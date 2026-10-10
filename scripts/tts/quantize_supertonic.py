"""Supertonic 3 for the APK: the fp32 originals -> graphs the app runs, ~100 MB instead of 398 MB.

Four steps per graph, always on a copy (the fp32 originals in models/tts/supertonic3-fp32 are read-only and
verified against Hugging Face Supertone/supertonic-3 @ 724fb5ab):

  1. estimator only: the classifier-free guidance is taken out of the graph (supertonic_cfg.py). The graph takes a
     batch of rows and returns the velocity; the app builds the conditioned + unconditioned rows for the first
     steps and only the conditioned one for the rest. The wrapper's constants go to onnx/guidance.bin.
  2. the 1x1 convolutions (92 % of the estimator's weights, most of the vocoder's) become MatMul
     (pointwise_to_matmul.py): identical audio, ~25 % faster on a guided step.
  3. the weights are stored in 8 bits (supertonic_weights.py), one of
       dq8      int8, one scale per output channel, DequantizeLinear in front of every use. ONNX Runtime 1.23 does NOT
                fold it: it dequantizes at every step, so it is ~25-30 % slower than fp32, in exchange for 3x less
                RAM (150 MB loaded instead of 440).
       nbits8   MatMulNBits (int8 weights, int8 compute): the fastest, but ONNX Runtime packs the weights again
                (+40-60 MB of RAM).
  4. onnx/guidance.bin and a copy of the voices, tts.json and the indexer.

usage (WSL, conda env `tts`; .\\dev.ps1 tts-models does this):
  python scripts/tts/quantize_supertonic.py models/tts/supertonic3-fp32 models/tts/supertonic3 [--format nbits8]
"""
import argparse
import os
import shutil
import sys

import numpy as np
import onnx

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pointwise_to_matmul  # noqa: E402
import supertonic_cfg  # noqa: E402
import supertonic_weights  # noqa: E402

GRAPHS = ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]
FORMATS = ["dq8", "nbits8"]
DEFAULT_FORMAT = "dq8"


def prepare(src_dir, dst_dir, fmt=DEFAULT_FORMAT, block=32):
    if os.path.abspath(src_dir) == os.path.abspath(dst_dir):
        raise SystemExit("the destination must be another folder: the fp32 originals are never modified")
    if os.path.exists(os.path.join(dst_dir, "onnx")):
        raise SystemExit(f"{dst_dir} already has graphs: move it away first (nothing is overwritten or deleted)")
    shutil.copytree(src_dir, dst_dir, ignore=shutil.ignore_patterns("*.onnx"))
    for name in GRAPHS:
        model = onnx.load(os.path.join(src_dir, "onnx", name + ".onnx"))
        report = {}
        if name == "vector_estimator":
            model, guidance = supertonic_cfg.split(model)
            guidance.tofile(os.path.join(dst_dir, "onnx", "guidance.bin"))
            report["guidance"] = guidance.size
        if fmt != "dq8conv":
            report["pointwise"] = pointwise_to_matmul.rewrite(model)
        if fmt == "nbits8":
            report["nbits"] = supertonic_weights.matmul_nbits(model, 8, block)
        report["dq"] = supertonic_weights.quantize_dq8(model)
        out = os.path.join(dst_dir, "onnx", name + ".onnx")
        onnx.save(model, out)
        print(name, report, os.path.getsize(out), "bytes")
    for path in sorted(os.listdir(os.path.join(dst_dir, "onnx"))):
        print("  onnx/" + path, os.path.getsize(os.path.join(dst_dir, "onnx", path)))
    return dst_dir


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--format", choices=FORMATS, default=DEFAULT_FORMAT)
    ap.add_argument("--block", type=int, default=32, help="nbits8: MatMulNBits block size")
    args = ap.parse_args()
    prepare(args.src, args.dst, args.format, args.block)
