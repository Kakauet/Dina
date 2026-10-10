"""Speed, memory and fidelity of one Supertonic 3 build on the PC (run one build per process).

  python scripts/tts/bench_supertonic.py onnx models/tts/supertonic3-fp32 --steps 4 --save eval-results/tts/ref-s4
  python scripts/tts/bench_supertonic.py onnx models/tts/supertonic3 --steps 4 --ref eval-results/tts/ref-s4

Prints one JSON line: RAM after loading (RSS, MB) and its peak, stage times (median ms per
sentence), real-time factor, and with --ref the log-mel distance and waveform error to the
reference renders (same text, same noise). --save writes the renders as .npy + .wav.
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import supertonic_pipeline as sp  # noqa: E402


def peak_rss_mb():
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith("VmHWM:"):
                return int(line.split()[1]) / 1024
    return float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["onnx"])
    ap.add_argument("model_dir")
    ap.add_argument("--assets", default=None, help="directory with onnx/tts.json and voice_styles (default: model_dir)")
    ap.add_argument("--steps", type=int, default=4)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--ref", default=None, help="reference renders (npy); several separated by commas")
    ap.add_argument("--wav-only", action="store_true", help="--save writes only the WAV files")
    ap.add_argument("--save", default=None)
    ap.add_argument("--label", default=None)
    ap.add_argument("--seed", type=int, default=20261007, help="another seed against --ref = the yardstick of mel_dist")
    ap.add_argument("--guided-steps", type=int, default=None,
                    help="split estimator (supertonic_cfg.py): guidance only in the first K steps (default: all)")
    ap.add_argument("--after", default="plain", choices=sp.GUIDE_AFTER, help="what the steps after the guided ones do")
    ap.add_argument("--sentences", type=int, default=None, help="only the first N sentences")
    ap.add_argument("--java-noise", action="store_true", help="the noise of the app's SupertonicVoice (java.util.Random), to compare with its output")
    ap.add_argument("--session-config", default="", help="onnx: ONNX Runtime session config entries key=value,key=value")
    args = ap.parse_args()

    assets = sp.Assets(args.assets or args.model_dir)
    base = sp.rss_mb()
    base_anon = sp.rss_mb("RssAnon")
    backend = sp.OnnxBackend(args.model_dir, args.threads, dict(kv.split("=") for kv in args.session_config.split(",") if kv))
    loaded = sp.rss_mb() - base
    loaded_anon = sp.rss_mb("RssAnon") - base_anon
    sp.synthesize(backend, assets, "Hola.", args.steps, guided_steps=args.guided_steps, after=args.after)  # warm-up, as the app does
    sentences = sp.SENTENCES[:args.sentences]

    stages = {k: [] for k in ["duration", "encoder", "estimator", "vocoder"]}
    step_ms = {"guided": [], "plain": []}
    totals, audio_seconds, renders = [], 0.0, []
    for rep in range(args.reps):
        for i, text in enumerate(sentences):
            wav, st = sp.synthesize(backend, assets, text, args.steps, seed=args.seed, guided_steps=args.guided_steps, after=args.after, noise_source="java" if args.java_noise else "numpy")
            if rep == 0:
                renders.append(wav)
                audio_seconds += len(wav) / assets.sample_rate
            for k in stages:
                stages[k].append(st[k])
            for k, v in st["step_ms"].items():
                step_ms[k] += v
            totals.append(sum(st[k] for k in stages))
    result = {
        "label": args.label or f"{args.kind}:{os.path.basename(os.path.normpath(args.model_dir))}",
        "steps": args.steps, "threads": args.threads, "guided_steps": args.guided_steps, "after": args.after if args.guided_steps is not None else None,
        "ram_loaded_mb": round(loaded), "ram_loaded_anon_mb": round(loaded_anon), "ram_peak_mb": round(peak_rss_mb() - base),
        "stage_ms": {k: round(float(np.median(v)), 1) for k, v in stages.items()},
        "sentence_ms": round(float(np.median(totals)), 1),
        "rtf": round(float(np.sum(totals)) / args.reps / 1e3 / audio_seconds, 3),
    }
    if step_ms["guided"] or step_ms["plain"]:
        result["step_ms"] = {k: round(float(np.median(v)), 1) for k, v in step_ms.items() if v}
    for number, ref_dir in enumerate(filter(None, (args.ref or "").split(","))):
        mel, err, length = [], [], []
        for i, wav in enumerate(renders):
            ref = np.load(os.path.join(ref_dir, f"{i:02d}.npy"))
            n = min(len(ref), len(wav))
            mel.append(sp.mel_distance(ref, wav))
            err.append(float(np.sqrt(np.mean((ref[:n] - wav[:n]) ** 2)) / (np.sqrt(np.mean(ref[:n] ** 2)) + 1e-9)))
            length.append(abs(len(ref) - len(wav)) / assets.sample_rate * 1e3)
        name = os.path.basename(os.path.normpath(ref_dir))
        key = "" if number == 0 else "_" + name
        result.update({"mel_dist" + key: round(float(np.mean(mel)), 3), "mel_dist_max" + key: round(float(np.max(mel)), 3),
                       "rel_wave_err" + key: round(float(np.mean(err)), 4), "length_diff_ms_max" + key: round(float(np.max(length)), 1)})
        if number == 0:
            result["ref"] = name
        result.setdefault("mel_by_sentence", {})[name] = [round(float(v), 2) for v in mel]
    if args.save:
        os.makedirs(args.save, exist_ok=True)
        with open(os.path.join(args.save, "sentences.json"), "w", encoding="utf-8") as f:
            json.dump(sentences, f, ensure_ascii=False)
        for i, wav in enumerate(renders):
            if not args.wav_only:
                np.save(os.path.join(args.save, f"{i:02d}.npy"), wav)
            sp.write_wav(os.path.join(args.save, f"{i:02d}.wav"), wav, assets.sample_rate)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
