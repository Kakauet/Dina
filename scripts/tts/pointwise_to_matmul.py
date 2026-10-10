"""Rewrites Supertonic's 1x1 Conv1d layers as MatMul (same math; ONNX Runtime runs it faster on CPU).

Conv(X[B,I,L], W[O,I,1], b[O]) == MatMul(W[O,I], X) + b[O,1]. Applied only when the kernel is 1,
group 1, stride 1, dilation 1 and no padding, with the weight as an initializer. Works on the fp32
graphs and on the 8-bit ones (W then comes from DequantizeLinear and keeps its int8 storage).

usage: python scripts/tts/pointwise_to_matmul.py <src model dir> <dst model dir>
"""
import os
import shutil
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper

GRAPHS = ["duration_predictor", "text_encoder", "vector_estimator", "vocoder"]


def attr(node, name, default):
    for a in node.attribute:
        if a.name == name:
            return helper.get_attribute_value(a)
    return default


def rewrite(model):
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    dq = {n.output[0]: n for n in g.node if n.op_type == "DequantizeLinear" and n.input[0] in inits}
    new_nodes, changed = [], 0
    for node in g.node:
        w_name = node.input[1] if node.op_type == "Conv" and len(node.input) > 1 else None
        ok = w_name is not None and attr(node, "group", 1) == 1 \
            and all(v == 1 for v in attr(node, "strides", [1])) and all(v == 1 for v in attr(node, "dilations", [1])) \
            and all(v == 0 for v in attr(node, "pads", [0, 0])) and attr(node, "auto_pad", b"NOTSET") in (b"NOTSET", "NOTSET")
        if ok and w_name in inits:
            w = numpy_helper.to_array(inits[w_name])
            ok = w.ndim == 3 and w.shape[2] == 1
            if ok:
                inits[w_name].CopyFrom(numpy_helper.from_array(w[:, :, 0], w_name))
        elif ok and w_name in dq:
            q = inits[dq[w_name].input[0]]
            w = numpy_helper.to_array(q)
            ok = w.ndim == 3 and w.shape[2] == 1
            if ok:
                q.CopyFrom(numpy_helper.from_array(w[:, :, 0], q.name))
        else:
            ok = False
        if not ok:
            new_nodes.append(node)
            continue
        out = node.output[0]
        mm = out + "_mm" if len(node.input) > 2 and node.input[2] else out
        new_nodes.append(helper.make_node("MatMul", [w_name, node.input[0]], [mm], name=node.name + "_mm"))
        if mm != out:
            b_name = node.input[2]
            b = numpy_helper.to_array(inits[b_name])
            inits[b_name].CopyFrom(numpy_helper.from_array(b.reshape(-1, 1), b_name))
            new_nodes.append(helper.make_node("Add", [mm, b_name], [out], name=node.name + "_bias"))
        changed += 1
    del g.node[:]
    g.node.extend(new_nodes)
    return changed


def main(src, dst):
    if os.path.abspath(src) != os.path.abspath(dst):
        shutil.copytree(src, dst, dirs_exist_ok=True)
    for name in GRAPHS:
        model = onnx.load(os.path.join(src, "onnx", name + ".onnx"))
        changed = rewrite(model)
        onnx.checker.check_model(model)
        onnx.save(model, os.path.join(dst, "onnx", name + ".onnx"))
        print(name, "pointwise convs ->", changed)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
