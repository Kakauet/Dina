"""Read locally recorded sessions. No network calls; never split individual clips."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import wave
from collections import Counter
from pathlib import Path

SPLITS = {"train", "calibration", "test"}
CONDITIONS = {"normal", "whisper", "shout", "question", "fast", "slow", "tired",
              "similar", "conversation", "tv", "ambient"}


def load_sessions(root: Path) -> list[dict]:
    rows = []
    hashes: dict[str, str] = {}
    for session_file in sorted(root.glob("*/session.json")):
        session = json.loads(session_file.read_text(encoding="utf-8-sig"))
        directory = session_file.parent
        if session.get("split") not in SPLITS or session.get("session_id") != directory.name:
            raise ValueError(f"Invalid immutable session split: {directory.name}")
        if session.get("consent") is not True:
            raise ValueError(f"Missing recording consent: {directory.name}")
        manifest = directory / "manifest.jsonl"
        if not manifest.exists():
            continue
        annotations_file = directory / "annotations.json"
        annotations = json.loads(annotations_file.read_text(encoding="utf-8")) if annotations_file.exists() else {}
        for line in manifest.read_text(encoding="utf-8-sig").splitlines():
            row = json.loads(line)
            if row.get("split") != session["split"] or row.get("session_id") != session["session_id"]:
                raise ValueError(f"A clip changed the session split: {directory.name}")
            if row.get("label") not in {"positive", "negative"} or row.get("condition") not in CONDITIONS:
                raise ValueError("Unknown recording label")
            if any(c in row["file"] for c in ("/", "\\", ":")) or Path(row["file"]).name != row["file"] or not row["file"].endswith(".wav"):
                raise ValueError("Unsafe recording filename")
            audio = directory / row["file"]
            with wave.open(str(audio), "rb") as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                    raise ValueError(f"Expected mono PCM16 16 kHz: {audio}")
                samples = wav.getnframes()
                if samples != row["samples"] or samples <= 0:
                    raise ValueError(f"Incomplete recording: {audio}")
            fingerprint = hashlib.sha256(audio.read_bytes()).hexdigest()
            previous = hashes.setdefault(fingerprint, session["split"])
            if previous != session["split"]:
                raise ValueError("Identical audio occurs in different splits")
            interval = annotations.get(row["file"], {})
            if interval:
                if set(interval) != {'word_start_s', 'word_end_s'}:
                    raise ValueError('Unknown annotation fields')
                start, end = interval["word_start_s"], interval["word_end_s"]
                if row["label"] != "positive" or not all(math.isfinite(x) for x in (start, end)) or not 0 <= start < end <= samples / 16000:
                    raise ValueError(f"Invalid wake word interval: {audio}")
            rows.append({**row, **interval, "path": audio, "duration_s": samples / 16000,
                         "parent_session_id": session.get('parent_session_id',session['session_id']),
                         "independent_session": session.get('independent_session',True),
                         "split_strategy": session.get('split_strategy','session'),
                         "source": "real", "voice": session["session_id"]})
    return rows


def summary(rows: list[dict]) -> dict:
    return {split: {"sessions": len({r["session_id"] for r in rows if r["split"] == split}),
                    "positive_clips": sum(r["label"] == "positive" and r["split"] == split for r in rows),
                    "continuous_negative_hours": round(sum(r["duration_s"] for r in rows
                        if r["split"] == split and r["label"] == "negative" and r.get("continuous")) / 3600, 3)}
            for split in sorted(SPLITS)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("data/wakeword/real"))
    args = parser.parse_args()
    print(json.dumps(summary(load_sessions(args.root)), ensure_ascii=False))
