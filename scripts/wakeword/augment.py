"""Deterministic speech transforms. Simulated whisper/distance are proxies, not real recordings."""
from __future__ import annotations
import warnings

import numpy as np
from scipy import signal
from wakeword_core import SAMPLE_RATE

warnings.filterwarnings("ignore", category=FutureWarning, module="librosa")


def transform(audio: np.ndarray, condition: str, rng: np.random.Generator,
              backgrounds: list[np.ndarray] | None = None) -> np.ndarray:
    x = audio.astype(np.float64)
    if condition in {"fast", "slow", "pitch", "question", "tired"}:
        import librosa
        y = (x / 32768).astype(np.float32)
        if condition in {"fast", "slow", "tired"}:
            rate = rng.uniform(1.15, 1.45) if condition == "fast" else rng.uniform(.65, .9)
            y = librosa.effects.time_stretch(y, rate=rate)
        if condition in {"pitch", "question", "tired"}:
            shift = rng.uniform(-3., 3.) if condition == "pitch" else (-2. if condition == "tired" else 3.)
            shifted = librosa.effects.pitch_shift(y, sr=SAMPLE_RATE, n_steps=shift)
            if condition == "question":
                # Rising pitch proxy: crossfade unshifted speech into a higher-pitched ending.
                blend = np.linspace(0, 1, len(y)) ** 2
                y = y * (1 - blend) + shifted * blend
            else:
                y = shifted
        x = y.astype(np.float64) * 32768
    elif condition == "whisper_simulated":
        envelope = np.sqrt(signal.convolve(x*x, np.ones(160)/160, mode="same"))
        noise = signal.sosfilt(signal.butter(2, [500, 7000], fs=SAMPLE_RATE, btype="band", output="sos"),
                               rng.normal(0, 1, len(x)))
        # Retain some articulation; this cannot reproduce human whispered formants.
        x = .25*x + .75*noise*envelope
        x *= rng.uniform(.025, .12)
    elif condition.startswith("distance_"):
        distance = int(condition.rsplit("_", 1)[1])
        dry = x.copy()
        rt60 = rng.uniform(.2, .7)
        impulse = rng.normal(0, 1, int(rt60*SAMPLE_RATE))
        impulse *= np.exp(-np.arange(len(impulse)) / (SAMPLE_RATE*rt60/6.91))
        impulse *= .22 / max(np.linalg.norm(impulse), 1e-6)
        impulse[0] += 1
        x = signal.fftconvolve(dry, impulse)[:len(dry)] / distance
        x = signal.sosfilt(signal.butter(2, rng.uniform(2500, 5500), fs=SAMPLE_RATE, output="sos"), x)
        x *= rng.uniform(.08, .35)
    elif condition == "quiet":
        x *= rng.uniform(.025, .22)
    elif condition == "shout":
        x = 32767 * np.tanh(x / 32767 * rng.uniform(1.5, 3.))
    elif condition in {"household", "babble"}:
        if backgrounds:
            noise = backgrounds[int(rng.integers(len(backgrounds)))].astype(np.float64)
            if len(noise) < len(x):
                noise = np.tile(noise, int(np.ceil(len(x)/max(len(noise), 1))))
            start = int(rng.integers(0, len(noise)-len(x)+1))
            noise = noise[start:start+len(x)]
        else:
            # Mechanical hum and colored noise; do not label this as real television audio.
            t = np.arange(len(x)) / SAMPLE_RATE
            noise = signal.lfilter([1], [1, -.92], rng.normal(0, 1, len(x)))
            noise += 2*np.sin(2*np.pi*50*t) + np.sin(2*np.pi*100*t)
            if len(noise):
                noise[int(rng.integers(len(noise)))] += 60
        rms = max(float(np.sqrt(np.mean(x*x))), 1.)
        noise_rms = max(float(np.sqrt(np.mean(noise*noise))), 1.)
        x += noise * rms / (noise_rms * 10**(rng.uniform(0, 25)/20))
    elif condition != "normal":
        raise ValueError(condition)
    if not np.all(np.isfinite(x)):
        raise ValueError(f"Non-finite augmented audio: {condition}")
    return np.clip(x, -32768, 32767).astype(np.int16)


CONDITIONS = ("normal", "quiet", "whisper_simulated", "shout", "question", "tired", "fast", "slow",
              "pitch", "distance_1", "distance_3", "distance_5", "household", "babble")
