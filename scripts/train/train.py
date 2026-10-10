"""Dina SFT on the RTX 4060 (docs/entrenamiento.md). Runs in WSL, conda env `ml`.

    .\\dev.ps1 train data=data/batches/piloto1 base=1p2b            # LoRA r=32, 2 epochs, then GGUF + quantizations
    .\\dev.ps1 train data=data/batches/piloto1 base=350m full=1     # (direct call) full fine-tune of the 350M
    .\\dev.ps1 train base=350m smoke=1                          # 5 steps on a few examples: checks the whole chain

Data: <data>/train.jsonl and dev.jsonl from the pipeline ({"prompt", "completion"}). The prompt is the
app's own render (Dina45Prompt, byte for byte); the loss is only on the completion plus <|im_end|>.
The checkpoint is chosen by the loss on the development set (other generator family and personas),
never with RW2 or Dina-Real. Output: <out>/model-f16.gguf (dev.ps1 quantizes it) and train-report.json.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASES = {"1p2b": "LiquidAI/LFM2.5-1.2B-Instruct", "350m": "LiquidAI/LFM2.5-350M"}
END = "<|im_end|>"


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] if path.exists() else []


def training_rejection(row: dict) -> str | None:
    """Retire obsolete targets without altering canonical data or the development set."""
    completion = row["completion"]
    if re.search(r"(?m)^\s*no\(\s*zonas\s*\)\s*$", completion):
        return "no_zonas"
    utterance = row["prompt"].rsplit("[ahora]\n", 1)[-1].split(END, 1)[0]
    utterance = "".join(c for c in unicodedata.normalize("NFD", utterance.lower()) if unicodedata.category(c) != "Mn")
    topics = {
        "body": (r"\bcuerpo\b", r"\b(cuerpo|fisic\w*|corporal\w*|brazos?|piernas?|humano\w*|persona|robot)\b"),
        "internet": (r"\binternet\b", r"\b(internet|red|web|wifi|online|conexion|conect\w*|naveg\w*)\b"),
    }
    for quoted in re.findall(r'(?m)^\s*say\(("(?:\\.|[^"\\])*")\)\s*$', completion):
        reply = json.loads(quoted).lower()
        for topic, (mention, request) in topics.items():
            if re.search(mention, reply) and not re.search(request, utterance):
                return f"unsolicited_{topic}"
    return None


def parse_weights(value: str | None, count: int) -> list[float]:
    """One non-negative sampling weight per input batch, in --data order."""
    weights = [float(w) for w in re.split(r"[+,]", value)] if value else [1.0] * count
    if len(weights) != count or any(not math.isfinite(w) or w < 0 for w in weights):
        raise ValueError("--weights necesita un peso finito >= 0 por lote, en el orden de --data")
    return weights


def prepare_data(dirs: list[Path], history: int = 1, weights: str | None = None, dedup: bool = False) -> tuple[list[dict], list[dict], dict]:
    """Filter, optionally deduplicate, then weight training rows; keep dev untouched."""
    values = parse_weights(weights, len(dirs))
    suffix = "" if history == 1 else f"-h{history}"
    dev_rows = [r for d in dirs for r in read_jsonl(d / f"dev{suffix}.jsonl")]
    dev_prompts = {r["prompt"] for r in dev_rows} if dedup else set()
    seen = set()
    train_rows = []
    batches = []
    rng = random.Random(1)
    for directory, weight in zip(dirs, values):
        source = directory / f"train{suffix}.jsonl"
        if not source.is_file():
            raise ValueError(f"Falta el lote renderizado: {source}")
        rows = read_jsonl(source)
        kept = []
        rejected = {}
        duplicates = overlap = 0
        for row in rows:
            reason = training_rejection(row)
            if reason:
                rejected[reason] = rejected.get(reason, 0) + 1
                continue
            if dedup and row["prompt"] in dev_prompts:
                overlap += 1
                continue
            key = (row["prompt"], row["completion"])
            if dedup and key in seen:
                duplicates += 1
                continue
            # A zero-weight batch must not suppress duplicates in a later active batch.
            if weight > 0:
                seen.add(key)
            kept.append(row)
        whole = math.floor(weight)
        extra = math.floor(len(kept) * (weight - whole) + 0.5)
        weighted = kept * whole + rng.sample(kept, extra)
        train_rows.extend(weighted)
        batches.append({"batch": directory.name, "input": len(rows), "filtered": rejected,
                        "duplicates": duplicates, "dev_overlap": overlap, "kept": len(kept),
                        "weight": weight, "sampled": len(weighted)})
    rng.shuffle(train_rows)
    return train_rows, dev_rows, {"dedup": dedup, "batches": batches, "examples": len(train_rows), "dev_examples": len(dev_rows)}


def smoke_examples() -> list[dict]:
    """A few hand-made examples in the app's format, when no batch exists yet (only to test the chain)."""
    head = "<|startoftext|><|im_start|>system\nEres Dina, asistente de voz local.<|im_end|>\n<|im_start|>user\n[estado]\nhora: lun 5 oct, 10:00\n[ahora]\n"
    pairs = [("qué hora es", "time.now(hora)"), ("pon una alarma a las siete de la mañana", "alarm.add(7:00 mañana)"),
             ("apunta leche", "list.add(\"leche\")"), ("sube el volumen", "vol.up()"), ("hola", "say(\"¡Hola! ¿En qué te ayudo?\")")]
    return [{"prompt": f"{head}{u}<|im_end|>\n<|im_start|>assistant\n", "completion": c} for u, c in pairs] * 4


def encode(tokenizer, rows: list[dict], max_len: int = 512) -> list[dict]:
    out = []
    for row in rows:
        prompt = tokenizer(row["prompt"], add_special_tokens=False)["input_ids"]
        completion = tokenizer(row["completion"] + END, add_special_tokens=False)["input_ids"]
        ids = (prompt + completion)[:max_len]
        out.append({"input_ids": ids, "labels": ([-100] * len(prompt) + completion)[:max_len]})
    return out


class Collator:
    def __init__(self, pad_id: int):
        self.pad_id = pad_id

    def __call__(self, batch):
        import torch
        width = max(len(b["input_ids"]) for b in batch)
        ids = torch.full((len(batch), width), self.pad_id, dtype=torch.long)
        labels = torch.full((len(batch), width), -100, dtype=torch.long)
        mask = torch.zeros((len(batch), width), dtype=torch.long)
        for i, b in enumerate(batch):
            n = len(b["input_ids"])
            ids[i, :n] = torch.tensor(b["input_ids"])
            labels[i, :n] = torch.tensor(b["labels"])
            mask[i, :n] = 1
        return {"input_ids": ids, "labels": labels, "attention_mask": mask}


def exact_match(model, tokenizer, rows: list[dict], limit: int) -> float | None:
    """Greedy, no grammar: share of dev examples whose output is exactly the canonical actions."""
    import torch
    rows = rows[:limit]
    if not rows:
        return None
    model.eval()
    end_id = tokenizer.convert_tokens_to_ids(END)
    hits = 0
    with torch.no_grad():
        for row in rows:
            enc = tokenizer(row["prompt"], add_special_tokens=False, return_tensors="pt").to(model.device)
            out = model.generate(**enc, max_new_tokens=120, do_sample=False, eos_token_id=end_id, pad_token_id=tokenizer.pad_token_id or end_id)
            text = tokenizer.decode(out[0, enc["input_ids"].shape[1]:], skip_special_tokens=True).strip()
            hits += text == row["completion"].strip()
    return hits / len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", choices=sorted(BASES), default="1p2b")
    parser.add_argument("--data", help="data/batches/<batch>[+data/batches/<other>] with train.jsonl and dev.jsonl")
    parser.add_argument("--history", type=int, default=1, help="0 = the render without [antes] (train-h0.jsonl)")
    parser.add_argument("--out", default=None)
    parser.add_argument("--epochs", type=float, default=2)
    parser.add_argument("--lr", type=float, default=None)
    parser.add_argument("--rank", type=int, default=32)
    parser.add_argument("--weights", help="sampling weights in --data order, e.g. 1+1+1+2+2 (default: all 1)")
    parser.add_argument("--dedup", action="store_true", help="deduplicate exact prompt/completion pairs and exclude training prompts also in dev, before weighting")
    parser.add_argument("--prepare-only", action="store_true", help="print data preparation counts without loading a model or writing files")
    parser.add_argument("--batch-size", type=int, default=None, help="default 4 for 1.2B (8 GB), 8 for 350M; effective batch 16")
    parser.add_argument("--grad-accum", type=int, default=None)
    parser.add_argument("--full", action="store_true", help="full fine-tune instead of LoRA (350M fits in 8 GB)")
    parser.add_argument("--limit", type=int, default=0, help="first N training examples (learning curve)")
    parser.add_argument("--dev-exact", type=int, default=300, help="dev examples for the exact-match check (0 = skip)")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--keep-hf", action="store_true")
    args = parser.parse_args()
    if not math.isfinite(args.epochs) or args.epochs <= 0 or args.rank <= 0 or (args.lr is not None and (not math.isfinite(args.lr) or args.lr <= 0)):
        parser.error("--epochs, --rank y --lr deben ser positivos y finitos")

    args.batch_size = args.batch_size or (4 if args.base == "1p2b" else 8)
    args.grad_accum = args.grad_accum or max(1, 16 // args.batch_size)

    out = ROOT / (args.out or f"models/train-{args.base}{'-smoke' if args.smoke else ''}")
    dirs = [ROOT / d for d in re.split(r"[+,]", args.data)] if args.data else []
    suffix = "" if args.history == 1 else f"-h{args.history}"
    try:
        train_rows, dev_rows, preparation = prepare_data(dirs, args.history, args.weights, args.dedup)
    except ValueError as error:
        parser.error(str(error))
    if args.smoke:
        train_rows = (train_rows or smoke_examples())[:32]
        dev_rows = dev_rows[:8]
    if args.limit:
        train_rows = train_rows[: args.limit]
    if not train_rows:
        sys.exit(f"No hay ejemplos en {args.data} (train{suffix}.jsonl)")
    preparation["used_examples"] = len(train_rows)
    preparation["used_dev_examples"] = len(dev_rows)
    print(json.dumps({"data_preparation": preparation}, ensure_ascii=False))
    if args.prepare_only:
        return

    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

    out.mkdir(parents=True, exist_ok=True)

    started = time.time()
    tokenizer = AutoTokenizer.from_pretrained(BASES[args.base])
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = END
    model = AutoModelForCausalLM.from_pretrained(BASES[args.base], dtype=torch.bfloat16).to("cuda")
    if not args.full:
        model = get_peft_model(model, LoraConfig(task_type="CAUSAL_LM", r=args.rank, lora_alpha=2 * args.rank, lora_dropout=0.05, target_modules="all-linear"))
    train = encode(tokenizer, train_rows)
    dev = encode(tokenizer, dev_rows)
    tokens = sum(len(x["input_ids"]) for x in train)
    lr = args.lr if args.lr is not None else (5e-5 if args.full else 2e-4)
    steps_per_epoch = math.ceil(len(train) / (args.batch_size * args.grad_accum))
    training = TrainingArguments(
        output_dir=str(out / "checkpoints"), per_device_train_batch_size=args.batch_size, per_device_eval_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum, learning_rate=lr, num_train_epochs=args.epochs, max_steps=5 if args.smoke else -1,
        lr_scheduler_type="cosine", warmup_ratio=0.05, bf16=True, logging_steps=max(1, steps_per_epoch // 10),
        eval_strategy="epoch" if dev and not args.smoke else "no", save_strategy="epoch" if not args.smoke else "no",
        save_total_limit=3, load_best_model_at_end=bool(dev) and not args.smoke, metric_for_best_model="eval_loss",
        report_to="none", seed=1, dataloader_num_workers=0,
    )
    trainer = Trainer(model=model, args=training, train_dataset=train, eval_dataset=dev or None, data_collator=Collator(tokenizer.pad_token_id))
    result = trainer.train()
    peak = torch.cuda.max_memory_reserved() / 2**30
    evals = [log for log in trainer.state.log_history if "eval_loss" in log]
    exact = exact_match(trainer.model, tokenizer, dev_rows, 8 if args.smoke else args.dev_exact) if args.dev_exact else None

    merged = trainer.model.merge_and_unload() if not args.full else trainer.model
    hf_dir = out / "hf"
    merged.save_pretrained(hf_dir, safe_serialization=True)
    tokenizer.save_pretrained(hf_dir)
    llama = ROOT / "android" / "third_party" / "llama.cpp"
    env = dict(os.environ, PYTHONPATH=str(llama / "gguf-py"))
    subprocess.run([sys.executable, str(llama / "convert_hf_to_gguf.py"), str(hf_dir), "--outfile", str(out / "model-f16.gguf"), "--outtype", "f16"],
                   check=True, env=env, stdout=subprocess.DEVNULL)
    if not args.keep_hf:
        shutil.rmtree(hf_dir)
    shutil.rmtree(out / "checkpoints", ignore_errors=True)

    report = {
        "base": BASES[args.base], "method": "full" if args.full else f"lora r={args.rank}", "data": args.data, "history": args.history, "examples": len(train),
        "dev_examples": len(dev), "epochs": args.epochs, "learning_rate": lr, "steps": trainer.state.global_step,
        "rank": None if args.full else args.rank, "data_preparation": preparation,
        "train_loss": round(result.training_loss, 4), "eval_loss": [round(e["eval_loss"], 4) for e in evals],
        "dev_exact_match": exact, "tokens_per_second": round(tokens * (args.epochs if not args.smoke else 1) / max(result.metrics["train_runtime"], 1e-6)),
        "peak_vram_gib": round(peak, 2), "minutes": round((time.time() - started) / 60, 1), "smoke": args.smoke,
    }
    (out / "train-report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
