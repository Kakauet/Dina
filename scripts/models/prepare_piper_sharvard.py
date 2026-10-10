"""Prepare the validated Sharvard female speaker for Moonshine's Android Piper runtime."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    model = onnx.load(args.model)
    if not any(value.name == "sid" for value in model.graph.input):
        raise SystemExit("Sharvard ya no expone sid; no se puede fijar la voz femenina de forma verificable")

    # Moonshine's generic Piper runner passes sid=0. Keep its expected input but
    # shift it to speaker 1 inside the graph, which is Sharvard's validated F voice.
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name == "sid":
                node.input[index] = "dina_female_sid"
    model.graph.initializer.append(numpy_helper.from_array(np.array([1], dtype=np.int64), name="dina_sid_offset"))
    model.graph.node.insert(0, helper.make_node("Add", ["sid", "dina_sid_offset"], ["dina_female_sid"], name="DinaFemaleSpeaker"))
    onnx.checker.check_model(model)

    stem = "es_ES-sharvard-medium"
    output = args.output_dir / f"{stem}.onnx"
    onnx.save(model, output)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    config["dina_fixed_speaker_id"] = 1
    (args.output_dir / f"{stem}.onnx.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

