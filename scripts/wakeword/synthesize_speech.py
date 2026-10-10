"""Spanish TTS sentences for the detector: conversation it must ignore and «Dina, …» requests.

Texts are the data pipeline's phrases (`data/batches/*/phrases.jsonl`) and the joke and fact banks; never
benchmark text. Each text goes to one split (by hash) and each split has its own voices, so calibration and
test measure unseen texts and unseen speakers. Only locally installed voices are used; nothing is downloaded.
-> data/wakeword/speech/<split>/*.wav + manifest.json (one process per split runs them in parallel)
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import subprocess
import tempfile
import unicodedata
import wave
from pathlib import Path

import numpy as np

from wakeword_core import load_wav, write_wav

VOICES = {
    # Supertonic F2 is Dina's own voice: she must never wake herself. It is slow, so it says one sentence in ten.
    "train": ["piper:es_ES-sharvard-medium:0", "piper:es_ES-mls_10246-low:0", "supertonic:F2",
              "windows:Microsoft Helena Desktop", "windows:Microsoft Zira Desktop"],
    "calibration": ["piper:es_ES-sharvard-medium:1", "windows:Microsoft David Desktop"],
    "test": ["piper:es_AR-daniela-high:0"],
}


def normalized(text: str) -> str:
    return unicodedata.normalize("NFD", text.lower()).encode("ascii", "ignore").decode()


def mentions_dina(text: str) -> bool:
    return re.search(r"\bdina\b", normalized(text)) is not None


def text_split(text: str) -> str:
    bucket = int(hashlib.sha1(normalized(text).encode()).hexdigest()[:8], 16) % 100
    return "train" if bucket < 70 else "calibration" if bucket < 85 else "test"


def load_texts() -> list[str]:
    texts = set()
    for path in Path("data/batches").glob("*/phrases.jsonl"):
        for line in path.read_text(encoding="utf-8").splitlines():
            texts.add(json.loads(line)["text"].strip())
    for path in Path("android/app/src/main/resources/dina/fun").glob("*.txt"):
        texts.update(line.strip() for line in path.read_text(encoding="utf-8").splitlines()
                     if line.strip() and not line.startswith("#"))
    return sorted(t for t in texts if 3 <= len(t) <= 220)


def piper_audio(cache: dict, voice: str, text: str, speed: float) -> tuple[np.ndarray, int]:
    from piper import PiperVoice, SynthesisConfig
    _, model, speaker = voice.split(":")
    if model not in cache:
        cache[model] = PiperVoice.load(Path("models/voice/piper") / f"{model}.onnx")
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        cache[model].synthesize_wav(text, out, syn_config=SynthesisConfig(speaker_id=int(speaker), length_scale=1 / speed))
    buffer.seek(0)
    with wave.open(buffer, "rb") as out:
        return np.frombuffer(out.readframes(out.getnframes()), dtype="<i2"), out.getframerate()


def supertonic_audio(cache: dict, voice: str, text: str, speed: float) -> tuple[np.ndarray, int]:
    from supertonic import TTS
    if "supertonic" not in cache:
        cache["supertonic"] = TTS(model_dir=Path("models/tts/supertonic3"), auto_download=False,
                                  intra_op_num_threads=4, inter_op_num_threads=1)
    tts = cache["supertonic"]
    audio, _ = tts.synthesize(text, voice_style=tts.get_voice_style(voice.split(":")[1]), lang="es",
                              speed=speed, total_steps=5)
    return np.asarray(audio).reshape(-1) * 32767, tts.sample_rate


WINDOWS_SCRIPT = r"""
param([string]$Jobs)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 16, 1)
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    foreach ($job in (Get-Content -Raw -Encoding UTF8 $Jobs | ConvertFrom-Json)) {
        $speaker.SelectVoice($job.voice)
        $speaker.Rate = [int]$job.rate
        $speaker.SetOutputToWaveFile($job.path, $format)
        $speaker.Speak($job.text)
        $speaker.SetOutputToNull()
    }
} finally { $speaker.Dispose() }
"""


def windows_batch(jobs: list[dict]) -> None:
    if not jobs:
        return
    with tempfile.TemporaryDirectory() as tmp:
        script, listing = Path(tmp) / "speak.ps1", Path(tmp) / "jobs.json"
        script.write_text(WINDOWS_SCRIPT, encoding="utf-8")
        listing.write_text(json.dumps(jobs, ensure_ascii=False), encoding="utf-8")
        subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
                        "-Jobs", str(listing)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("data/wakeword/speech"))
    parser.add_argument("--split", choices=("train", "calibration", "test"), action="append",
                        help="Only these splits (default: all)")
    parser.add_argument("--shard", help="K/N: only synthesize every N-th sentence starting at K (run N in parallel, "
                                         "then once without --shard to write the manifest)")
    parser.add_argument("--limit", type=int, help="Smoke test: texts per split")
    parser.add_argument("--existing", action="store_true", help="Only write the manifest of the sentences already synthesized")
    args = parser.parse_args()
    by_split: dict[str, list[str]] = {"train": [], "calibration": [], "test": []}
    for text in load_texts():
        by_split[text_split(text)].append(text)
    for split in args.split or list(by_split):
        synthesize_split(args, split, by_split[split])


def synthesize_split(args, split: str, texts: list[str]) -> None:
    rng = np.random.default_rng({"train": 2207, "calibration": 2208, "test": 2209}[split])
    rows, windows_jobs, cache = [], [], {}
    shard, shards = map(int, args.shard.split("/")) if args.shard else (0, 1)
    if args.limit:
        texts = texts[:args.limit]
    folder = args.output / split
    folder.mkdir(parents=True, exist_ok=True)
    for index, text in enumerate(texts):
        voices = VOICES[split]
        weights = np.array([.225 if v != "supertonic:F2" else .1 for v in voices])
        voice = voices[int(rng.choice(len(voices), p=weights / weights.sum()))]
        speed = float(rng.uniform(.85, 1.2))
        name = f"{split}_{index:05d}.wav"
        path = folder / name
        if args.existing and not path.exists():
            continue
        rows.append({"file": f"{split}/{name}", "split": split, "voice": voice, "speed": round(speed, 3),
                     "text": text, "label": "positive" if mentions_dina(text) else "negative"})
        if path.exists() or index % shards != shard:
            continue
        if voice.startswith("windows:"):
            windows_jobs.append({"voice": voice.split(":", 1)[1], "rate": int(round((speed - 1) * 10)),
                                 "text": text, "path": str(path.resolve())})
            continue
        audio, rate = (piper_audio if voice.startswith("piper:") else supertonic_audio)(cache, voice, text, speed)
        write_wav(path, audio, rate)
        if len(rows) % 200 == 0:
            print(f"{len(rows)} sentences", flush=True)
    windows_batch(windows_jobs)
    if args.shard:
        return
    for row in rows:
        audio = load_wav(args.output / row["file"])
        if not np.any(audio):
            raise ValueError(f"Silent synthesis: {row['file']}")
        row["seconds"] = round(len(audio) / 16000, 3)
    (args.output / split / "manifest.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({split: {"sentences": len(rows), "hours": round(sum(r["seconds"] for r in rows) / 3600, 2),
                              "with_dina": sum(r["label"] == "positive" for r in rows)}}), flush=True)


if __name__ == "__main__":
    main()
