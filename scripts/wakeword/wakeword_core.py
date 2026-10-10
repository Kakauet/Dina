"""Shared code of the «Dina» detector: the app's streaming front-end, the head, the decision and the silence gate.

Everything here mirrors `voice/WakeWordDetector.kt` exactly, so what the tools measure is what the phone does:
every 80 ms (1,280 samples) openWakeWord's mel model adds 8 frames, the embedding model turns the last 76 frames
into one 96-value embedding, and the head scores the last 16 embeddings (about 2 s of audio). The mel frames only
depend on their own samples, so a whole recording can be computed at once with the same result.
"""
from __future__ import annotations

import math
import wave
from pathlib import Path

import numpy as np
from scipy import signal

SAMPLE_RATE = 16_000
CHUNK = 1_280                     # one detector step: 80 ms
EMBEDDINGS = 16                   # the head sees 16 embeddings: 1,536 values
WARMUP_CHUNKS = 25                # chunks before the first complete feature vector (2 s)
FRONT_END = Path("models/voice/openwakeword")

# Decision (WakeDecision.kt): two consecutive steps over the threshold; one event per REFRACTORY_S.
HITS = 2
REFRACTORY_S = 2.0

# Silence gate (WakeGate.kt): the neural front-end sleeps while every chunk of the last HOLD_CHUNKS
# stayed under the level; on waking, those chunks are replayed so the score is the same as always-on.
GATE_MIN, GATE_MAX, GATE_RATIO = 40.0, 150.0, 3.0
GATE_HOLD_CHUNKS = 26
FLOOR_START, FLOOR_RISE = 50.0, 1.01


def load_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as wav:
        channels, width, rate = wav.getnchannels(), wav.getsampwidth(), wav.getframerate()
        raw = wav.readframes(wav.getnframes())
    if width != 2:
        raise ValueError(f"{path}: expected PCM16")
    audio = np.frombuffer(raw, dtype="<i2").astype(np.float32)
    if channels > 1:
        audio = audio.reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE:
        divisor = math.gcd(rate, SAMPLE_RATE)
        audio = signal.resample_poly(audio, SAMPLE_RATE // divisor, rate // divisor)
    return np.clip(audio, -32768, 32767).astype(np.int16)


def write_wav(path: Path, audio: np.ndarray, rate: int = SAMPLE_RATE) -> None:
    if rate != SAMPLE_RATE:
        divisor = math.gcd(rate, SAMPLE_RATE)
        audio = signal.resample_poly(np.asarray(audio, dtype=np.float64), SAMPLE_RATE // divisor, rate // divisor)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
        wav.writeframes(np.clip(audio, -32768, 32767).astype("<i2").tobytes())


def _session(path: Path, threads: int):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])


class FrontEnd:
    """openWakeWord's frozen mel + embedding models, computed like the app's streaming detector."""

    def __init__(self, threads: int = 1, root: Path = FRONT_END):
        self.mel = _session(root / "melspectrogram.onnx", threads)
        self.embedding = _session(root / "embedding_model.onnx", threads)

    def features(self, audio: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """(end sample of each step, its 1,536 features) for every complete 80 ms step after the 2 s warm-up."""
        chunks = len(audio) // CHUNK
        if chunks < WARMUP_CHUNKS:
            return np.empty(0, dtype=np.int64), np.empty((0, EMBEDDINGS * 96), dtype=np.float32)
        # The mel model scales each call to its own loudest frame, so it runs on exactly the app's input:
        # one call per chunk on the last 1,760 samples (480 of them from the chunk before; zeros at the start).
        x = np.concatenate((np.zeros(480, dtype=np.float32), audio[:chunks * CHUNK].astype(np.float32)))
        frames = np.concatenate([self.mel.run(None, {"input": x[None, k * CHUNK:k * CHUNK + 1760]})[0].reshape(-1, 32)[-8:]
                                 for k in range(chunks)]) / 10 + 2
        # After chunk k the mel history holds frames [8k-68, 8k+8); its embedding uses those 76 frames.
        first = 9
        starts = 8 * np.arange(first, chunks) - 68
        windows = np.stack([frames[s:s + 76] for s in starts])[..., None]
        embeddings = np.concatenate([self.embedding.run(None, {"input_1": windows[i:i + 512]})[0].reshape(-1, 96)
                                     for i in range(0, len(windows), 512)])
        index = np.arange(EMBEDDINGS - 1, len(embeddings))
        features = np.stack([embeddings[i - EMBEDDINGS + 1:i + 1].reshape(-1) for i in index]).astype(np.float32)
        ends = (index + first + 1) * CHUNK
        return ends.astype(np.int64), features


def score_vector(model, vectors: np.ndarray) -> np.ndarray:
    """The head on one vector or a batch: an `.npz` MLP (training) or an ONNX head (`OrtHead`)."""
    vectors = np.atleast_2d(vectors).astype(np.float32)
    if isinstance(model, OrtHead):
        return model.score(vectors)
    normalized = (vectors - model["mean"]) / model["scale"]
    hidden = np.maximum(0.0, normalized @ model["w1"] + model["b1"])
    logits = (hidden @ model["w2"] + model["b2"]).reshape(-1)
    return 1.0 / (1.0 + np.exp(-np.clip(logits, -30, 30)))


class OrtHead:
    """An exported head, scored with ONNX Runtime like the app."""

    def __init__(self, path: Path):
        self.session = _session(path, 1)

    def score(self, vectors: np.ndarray) -> np.ndarray:
        return np.array([float(self.session.run(None, {"features": v.reshape(1, -1)})[0].reshape(-1)[0])
                         for v in vectors])


def chunk_rms(audio: np.ndarray) -> np.ndarray:
    chunks = len(audio) // CHUNK
    x = audio[:chunks * CHUNK].astype(np.float64).reshape(chunks, CHUNK)
    return np.sqrt((x * x).mean(axis=1))


def gate_awake(audio: np.ndarray) -> np.ndarray:
    """For each chunk: is the front-end running? Same arithmetic as WakeGate.kt (float rounding aside)."""
    floor, quiet = FLOOR_START, GATE_HOLD_CHUNKS
    awake = []
    for rms in chunk_rms(audio):
        level = min(GATE_MAX, max(GATE_MIN, floor * GATE_RATIO))
        quiet = 0 if rms >= level else quiet + 1
        floor = rms if rms < floor else floor * FLOOR_RISE
        awake.append(quiet < GATE_HOLD_CHUNKS)
    return np.asarray(awake, dtype=bool)


def step_scores(model, front: FrontEnd, audio: np.ndarray, gated: bool = True) -> tuple[np.ndarray, np.ndarray]:
    """The app's score at every step (0 while the gate sleeps)."""
    ends, features = front.features(audio)
    scores = score_vector(model, features) if len(features) else np.empty(0)
    if gated and len(ends):
        scores = np.where(gate_awake(audio)[ends // CHUNK - 1], scores, 0.0)
    return ends, scores


def events(ends: np.ndarray, scores: np.ndarray, threshold: float) -> list[int]:
    """End samples where the app would activate: HITS consecutive steps >= threshold, then a refractory pause."""
    streak, allowed, found = 0, -1, []
    for end, score in zip(ends, scores):
        if end < allowed or not score >= threshold:
            streak = 0
            continue
        streak += 1
        if streak >= HITS:
            found.append(int(end))
            allowed = end + int(REFRACTORY_S * SAMPLE_RATE)
            streak = 0
    return found


def hits_by_threshold(ends: np.ndarray, scores: np.ndarray, thresholds: np.ndarray) -> np.ndarray:
    """Number of events for every threshold at once (the threshold sweep of calibration)."""
    return np.array([len(events(ends, scores, t)) for t in thresholds])
