"""Weight formats for Supertonic 3's ONNX graphs, to compare speed, RAM and quality on the PC.

All start from the original fp32 graphs (after supertonic_cfg.split for the estimator) and keep the
graph interface. The 1x1 convolutions (92 % of the estimator's weights, most of the vocoder's) are
the layers that matter; `pointwise_to_matmul.rewrite` turns them into MatMul(W[O,I], X[B,I,L]).

  fp32        the original weights
  fp32pw      the original weights, the 1x1 convolutions as MatMul (identical audio)
  fp32pw      the original weights, the 1x1 convolutions as MatMul (identical audio)
  dq8         int8 per-output-channel + DequantizeLinear in front of every use (the app 2.3 format; ONNX Runtime
              does not fold it: it dequantizes at every step, ~30 % slower than fp32 but 3x less RAM)
  nbits8      MatMulNBits(bits=8, block_size=B, accuracy_level=4) on the 1x1 layers: the weights stay int8 and the
              product is computed in int8 (the activations are quantized on the fly); the other weights as dq8
  dq8conv     dq8 without the 1x1 -> MatMul rewrite (the app 2.3 graphs)
  dq8fold     dq8 whose DequantizeLinear ONNX Runtime is allowed to fold at load (fp32 speed, fp32 RAM)

usage: python scripts/tts/supertonic_weights.py <fp32 or split dir> <dst dir> <format> [--block 32]
"""
import argparse
import os
import shutil
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pointwise_to_matmul  # noqa: E402

GRAPHS = ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]
WEIGHT_INPUT = {"Conv": 1, "MatMul": (0, 1), "Gemm": 1, "Gather": 0}
MS = "com.microsoft"


def _is_weight_use(op, index):
    allowed = WEIGHT_INPUT.get(op)
    return allowed == index or (isinstance(allowed, tuple) and index in allowed)


def quantize_dq8(model, min_size=4096):
    """int8 per-output-channel weights + DequantizeLinear (quantize_supertonic.py's format), for Conv, Gemm, Gather
    and MatMul whose weight is the first or the second operand."""
    consumers = {}
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            consumers.setdefault(name, []).append((node.op_type, index))
    initializers, nodes = [], []
    for init in list(model.graph.initializer):
        w = numpy_helper.to_array(init)
        uses = consumers.get(init.name, [])
        if w.dtype != np.float32 or w.size < min_size or not uses:
            continue
        if not all(_is_weight_use(op, index) for op, index in uses):
            continue
        op, index = uses[0]
        axis = 1 if (op == "MatMul" and index == 1) else 0  # output channels
        reduce = tuple(a for a in range(w.ndim) if a != axis)
        scale = (np.abs(w).max(axis=reduce) / 127.0).astype(np.float32)
        scale[scale == 0] = 1e-8
        shape = [1] * w.ndim
        shape[axis] = -1
        q = np.clip(np.round(w / scale.reshape(shape)), -127, 127).astype(np.int8)
        model.graph.initializer.remove(init)
        initializers += [numpy_helper.from_array(q, init.name + "_q"), numpy_helper.from_array(scale, init.name + "_s")]
        nodes.append(helper.make_node("DequantizeLinear", [init.name + "_q", init.name + "_s"], [init.name], axis=axis))
    model.graph.initializer.extend(initializers)
    for node in reversed(nodes):
        model.graph.node.insert(0, node)
    return len(nodes)


def matmul_nbits(model, bits=8, block=32, accuracy=4, min_size=4096):
    """MatMul(W[O,I] constant, X[B,I,L]) -> Transpose, MatMulNBits (N=O, K=I), Transpose. Returns how many."""
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    out, changed = [], 0
    for node in g.node:
        w = numpy_helper.to_array(inits[node.input[0]]) if node.op_type == "MatMul" and node.input[0] in inits else None
        if w is None or w.ndim != 2 or w.size < min_size or w.dtype != np.float32:
            out.append(node)
            continue
        n_out, k = w.shape
        blocks = -(-k // block)
        padded = np.zeros((n_out, blocks * block), np.float32)
        padded[:, :k] = w
        grouped = padded.reshape(n_out, blocks, block)
        half = 2 ** (bits - 1)
        scale = np.abs(grouped).max(axis=2) / (half - 1)
        scale[scale == 0] = 1e-8
        q = np.clip(np.round(grouped / scale[:, :, None]), -(half - 1), half - 1) + half  # unsigned, zero point = half
        name = node.name or node.output[0]
        b, s = f"{name}/nbits_b", f"{name}/nbits_s"
        g.initializer.append(numpy_helper.from_array(q.astype(np.uint8), b))
        g.initializer.append(numpy_helper.from_array(scale.reshape(-1).astype(np.float32), s))
        xt, yt = f"{name}/nbits_xt", f"{name}/nbits_yt"
        out.append(helper.make_node("Transpose", [node.input[1]], [xt], perm=[0, 2, 1]))
        out.append(helper.make_node("MatMulNBits", [xt, b, s], [yt], domain=MS, K=k, N=n_out, bits=bits, block_size=block, accuracy_level=accuracy))
        out.append(helper.make_node("Transpose", [yt], [node.output[0]], perm=[0, 2, 1]))
        changed += 1
    del g.node[:]
    g.node.extend(out)
    used = {i for n in g.node for i in n.input}
    keep = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(keep)
    if changed and not any(o.domain == MS for o in model.opset_import):
        model.opset_import.append(helper.make_opsetid(MS, 1))
    return changed


def convert(src, dst, fmt, block=32, nbits_graphs=None, accuracy=4):
    if os.path.abspath(src) != os.path.abspath(dst):
        shutil.copytree(src, dst, dirs_exist_ok=True)
    for name in GRAPHS:
        path = os.path.join(src, "onnx", name + ".onnx")
        model = onnx.load(path)
        report = {}
        if fmt not in ("fp32", "dq8conv"):
            report["pointwise"] = pointwise_to_matmul.rewrite(model)
        if fmt == "nbits8" and (nbits_graphs is None or name in nbits_graphs):
            report["nbits"] = matmul_nbits(model, 8, block, accuracy)
        if fmt in ("dq8", "dq8conv", "dq8fold", "nbits8"):
            report["dq"] = quantize_dq8(model)
        out = os.path.join(dst, "onnx", name + ".onnx")
        if os.path.exists(out):
            os.chmod(out, 0o644)
            os.remove(out)
        onnx.save(model, out)
        print(name, report, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("format", choices=["fp32", "fp32pw", "dq8", "dq8conv", "dq8fold", "nbits8"])
    ap.add_argument("--block", type=int, default=32, help="nbits8: MatMulNBits block size")
    ap.add_argument("--accuracy", type=int, default=4, help="MatMulNBits accuracy_level (4 = int8 compute, 1 = fp32 compute)")
    ap.add_argument("--nbits-graphs", default=None, help="comma separated graphs that get MatMulNBits (default all); the others stay dq8")
    args = ap.parse_args()
    convert(args.src, args.dst, args.format, args.block, set(args.nbits_graphs.split(",")) if args.nbits_graphs else None, args.accuracy)
