"""Public audio for the detector: real Spanish voices and real household noise, split like the rest.

Sources (CC BY 4.0, credited in THIRD_PARTY.md), downloaded to `data/wakeword/downloads/`:
- Multilingual LibriSpeech, Spanish (huggingface.co/datasets/facebook/multilingual_librispeech, `spanish/`):
  `9_hours` -> train, `dev` -> calibration, `test` -> test. Each part has its own readers.
  Utterances whose transcript says «Dina» are dropped; «divina», «cocina»… stay as hard negatives.
- DEMAND 16 kHz (zenodo.org/records/1227121, `<ROOM>_16k.zip`): kitchen, living room, washing machine, cafeteria,
  car. Only `ch01.wav` and `ch09.wav` of each zip are used: extract them to `downloads/DEMAND/<ROOM>/`.
  Two microphones per room at RMS 60, cut in time: 60 % train, 20 % calibration, 20 % test.

    python scripts/wakeword/import_public.py   -> data/wakeword/public/{speech,noise}/<split>/ + manifest.json
"""
from __future__ import annotations

import argparse
import io
import json
import re
from collections import defaultdict
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile

from wakeword_core import SAMPLE_RATE, write_wav

SPEECH = {"train": ("mls_9_hours-00000-of-00001.parquet", None),
          "calibration": ("mls_dev-00000-of-00001.parquet", 5.0),
          "test": ("mls_test-00000-of-00001.parquet", 5.0)}
ROOMS = ("DKITCHEN", "DLIVING", "DWASHING", "PCAFETER", "TCAR")
MICROPHONES = ("ch01", "ch09")
CUTS = {"train": (0, .6), "calibration": (.6, .8), "test": (.8, 1)}
DINA = re.compile(r"\bdina\b", re.IGNORECASE)


def speech(downloads: Path, output: Path, split: str) -> dict:
    name, hours = SPEECH[split]
    table = pq.read_table(downloads / name, columns=["audio", "transcript", "speaker_id", "audio_duration", "id"])
    by_speaker = defaultdict(list)
    for row in table.to_pylist():
        if not DINA.search(row["transcript"]):
            by_speaker[row["speaker_id"]].append(row)
    # Round robin over readers: the hours budget keeps as many different voices as possible.
    queues = [by_speaker[s] for s in sorted(by_speaker)]
    chosen, total = [], 0.0
    while any(queues) and (hours is None or total < hours * 3600):
        for queue in queues:
            if queue and (hours is None or total < hours * 3600):
                row = queue.pop(0)
                chosen.append(row)
                total += row["audio_duration"]
    folder = output / "speech" / split
    folder.mkdir(parents=True, exist_ok=True)
    rows = []
    for row in chosen:
        audio, rate = soundfile.read(io.BytesIO(row["audio"]["bytes"]), dtype="int16")
        if rate != SAMPLE_RATE:
            raise ValueError(f"{row['id']}: {rate} Hz")
        path = folder / f"{row['id']}.wav"
        write_wav(path, audio, SAMPLE_RATE)
        rows.append({"file": f"speech/{split}/{path.name}", "split": split, "speaker": str(row["speaker_id"]),
                     "seconds": round(len(audio) / SAMPLE_RATE, 3), "text": row["transcript"], "label": "negative"})
    (folder / "manifest.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"utterances": len(rows), "speakers": len({r["speaker"] for r in rows}),
            "hours": round(sum(r["seconds"] for r in rows) / 3600, 2)}


def noise(downloads: Path, output: Path) -> dict:
    rows = []
    for room in ROOMS:
        for microphone in MICROPHONES:
            member = downloads / "DEMAND" / room / f"{microphone}.wav"
            audio, rate = soundfile.read(member, dtype="int16")
            if rate != SAMPLE_RATE:
                raise ValueError(f"{member}: {rate} Hz")
            # A noisy room as the phone hears it: RMS 60, a bit over the silence gate (40).
            audio = audio.astype(np.float64) * 60 / max(float(np.std(audio)), 1.0)
            for split, (a, b) in CUTS.items():
                folder = output / "noise" / split
                folder.mkdir(parents=True, exist_ok=True)
                path = folder / f"{room.lower()}_{microphone}.wav"
                write_wav(path, audio[int(a * len(audio)):int(b * len(audio))], SAMPLE_RATE)
                rows.append({"file": f"noise/{split}/{path.name}", "split": split, "room": room})
    (output / "noise" / "manifest.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return {"files": len(rows), "rooms": len(ROOMS)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--downloads", type=Path, default=Path("data/wakeword/downloads"))
    parser.add_argument("--output", type=Path, default=Path("data/wakeword/public"))
    args = parser.parse_args()
    if not (args.downloads / SPEECH["test"][0]).exists():
        raise SystemExit(f"Download the public audio to {args.downloads} first (scripts/wakeword/README.md)")
    report = {split: speech(args.downloads, args.output, split) for split in SPEECH}
    report["noise"] = noise(args.downloads, args.output)
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
