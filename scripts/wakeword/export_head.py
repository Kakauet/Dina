"""Export Dina's frozen NumPy MLP head as one ONNX graph for Android."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    weights = np.load(args.source)
    initializers = [
        numpy_helper.from_array(weights[name].astype(np.float32), name=name)
        for name in ("mean", "scale", "w1", "b1", "w2", "b2")
    ]
    nodes = [
        helper.make_node("Sub", ["features", "mean"], ["centered"]),
        helper.make_node("Div", ["centered", "scale"], ["normalized"]),
        helper.make_node("MatMul", ["normalized", "w1"], ["hidden_linear"]),
        helper.make_node("Add", ["hidden_linear", "b1"], ["hidden_bias"]),
        helper.make_node("Relu", ["hidden_bias"], ["hidden"]),
        helper.make_node("MatMul", ["hidden", "w2"], ["logit_linear"]),
        helper.make_node("Add", ["logit_linear", "b2"], ["logit"]),
        helper.make_node("Sigmoid", ["logit"], ["confidence"]),
    ]
    graph = helper.make_graph(
        nodes,
        "dina_wakeword_head",
        [helper.make_tensor_value_info("features", TensorProto.FLOAT, [1, 1536])],
        [helper.make_tensor_value_info("confidence", TensorProto.FLOAT, [1, 1])],
        initializer=initializers,
    )
    model = helper.make_model(
        graph,
        producer_name="Dina",
        opset_imports=[helper.make_opsetid("", 17)],
        ir_version=8,  # Compatible with the app's ONNX Runtime; do not inherit the exporter default.
    )
    helper.set_model_props(model, {"license": "CC BY-NC-SA 4.0", "frontend": "openWakeWord frozen mel + embedding"})
    onnx.checker.check_model(model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, args.output)
    # Verify the real ONNX graph, including normalization, instead of trusting only its schema.
    import onnxruntime as ort
    from wakeword_core import score_vector
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.output), sess_options=options, providers=['CPUExecutionProvider'])
    rng = np.random.default_rng(7719)
    vectors = [np.zeros(1536,dtype=np.float32), weights['mean']]
    vectors += [weights['mean'] + weights['scale'] * rng.normal(size=1536).astype(np.float32) for _ in range(32)]
    errors = [abs(float(session.run(None, {'features': vector.reshape(1,1536)})[0][0,0]) - float(np.ravel(score_vector(weights,vector))[0])) for vector in vectors]
    if max(errors) > 1e-5:
        raise ValueError(f'ONNX parity failed: {max(errors)}')
    print(args.output)
    print(f'ONNX parity max error: {max(errors):.8g}; bytes: {args.output.stat().st_size}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
