"""Material and scenes for training and evaluating the detector.

Sources (all local, under `data/wakeword/`, outside Git):
- `corpus/`: short TTS clips of «Dina» and of similar words and phrases, split by speaker (`synthesize.py`).
- `speech/`: TTS sentences, split by text and speaker (`synthesize_speech.py`).
- `real/`: the owner's recordings (24 takes, private), split by session (`real_corpus.py`).
- `public/`: real Spanish readers (Multilingual LibriSpeech) and real room noise (DEMAND), `import_public.py`.

A scene puts a word where the phone hears it: after silence, room noise or someone talking, and followed by
silence or by the request («Dina, pon una alarma»). Scenes start with 2.5 s of context, the detector's warm-up.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

import numpy as np
from scipy import signal

from augment import CONDITIONS, transform
from real_corpus import load_sessions
from wakeword_core import SAMPLE_RATE, load_wav

DATA = Path("data/wakeword")
WARMUP_S = 2.5


@lru_cache(maxsize=None)
def wav(path: str) -> np.ndarray:
    return load_wav(Path(path))


@lru_cache(maxsize=None)
def _corpus() -> list[dict]:
    rows = json.loads((DATA / "corpus" / "manifest.json").read_text(encoding="utf-8-sig"))
    return [{**r, "path": str(DATA / "corpus" / r["file"])} for r in rows]


@lru_cache(maxsize=None)
def _speech() -> list[dict]:
    return [{**r, "path": str(DATA / "speech" / r["file"])} for manifest in sorted((DATA / "speech").glob("*/manifest.json"))
            for r in json.loads(manifest.read_text(encoding="utf-8"))]


@lru_cache(maxsize=None)
def _real() -> list[dict]:
    return [{**r, "path": str(r["path"])} for r in load_sessions(DATA / "real")] if (DATA / "real").exists() else []


@lru_cache(maxsize=None)
def _public(kind: str) -> list[dict]:
    return [{**r, "path": str(DATA / "public" / r["file"])} for manifest in sorted((DATA / "public" / kind).glob("**/manifest.json"))
            for r in json.loads(manifest.read_text(encoding="utf-8"))]


def clips(split: str, label: str) -> list[dict]:
    return [r for r in _corpus() if r["split"] == split and r["label"] == label and not r.get("continuous")]


def streams(split: str) -> list[dict]:
    return [r for r in _corpus() if r["split"] == split and r.get("continuous")]


def sentences(split: str, label: str = "negative") -> list[dict]:
    return [r for r in _speech() if r["split"] == split and r["label"] == label]


@lru_cache(maxsize=None)
def talk(split: str) -> tuple:
    """Speech without «Dina»: the app's requests (TTS) and real readers."""
    return tuple(sentences(split) + [r for r in _public("speech") if r["split"] == split])


def conversations(split: str, rng: np.random.Generator, minutes: float = 4.0) -> list[list[int]]:
    """`talk(split)` shuffled and cut into conversations of about `minutes`: every utterance once."""
    pool, groups, group, total = talk(split), [], [], 0.0
    for index in rng.permutation(len(pool)):
        group.append(int(index))
        total += pool[index]["seconds"]
        if total >= minutes * 60:
            groups.append(group)
            group, total = [], 0.0
    return groups + ([group] if group else [])


def real(split: str, label: str, continuous: bool = False) -> list[dict]:
    return [r for r in _real() if r["split"] == split and r["label"] == label and bool(r.get("continuous")) == continuous]


def word_bounds(row: dict, audio: np.ndarray) -> tuple[int, int]:
    """The word inside a positive clip: reviewed marks for real takes, the speech envelope for TTS."""
    if "word_start_s" in row:
        return int(row["word_start_s"] * SAMPLE_RATE), int(row["word_end_s"] * SAMPLE_RATE)
    frames = len(audio) // 320
    energy = np.sqrt((audio[:frames * 320].astype(np.float64).reshape(frames, 320) ** 2).mean(axis=1))
    active = np.flatnonzero(energy > max(1.0, float(energy.max()) * .015))
    if not len(active):
        raise ValueError(f"Silent clip: {row['path']}")
    return int(active[0]) * 320, (int(active[-1]) + 1) * 320


def floor_noise(samples: int, rng: np.random.Generator, rms: float | None = None) -> np.ndarray:
    """A quiet room as the S24 hears it (RMS ~10-35 between the owner's takes), slightly coloured."""
    rms = float(rng.uniform(6, 40)) if rms is None else rms
    noise = signal.lfilter([1], [1, -float(rng.uniform(0, .9))], rng.normal(0, 1, samples))
    return noise * rms / max(float(np.std(noise)), 1e-6)


def household(samples: int, rng: np.random.Generator) -> np.ndarray:
    t = np.arange(samples) / SAMPLE_RATE
    noise = signal.lfilter([1], [1, -.92], rng.normal(0, 1, samples))
    noise += 2 * np.sin(2 * np.pi * 50 * t) + np.sin(2 * np.pi * 100 * t)
    return noise / max(float(np.std(noise)), 1e-6)


def _speech_piece(pool: list[dict], rng: np.random.Generator, max_s: float) -> np.ndarray:
    audio = wav(pool[int(rng.integers(len(pool)))]["path"]).astype(np.float64)
    keep = int(max_s * SAMPLE_RATE)
    return audio[-keep:] if len(audio) > keep else audio


def _ambient_piece(pool: list[dict], rng: np.random.Generator, samples: int) -> np.ndarray:
    audio = wav(pool[int(rng.integers(len(pool)))]["path"]).astype(np.float64)
    if len(audio) < samples:
        audio = np.tile(audio, samples // max(len(audio), 1) + 1)
    start = int(rng.integers(len(audio) - samples + 1))
    return audio[start:start + samples]


def scene(word: np.ndarray, rng: np.random.Generator, split: str, *, follow: bool | None = None,
          condition: str = "normal") -> tuple[np.ndarray, int, int, bool]:
    """Context + word (+ request). Returns audio, word start, word end and whether speech follows."""
    word = transform(word, condition, rng, ambient_audio(split)).astype(np.float64)
    floor = float(rng.uniform(6, 40))
    speech = list(talk(split))
    warmup = int(rng.uniform(WARMUP_S, WARMUP_S + 1.0) * SAMPLE_RATE)
    parts = [floor_noise(warmup, rng, floor)]
    choice = rng.random()
    if speech and choice < .3:
        # Someone was talking just before: «… vale. Dina».
        parts += [_speech_piece(speech, rng, 2.0), floor_noise(int(rng.uniform(.15, .9) * SAMPLE_RATE), rng, floor)]
    elif ambient(split) and choice < .45:
        parts = [_ambient_piece(ambient(split), rng, warmup)]
    start = sum(map(len, parts))
    parts.append(word)
    end = start + len(word)
    follow = (bool(speech) and rng.random() < .4) if follow is None else follow and bool(speech)
    if follow:
        # «Dina, pon una alarma»: the request starts 50-450 ms after the word.
        parts += [floor_noise(int(rng.uniform(.05, .45) * SAMPLE_RATE), rng, floor), _speech_piece(speech, rng, 3.0)]
    parts.append(floor_noise(int(1.2 * SAMPLE_RATE), rng, floor))
    audio = np.concatenate(parts)
    audio += floor_noise(len(audio), rng, floor * .5)
    if rng.random() < .2:
        snr = float(rng.uniform(5, 25))
        level = max(float(np.sqrt(np.mean(word ** 2))), 1.0) / 10 ** (snr / 20)
        audio += household(len(audio), rng) * level
    return np.clip(audio, -32768, 32767).astype(np.int16), start, end, follow


@lru_cache(maxsize=None)
def ambient(split: str) -> tuple:
    """Real room audio without «Dina» (the owner's continuous negatives, DEMAND rooms), only for its own split."""
    return tuple(real(split, "negative", continuous=True) + [r for r in _public("noise") if r["split"] == split])


@lru_cache(maxsize=None)
def ambient_audio(split: str) -> list[np.ndarray]:
    return [wav(r["path"]) for r in ambient(split)]


def stitched(pool: list[dict], rng: np.random.Generator, minutes: float | None = None, split: str | None = None) -> np.ndarray:
    """Sentences one after another with natural pauses over a room: a long conversation.

    `minutes`: random sentences until that long; None: all of `pool` in order. With `split`, half of the
    conversations happen over that split's room noise (kitchen, washing machine, car…)."""
    parts, total, floor = [], 0, float(rng.uniform(8, 35))
    parts.append(floor_noise(int(WARMUP_S * SAMPLE_RATE), rng, floor))
    order = iter(range(len(pool))) if minutes is None else None
    while True:
        if order is None:
            if total >= minutes * 60 * SAMPLE_RATE:
                break
            row = pool[int(rng.integers(len(pool)))]
        else:
            row = next(order, None)
            if row is None:
                break
            row = pool[row]
        audio = wav(row["path"]).astype(np.float64) * rng.uniform(.3, 1.2)
        gap = floor_noise(int(rng.uniform(.1, 1.6) * SAMPLE_RATE), rng, floor)
        parts += [audio + floor_noise(len(audio), rng, floor), gap]
        total += len(audio) + len(gap)
    audio = np.concatenate(parts)
    rooms = list(ambient(split)) if split else []
    if rooms and rng.random() < .5:
        noise = _ambient_piece(rooms, rng, len(audio))
        audio += noise * rng.uniform(15, 120) / max(float(np.std(noise)), 1.0)
    return np.clip(audio, -32768, 32767).astype(np.int16)


def contextual(audio: np.ndarray, rng: np.random.Generator, condition: str = "normal", split: str = "train") -> np.ndarray:
    """A negative clip (similar word, phrase, real take) inside the same kind of context as a positive."""
    x = transform(audio, condition, rng, ambient_audio(split)).astype(np.float64)
    floor = float(rng.uniform(6, 40))
    lead = floor_noise(int(rng.uniform(WARMUP_S, WARMUP_S + .8) * SAMPLE_RATE), rng, floor)
    tail = floor_noise(int(rng.uniform(.5, 1.2) * SAMPLE_RATE), rng, floor)
    out = np.concatenate((lead, x + floor_noise(len(x), rng, floor * .5), tail))
    return np.clip(out, -32768, 32767).astype(np.int16)


__all__ = ["CONDITIONS", "WARMUP_S", "ambient", "clips", "contextual", "conversations", "real", "scene", "sentences",
           "stitched", "streams", "talk", "wav", "word_bounds"]
