"""Measure heads exactly as the app runs them (streaming front-end, silence gate, two hits) on held-out material.

    python scripts/wakeword/evaluate.py calibrate   # calibration split -> reports/calibration.json (thresholds)
    python scripts/wakeword/evaluate.py test        # test split, once  -> reports/evaluation.json

Cases (only the chosen split: unseen speakers, texts and recording sessions):
- «Dina» alone or followed by a request, from TTS voices in 14 conditions (whisper, distance, noise, tone…);
- TTS sentences that contain «Dina» («Hola, Dina, pon…»): any activation during the sentence counts;
- the owner's recorded takes (test split), whose word was marked by hand;
- negatives: similar words and phrases in context and conversations (the app's requests and real readers, every
  utterance once, half over real room noise), for false activations per hour.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

import numpy as np

import dataset as ds
from wakeword_core import SAMPLE_RATE, FrontEnd, OrtHead, events, gate_awake, score_vector

SEEDS = {"calibration": 31, "test": 47}
REPORTS = Path("scripts/wakeword/reports")
# Sensitivity levels of Ajustes › Escucha: false activations per hour of nonstop conversation on calibration.
LEVELS = {"high": 2.0, "normal": .7, "low": .2}
_front: FrontEnd | None = None


def _init() -> None:
    global _front
    _front = FrontEnd()


def floor_lead(audio: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """A recorded take starts ~1 s before the word: prepend the warm-up at the take's own noise level."""
    rms = float(np.sqrt(np.mean(audio[:4800].astype(np.float64) ** 2)))
    lead = ds.floor_noise(int(ds.WARMUP_S * SAMPLE_RATE), rng, rms)
    return np.clip(np.concatenate((lead, audio.astype(np.float64))), -32768, 32767).astype(np.int16)


def build(case: dict) -> tuple[np.ndarray, tuple[int, int] | None]:
    """Audio of a case and its activation window (None for negatives)."""
    rng = np.random.default_rng(case["seed"])
    audio = ds.wav(case["path"]) if case.get("path") else None
    kind = case["kind"]
    if kind == "tts":
        start, end = ds.word_bounds({}, audio)
        word = audio[max(0, start - 1600):end + 1600]
        scene, ws, we, _ = ds.scene(word, rng, case["split"], follow=case["follow"], condition=case["condition"])
        return scene, (ws, we + SAMPLE_RATE)
    if kind == "sentence":
        scene = ds.contextual(audio, rng, case["condition"], case["split"])
        return scene, (int(ds.WARMUP_S * SAMPLE_RATE), len(scene))
    if kind == "real":
        scene = floor_lead(audio, rng)
        offset = int(ds.WARMUP_S * SAMPLE_RATE)
        return scene, (offset + int(case["word"][0] * SAMPLE_RATE), offset + int((case["word"][1] + 1.0) * SAMPLE_RATE))
    if kind == "negative":
        return ds.contextual(audio, rng, case["condition"], case["split"]), None
    if kind == "real_negative":
        return floor_lead(audio, rng), None
    if kind == "conversation":
        pool = ds.talk(case["split"])
        return ds.stitched([pool[i] for i in case["items"]], rng, split=case["split"]), None
    if kind == "stream":
        return audio, None
    raise ValueError(kind)


def features_job(case: dict):
    audio, window = build(case)
    ends, x = _front.features(audio)
    awake = gate_awake(audio)[ends // 1280 - 1] if len(ends) else np.empty(0, dtype=bool)
    return case, window, len(audio) / SAMPLE_RATE, ends, x.astype(np.float32), awake


def cases(split: str) -> list[dict]:
    rng = np.random.default_rng(SEEDS[split])
    seed = lambda: int(rng.integers(2**31))
    out = []
    for row in ds.clips(split, "positive"):
        for condition in ds.CONDITIONS:
            for follow in (False, True):
                out.append({"kind": "tts", "group": f"tts/{condition}", "follow": follow, "condition": condition,
                            "path": row["path"], "split": split, "seed": seed()})
    for row in ds.sentences(split, "positive"):
        out.append({"kind": "sentence", "group": "sentence_with_dina", "condition": "normal", "path": row["path"],
                    "split": split, "seed": seed()})
    for row in ds.real(split, "positive"):
        out.append({"kind": "real", "group": f"real/{row['condition']}", "path": row["path"], "split": split,
                    "word": [row["word_start_s"], row["word_end_s"]], "seed": seed()})
    for row in ds.clips(split, "negative"):
        for condition in ("normal", "quiet", "household", "question"):
            out.append({"kind": "negative", "group": "similar_and_phrases", "text": row["text"], "condition": condition,
                        "path": row["path"], "split": split, "seed": seed()})
    for row in ds.real(split, "negative"):
        out.append({"kind": "real_negative", "group": "real_similar", "path": row["path"], "split": split, "seed": seed()})
    for items in ds.conversations(split, rng):
        out.append({"kind": "conversation", "group": "conversation", "items": items, "split": split, "seed": seed()})
    for row in ds.streams(split):
        out.append({"kind": "stream", "group": "conversation", "path": row["path"], "split": split, "seed": 0})
    return out


def collect(split: str, workers: int) -> list[tuple]:
    with Pool(workers, initializer=_init) as pool:
        return list(pool.imap_unordered(features_job, cases(split), chunksize=2))


def score(model, data: list[tuple], thresholds: np.ndarray) -> dict:
    """Per group: hits per threshold for positives; events and hours for negatives."""
    hits = defaultdict(lambda: np.zeros(len(thresholds)))
    count = defaultdict(int)
    false = defaultdict(lambda: np.zeros(len(thresholds)))
    hours = defaultdict(float)
    for case, window, seconds, ends, x, awake in data:
        if not len(ends):
            continue
        s = np.where(awake, score_vector(model, x), 0.0)
        group = case["group"]
        for i, t in enumerate(thresholds):
            found = events(ends, s, t)
            if window is None:
                false[group][i] += len(found)
            else:
                hits[group][i] += any(window[0] <= e <= window[1] for e in found)
        if window is None:
            hours[group] += (seconds - ds.WARMUP_S) / 3600
        else:
            count[group] += 1
    return {"thresholds": thresholds, "hits": dict(hits), "count": dict(count), "false": dict(false), "hours": dict(hours)}


def summary(result: dict, index: int) -> dict:
    groups = {g: {"hits": int(h[index]), "of": result["count"][g], "recall": round(h[index] / result["count"][g], 4)}
              for g, h in sorted(result["hits"].items())}
    tts = [g for g in groups if g.startswith("tts/")]
    real = [g for g in groups if g.startswith("real/")]
    total = lambda names: {"hits": sum(groups[g]["hits"] for g in names), "of": sum(groups[g]["of"] for g in names)}
    false = {g: {"activations": int(f[index]), "hours": round(result["hours"][g], 3),
                 "per_hour": round(f[index] / max(result["hours"][g], 1e-9), 2)} for g, f in sorted(result["false"].items())}
    return {"threshold": float(result["thresholds"][index]), "tts": total(tts), "real": total(real), "groups": groups,
            "false": false}


def per_hour(result: dict, group: str = "conversation") -> np.ndarray:
    return result["false"].get(group, np.zeros(len(result["thresholds"]))) / max(result["hours"].get(group, 0), 1e-9)


def recall(result: dict, prefix: str) -> np.ndarray:
    names = [g for g in result["hits"] if g.startswith(prefix)]
    return sum(result["hits"][g] for g in names) / max(sum(result["count"][g] for g in names), 1)


THRESHOLDS = np.round(np.concatenate((np.arange(.05, .9, .05), np.arange(.9, .99, .01), [.99, .995, .998, .999, .9995])), 4)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("calibrate", "test"))
    parser.add_argument("--head", type=Path, default=Path("models/wakeword/dina_wakeword.npz"))
    parser.add_argument("--compare", type=Path, nargs="*", default=[], help="Other ONNX heads, same cases and policy")
    parser.add_argument("--workers", type=int, default=14)
    args = parser.parse_args()
    split = "calibration" if args.mode == "calibrate" else "test"
    data = collect(split, args.workers)
    head = dict(np.load(args.head)) if args.head.suffix == ".npz" else OrtHead(args.head)
    result = score(head, data, THRESHOLDS)
    REPORTS.mkdir(parents=True, exist_ok=True)
    if args.mode == "calibrate":
        fa = per_hour(result)
        levels = {}
        for name, target in LEVELS.items():
            allowed = [i for i in range(len(THRESHOLDS)) if fa[i] <= target]
            index = allowed[0] if allowed else len(THRESHOLDS) - 1
            levels[name] = {"threshold": float(THRESHOLDS[index]), "target_per_hour": target, **summary(result, index)}
        sweep = [{"threshold": float(t), "tts_recall": round(float(recall(result, "tts/")[i]), 4),
                  "sentence_recall": round(float(recall(result, "sentence")[i]), 4),
                  "conversation_per_hour": round(float(fa[i]), 2)} for i, t in enumerate(THRESHOLDS)]
        report = {"split": split, "levels": levels, "sweep": sweep, "conversation_hours": round(result["hours"].get("conversation", 0), 2)}
        (REPORTS / "calibration.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
        print(json.dumps({k: {"threshold": v["threshold"], "tts": v["tts"], "false": v["false"]} for k, v in levels.items()},
                         ensure_ascii=False))
        return
    calibration = json.loads((REPORTS / "calibration.json").read_text(encoding="utf-8"))
    report = {"split": split, "heads": {}}
    heads = {"dina_wakeword": (head, {k: v["threshold"] for k, v in calibration["levels"].items()})}
    for path in args.compare:
        heads[path.stem] = (OrtHead(path), {"0.999": .999, "0.5": .5})
    for name, (model, chosen) in heads.items():
        thresholds = np.array(sorted(set(chosen.values())))
        result = score(model, data, thresholds)
        report["heads"][name] = {level: summary(result, int(np.flatnonzero(thresholds == t)[0])) for level, t in chosen.items()}
    (REPORTS / "evaluation.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    for name, levels in report["heads"].items():
        for level, s in levels.items():
            print(name, level, s["threshold"], "tts", s["tts"], "real", s["real"],
                  {g: v["per_hour"] for g, v in s["false"].items()}, flush=True)


if __name__ == "__main__":
    main()
