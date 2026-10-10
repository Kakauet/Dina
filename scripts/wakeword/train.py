"""Train the «Dina» head on the app's exact streaming features, then mine hard negatives and retrain.

Only `train` material is used (speakers, texts and recording sessions of calibration and test stay out).
Every scene is streamed through the app's front-end; each 80 ms step becomes one example:
- positive: the word has just ended (up to 0.8 s later, 0.3 s when a request follows);
- negative: before the word, similar words, phrases, conversations (the app's requests and real readers, every
  utterance once, half over real room noise) and the owner's room audio;
- ignored: steps where only part of the word is visible.
Two rounds of mining add the negative steps the current head scores highest on fresh conversations.

    python scripts/wakeword/train.py            -> models/wakeword/dina_wakeword.npz + reports/training.json
"""
from __future__ import annotations

import argparse
import json
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np

import dataset as ds
from wakeword_core import CHUNK, SAMPLE_RATE, FrontEnd, score_vector

SEED = 2207
_front: FrontEnd | None = None


def _init() -> None:
    global _front
    _front = FrontEnd()


def positive_job(job: dict):
    rng = np.random.default_rng(job["seed"])
    audio = ds.wav(job["path"])
    start, end = ds.word_bounds(job["row"], audio)
    margin = int(.1 * SAMPLE_RATE)
    word = audio[max(0, start - margin):end + margin]
    scene, ws, we, follow = ds.scene(word, rng, "train", condition=job["condition"])
    ends, x = _front.features(scene)
    # The piece carries 100 ms of margin on each side of the word.
    positive = (ends >= we - margin + int(.04 * SAMPLE_RATE)) & (ends <= we + int((.3 if follow else .8) * SAMPLE_RATE))
    before = ends <= ws + margin + int(.04 * SAMPLE_RATE)
    before[1::2] = False
    keep = positive | before
    return x[keep].astype(np.float16), positive[keep].astype(np.int8)


def negative_job(job: dict):
    rng = np.random.default_rng(job["seed"])
    audio = ds.wav(job["path"])
    if job.get("stream"):
        scene = audio
    else:
        scene = ds.contextual(audio, rng, job["condition"], "train")
    ends, x = _front.features(scene)
    x = x[::job.get("stride", 2)]
    return x.astype(np.float16), np.zeros(len(x), dtype=np.int8)


def conversation_job(job: dict):
    rng = np.random.default_rng(job["seed"])
    pool = ds.talk("train")
    if job.get("items") is not None:
        scene = ds.stitched([pool[i] for i in job["items"]], rng, split="train")
    else:
        scene = ds.stitched(list(pool), rng, job["minutes"], "train")
    ends, x = _front.features(scene)
    if job.get("mine") is not None:
        scores = score_vector(job["mine"], x)
        x = x[scores >= job["min_score"]]
    else:
        x = x[::job.get("stride", 2)]
    return x.astype(np.float16), np.zeros(len(x), dtype=np.int8)


def run(pool: Pool, function, jobs: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    xs, ys = [], []
    for x, y in pool.imap_unordered(function, jobs, chunksize=4):
        xs.append(x)
        ys.append(y)
    return np.concatenate(xs), np.concatenate(ys)


def base_jobs(rng: np.random.Generator, repeats_real: int) -> tuple[list[dict], list[dict]]:
    positives, negatives = [], []
    for row in ds.real("train", "positive"):
        for _ in range(repeats_real):
            for condition in ds.CONDITIONS:
                positives.append({"path": row["path"], "row": _plain(row), "condition": condition, "seed": int(rng.integers(2**31))})
    for row in ds.clips("train", "positive"):
        for condition in ds.CONDITIONS:
            positives.append({"path": row["path"], "row": _plain(row), "condition": condition, "seed": int(rng.integers(2**31))})
    for row in ds.clips("train", "negative"):
        for condition in rng.choice(ds.CONDITIONS, 3, replace=False):
            negatives.append({"path": row["path"], "condition": str(condition), "seed": int(rng.integers(2**31))})
    for row in ds.real("train", "negative"):
        for condition in ds.CONDITIONS:
            negatives.append({"path": row["path"], "condition": condition, "seed": int(rng.integers(2**31))})
    for row in ds.real("train", "negative", continuous=True):
        negatives.append({"path": row["path"], "stream": True, "stride": 1, "seed": 0})
        for condition in ("quiet", "household", "distance_3"):
            negatives.append({"path": row["path"], "condition": condition, "seed": int(rng.integers(2**31)), "stride": 1})
    for row in ds.streams("train"):
        negatives.append({"path": row["path"], "stream": True, "stride": 2, "seed": 0})
    return positives, negatives


def _plain(row: dict) -> dict:
    return {k: v for k, v in row.items() if k in ("word_start_s", "word_end_s", "path")}


def fit(x: np.ndarray, y: np.ndarray, weights: np.ndarray, hidden: int, epochs: int, seed: int) -> dict:
    import torch
    torch.manual_seed(seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    mean = x.astype(np.float32).mean(axis=0)
    scale = x.astype(np.float32).std(axis=0) + 1e-5
    xt = torch.tensor((x.astype(np.float32) - mean) / scale, device=device)
    yt = torch.tensor(y, dtype=torch.float32, device=device)
    wt = torch.tensor(weights, dtype=torch.float32, device=device)
    model = torch.nn.Sequential(torch.nn.Dropout(.1), torch.nn.Linear(x.shape[1], hidden), torch.nn.ReLU(),
                                torch.nn.Dropout(.2), torch.nn.Linear(hidden, 1)).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-3)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, epochs)
    generator = torch.Generator(device=device).manual_seed(seed)
    for _ in range(epochs):
        model.train()
        order = torch.randperm(len(yt), device=device, generator=generator)
        for i in range(0, len(order), 512):
            batch = order[i:i + 512]
            logits = model(xt[batch]).squeeze(1)
            loss = (torch.nn.functional.binary_cross_entropy_with_logits(logits, yt[batch], reduction="none")
                    * wt[batch]).mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
        schedule.step()
    first, last = model[1], model[4]
    return {"mean": mean, "scale": scale.astype(np.float32),
            "w1": first.weight.detach().cpu().numpy().T.copy(), "b1": first.bias.detach().cpu().numpy(),
            "w2": last.weight.detach().cpu().numpy().T.copy(), "b2": last.bias.detach().cpu().numpy()}


def balanced(y: np.ndarray, hard: np.ndarray) -> np.ndarray:
    """Positives and negatives weigh the same in total; mined negatives count double."""
    weights = np.where(y == 1, .5 / max(int((y == 1).sum()), 1), .5 / max(int((y == 0).sum()), 1))
    weights = np.where(hard, weights * 2, weights)
    return weights / weights.mean()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("models/wakeword/dina_wakeword.npz"))
    parser.add_argument("--report", type=Path, default=Path("scripts/wakeword/reports/training.json"))
    parser.add_argument("--hidden", type=int, default=128)
    parser.add_argument("--epochs", type=int, default=12)
    parser.add_argument("--real-repeats", type=int, default=3)
    parser.add_argument("--mining-rounds", type=int, default=2)
    parser.add_argument("--mining-minutes", type=float, default=60)
    parser.add_argument("--workers", type=int, default=14)
    args = parser.parse_args()
    started = time.time()
    rng = np.random.default_rng(SEED)
    positives, negatives = base_jobs(rng, args.real_repeats)
    conversations = [{"items": items, "seed": int(rng.integers(2**31)), "stride": 3}
                     for items in ds.conversations("train", rng)]
    with Pool(args.workers, initializer=_init) as pool:
        xp, yp = run(pool, positive_job, positives)
        xn, yn = run(pool, negative_job, negatives)
        xc, yc = run(pool, conversation_job, conversations)
        x = np.concatenate((xp, xn, xc))
        y = np.concatenate((yp, yn, yc))
        hard = np.zeros(len(y), dtype=bool)
        print(f"base: {int(y.sum())} positive / {int((y == 0).sum())} negative steps", flush=True)
        model = fit(x, y, balanced(y, hard), args.hidden, args.epochs, SEED)
        mined = []
        for round_ in range(args.mining_rounds):
            jobs = [{"minutes": 4.0, "seed": int(rng.integers(2**31)), "mine": model, "min_score": .05}
                    for _ in range(int(args.mining_minutes / 4))]
            xm, ym = run(pool, conversation_job, jobs)
            mined.append(len(ym))
            print(f"mining round {round_ + 1}: {len(ym)} hard negative steps", flush=True)
            x = np.concatenate((x, xm))
            y = np.concatenate((y, ym))
            hard = np.concatenate((hard, np.ones(len(ym), dtype=bool)))
            model = fit(x, y, balanced(y, hard), args.hidden, args.epochs, SEED + round_ + 1)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    np.savez(args.output, **model)
    report = {"architecture": f"openWakeWord front-end (frozen) + MLP 1536 -> {args.hidden} -> 1",
              "features": "app streaming: one mel call per 80 ms chunk, 16 embeddings",
              "seed": SEED, "epochs": args.epochs, "real_repeats": args.real_repeats,
              "positive_steps": int((y == 1).sum()), "negative_steps": int((y == 0).sum()),
              "mined_per_round": mined, "positive_scenes": len(positives), "negative_scenes": len(negatives),
              "real_train_positives": len(ds.real("train", "positive")),
              "synthetic_train_positives": len(ds.clips("train", "positive")),
              "train_sentences": len(ds.sentences("train")), "train_talk_hours": round(sum(r["seconds"] for r in ds.talk("train")) / 3600, 2),
              "minutes": round((time.time() - started) / 60, 1),
              "license": "CC BY-NC-SA 4.0 (trained on openWakeWord features)"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
