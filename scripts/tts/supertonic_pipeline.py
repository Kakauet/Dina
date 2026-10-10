"""Supertonic 3 on the PC, the same pipeline as the app's SupertonicVoice, over swappable backends.

The text front end mirrors SupertonicText.kt (NFKD, symbols, final punctuation, <es>...</es>) for
sentences already in words (the app's SpanishSpeech turns digits into words before this step).
The noise comes from NumPy with a fixed seed, so two backends get exactly the same input.

Backend: OnnxBackend (ONNX Runtime CPU, as in the app).

A model directory whose onnx/ holds guidance.bin has the estimator split by supertonic_cfg.py (the
body graph, guidance done by the caller): then `guided_steps` chooses how many of the first steps
use the guidance (default: all, the original) and `after` what the others do (see GUIDE_AFTER).
"""
import json
import math
import os
import re
import sys
import time
import unicodedata

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import supertonic_cfg as cfg  # noqa: E402

GRAPHS = ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]

SYMBOLS = [("–", "-"), ("‑", "-"), ("—", "-"), ("¯", " "), ("_", " "), ("“", '"'), ("”", '"'), ("‘", "'"), ("’", "'"),
           ("´", "'"), ("`", "'"), ("[", " "), ("]", " "), ("|", " "), ("/", " "), ("#", " "), ("→", " "), ("←", " ")]
PUNCTUATION_SPACING = [(" ,", ","), (" .", "."), (" !", "!"), (" ?", "?"), (" ;", ";"), (" :", ":"), (" '", "'")]
ENDING = re.compile(r"[.!?;:,'\"')\]}…。」』】〉》›»]$")

# Typical answers, numbers already in words (as SpanishSpeech leaves them). Written here, not from benchmark/.
SENTENCES = [
    "Temporizador de pasta en marcha: cinco minutos.",
    "Quedan cuatro minutos y cincuenta y cinco segundos en el temporizador de pasta.",
    "Vale, alarma para mañana a las siete y media.",
    "Vale, leche, huevos y pan a la lista.",
    "Tienes tres cosas apuntadas: leche, huevos y pan.",
    "Son las nueve y media.",
    "Hecho: el volumen está al sesenta por ciento.",
    "Cronómetro en marcha.",
    "Son mil ochenta y uno.",
    "No tienes ninguna alarma por la mañana.",
    "¿Cuál es el colmo de un despertador? Que lo pongan de mal humor por las mañanas.",
    "Gracias, me alegro de que te haya gustado.",
    # Long answers and questions (appended, so earlier reference renders stay valid).
    "Tienes tres alarmas: una a las siete y media, otra a las ocho menos cuarto y la última a las nueve de la mañana, y además hay un temporizador de pasta en marcha.",
    "Te leo la lista de la compra: leche, huevos, pan, tomates, queso, manzanas y un paquete de arroz, en total siete productos.",
    "He puesto el temporizador de la pasta, cinco minutos, y te aviso cuando queden treinta segundos para que no se pase.",
    "¿Quieres que ponga la alarma a las siete y media o a las ocho?",
    "¿Seguro que quieres borrar todas las alarmas de la semana?",
]


class JavaRandom:
    """java.util.Random (LCG + polar Box-Muller), so a render here has the same noise as the app's SupertonicVoice."""

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
        self.pending = None

    def _next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def _double(self):
        return ((self._next(26) << 27) + self._next(27)) * (1.0 / (1 << 53))

    def gaussian(self):
        if self.pending is not None:
            value, self.pending = self.pending, None
            return value
        while True:
            v1 = 2 * self._double() - 1
            v2 = 2 * self._double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        multiplier = math.sqrt(-2 * math.log(s) / s)
        self.pending = v2 * multiplier
        return v1 * multiplier


def java_noise(seed, channels, frames, masked):
    """[1, channels, frames] as SupertonicVoice draws it: row-major, frames beyond `masked` drawn then zeroed."""
    random = JavaRandom(seed)
    noise = np.array([[random.gaussian() for _ in range(frames)] for _ in range(channels)], dtype=np.float32)
    noise[:, masked:] = 0
    return noise[None]


def preprocess(text, lang="es"):
    t = unicodedata.normalize("NFKD", text)
    for a, b in SYMBOLS:
        t = t.replace(a, b)
    t = re.sub(r"[♥☆♡©\\]", "", t)
    for a, b in PUNCTUATION_SPACING:
        t = t.replace(a, b)
    t = re.sub(r"([\"'`])\1+", r"\1", t)
    t = re.sub(r"\s+", " ", t).strip()
    if not ENDING.search(t):
        t += "."
    return f"<{lang}>{t}</{lang}>"


class Assets:
    """tts.json, the unicode indexer and one voice style from a Supertonic directory."""

    def __init__(self, model_dir, style="F2"):
        onnx_dir = os.path.join(model_dir, "onnx")
        cfg = json.load(open(os.path.join(onnx_dir, "tts.json"), encoding="utf-8"))
        self.sample_rate = cfg["ae"]["sample_rate"]
        self.chunk = cfg["ae"]["base_chunk_size"] * cfg["ttl"]["chunk_compress_factor"]
        self.channels = cfg["ttl"]["latent_dim"] * cfg["ttl"]["chunk_compress_factor"]
        self.indexer = json.load(open(os.path.join(onnx_dir, "unicode_indexer.json"), encoding="utf-8"))
        voice = json.load(open(os.path.join(model_dir, "voice_styles", f"{style}.json"), encoding="utf-8"))
        self.style_ttl = np.array(voice["style_ttl"]["data"], dtype=np.float32).reshape(voice["style_ttl"]["dims"])
        self.style_dp = np.array(voice["style_dp"]["data"], dtype=np.float32).reshape(voice["style_dp"]["dims"])

    def ids(self, text):
        chars = [c for c in preprocess(text) if ord(c) < len(self.indexer) and self.indexer[ord(c)] >= 0]
        return np.array([[self.indexer[ord(c)] for c in chars]], dtype=np.int64)

    def shapes(self, seconds):
        """SupertonicText.shapes: (samples, frames, masked frames)."""
        wav = np.float32(seconds) * np.float32(self.sample_rate)
        frames = max(1, int(math.floor((wav + self.chunk - 1) / self.chunk)))
        samples = int(wav)
        masked = max(1, min(frames, (samples + self.chunk - 1) // self.chunk))
        return samples, frames, masked


class OnnxBackend:
    name = "onnx"

    def __init__(self, model_dir, threads=4, session_config=None):
        import onnxruntime as ort
        guidance = os.path.join(model_dir, "onnx", "guidance.bin")
        self.guidance = np.fromfile(guidance, dtype="<f4") if os.path.exists(guidance) else None
        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        opts.inter_op_num_threads = 1
        opts.intra_op_num_threads = threads
        for key, value in (session_config or {}).items():
            opts.add_session_config_entry(key, value)
        self.sessions = {g: ort.InferenceSession(os.path.join(model_dir, "onnx", g + ".onnx"), opts, providers=["CPUExecutionProvider"])
                         for g in GRAPHS}

    def run(self, graph, feeds):
        return self.sessions[graph].run(None, feeds)

    def estimate(self, latent, text_emb, style_ttl, text_mask, latent_mask, current, total, guided):
        """Velocity rows of one step of a split estimator: [cond, uncond] (guided) or [cond]."""
        n = 2 if guided else 1
        emb, value, key = cfg.rows(self.guidance, text_emb, style_ttl, guided)
        rep = lambda a: np.repeat(a, n, axis=0) if a.shape[0] == 1 else a
        return self.sessions["vector_estimator"].run(["velocity"], {
            "noisy_latent": rep(latent), "text_emb": emb, "style_ttl": value, "style_key": key,
            "latent_mask": rep(latent_mask), "text_mask": rep(text_mask),
            "current_step": np.repeat(current, n), "total_step": np.repeat(total, n),
        })[0]


# What a step does after the guided ones (backend with a split estimator only):
#   plain  the conditioned row alone, v = cond                              (half the work of a guided step)
#   stale  the conditioned row, guided with the last guided step's uncond   v = W*cond - (W-1)*uncond_last
#   delta  the conditioned row plus the last guided step's guidance         v = cond + (W-1)*(cond_last - uncond_last)
GUIDE_AFTER = ("plain", "stale", "delta")


def synthesize(backend, assets, text, steps, speed=1.05, seed=20261007, guided_steps=None, after="plain", noise_source="numpy"):
    """One sentence → (float32 wav, {stage: ms}); the app's SupertonicVoice.synthesize."""
    ids = assets.ids(text)
    mask = np.ones((1, 1, ids.shape[1]), dtype=np.float32)
    t0 = time.perf_counter()
    seconds = float(backend.run("duration_predictor", {"text_ids": ids, "style_dp": assets.style_dp, "text_mask": mask})[0].reshape(-1)[0]) / speed
    t1 = time.perf_counter()
    text_emb = backend.run("text_encoder", {"text_ids": ids, "style_ttl": assets.style_ttl, "text_mask": mask})[0]
    t2 = time.perf_counter()
    samples, frames, masked = assets.shapes(seconds)
    if noise_source == "java":
        noise = java_noise(seed, assets.channels, frames, masked)
    else:
        noise = np.random.default_rng(seed).standard_normal((1, assets.channels, frames)).astype(np.float32)
        noise[:, :, masked:] = 0
    latent_mask = np.zeros((1, 1, frames), dtype=np.float32)
    latent_mask[:, :, :masked] = 1
    total = np.array([steps], dtype=np.float32)
    latent = noise
    split = getattr(backend, "guidance", None) is not None
    if not split and guided_steps is not None and guided_steps < steps:
        raise ValueError("this model has the guidance inside the graph: use supertonic_cfg.py split")
    last = None  # (cond, uncond) velocity of the last guided step
    step_ms = {"guided": [], "plain": []}
    for step in range(steps):
        current = np.array([step], dtype=np.float32)
        if not split:
            latent = backend.run("vector_estimator", {
                "noisy_latent": latent, "text_emb": text_emb, "style_ttl": assets.style_ttl, "text_mask": mask,
                "latent_mask": latent_mask, "current_step": current, "total_step": total,
            })[0]
            continue
        guided = guided_steps is None or step < guided_steps
        started = time.perf_counter()
        velocity = backend.estimate(latent, text_emb, assets.style_ttl, mask, latent_mask, current, total, guided)
        step_ms["guided" if guided else "plain"].append((time.perf_counter() - started) * 1e3)
        if guided:
            last = (velocity[:1], velocity[1:2])
            v = cfg.combine(velocity, True)
        elif after == "plain" or last is None:
            v = velocity[:1]
        elif after == "stale":
            v = cfg.GUIDANCE_SCALE * velocity[:1] - (cfg.GUIDANCE_SCALE - 1.0) * last[1]
        else:
            v = velocity[:1] + (cfg.GUIDANCE_SCALE - 1.0) * (last[0] - last[1])
        latent = (latent + v / total.reshape(-1, 1, 1)) * latent_mask
    t3 = time.perf_counter()
    wav = backend.run("vocoder", {"latent": latent})[0].reshape(-1)[:samples]
    t4 = time.perf_counter()
    stages = {"duration": (t1 - t0) * 1e3, "encoder": (t2 - t1) * 1e3, "estimator": (t3 - t2) * 1e3, "vocoder": (t4 - t3) * 1e3}
    stages["step_ms"] = step_ms  # only for a split estimator
    return wav.astype(np.float32), stages


def log_mel(wav, sample_rate=44100, n_fft=2048, hop=512, mels=80):
    """Log-mel spectrogram (NumPy only) for comparing two renderings of the same sentence."""
    if len(wav) < n_fft:
        wav = np.pad(wav, (0, n_fft - len(wav)))
    frames = 1 + (len(wav) - n_fft) // hop
    window = np.hanning(n_fft).astype(np.float32)
    idx = np.arange(n_fft)[None, :] + hop * np.arange(frames)[:, None]
    spec = np.abs(np.fft.rfft(wav[idx] * window, axis=1)) ** 2
    hz = np.linspace(0, sample_rate / 2, n_fft // 2 + 1)
    mel = lambda f: 2595 * np.log10(1 + f / 700)
    edges = 700 * (10 ** (np.linspace(mel(0), mel(sample_rate / 2), mels + 2) / 2595) - 1)
    bank = np.zeros((mels, len(hz)), dtype=np.float32)
    for m in range(mels):
        lo, mid, hi = edges[m], edges[m + 1], edges[m + 2]
        bank[m] = np.clip(np.minimum((hz - lo) / (mid - lo), (hi - hz) / (hi - mid)), 0, None)
    return np.log(spec @ bank.T + 1e-6)


def mel_distance(a, b):
    """Mean absolute log-mel difference (dB-like) over the common length."""
    n = min(len(a), len(b))
    return float(np.mean(np.abs(log_mel(a[:n]) - log_mel(b[:n]))))


def write_wav(path, wav, sample_rate=44100):
    import wave
    pcm = (np.clip(wav, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sample_rate)
        f.writeframes(pcm.tobytes())


def rss_mb(field="VmRSS"):
    """Resident memory of this process (Linux), MB: VmRSS, or RssAnon / RssFile (heap vs mapped files)."""
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith(field + ":"):
                return int(line.split()[1]) / 1024
    return float("nan")
