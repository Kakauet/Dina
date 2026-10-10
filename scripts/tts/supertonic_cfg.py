"""Supertonic 3's classifier-free guidance (CFG) taken out of the vector estimator.

The original estimator builds a batch of 2 inside the graph (conditioned + unconditioned) and
combines the two halves as  v = 4*cond - 3*uncond.  Here the graph keeps only the *body*: it takes
a batch of N rows already built and returns the raw velocity of every row. The caller (the app's
SupertonicVoice, the PC benchmark) builds the rows and combines them:

  guided step     N = 2   rows [cond, uncond]      v = W*cond - (W-1)*uncond      (the original, W = 4)
  cond-only step  N = 1   row  [cond]              v = cond                       (half the work)
  then            x = (x + v / total_step) * latent_mask

One session and one set of weights serve both kinds of step, so cheap guidance costs no RAM.
Nothing in the body changes: the Tile / Concat / Slice wrapper is removed and its constants are
saved to `guidance.bin`, which the caller needs to build the unconditioned row.

body inputs : noisy_latent [N,144,L]  text_emb [N,256,T]  style_ttl [N,50,256] (values)  style_key [N,50,256]
              latent_mask [N,1,L]  text_mask [N,1,T]  current_step [N]  total_step [N]
body output : velocity [N,144,L]

guidance.bin: float32 little endian, in this order
  text_token   [256]        the unconditioned text embedding (the same vector at every position)
  key_cond     [50*256]     style keys of the conditioned row (a constant of the model)
  key_uncond   [50*256]     style keys of the unconditioned row
  value_uncond [50*256]     style values of the unconditioned row (the conditioned row uses the voice's style_ttl)

usage: python scripts/tts/supertonic_cfg.py split <src model dir> <dst model dir>
       python scripts/tts/supertonic_cfg.py verify <original model dir> <split model dir>
"""
import os
import shutil
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

GUIDANCE_SCALE = 4.0  # the original's  /Mul_1 (x4.0) and /Mul_2 (x3.0): v = W*cond - (W-1)*uncond
TEXT_CHANNELS, STYLE_TOKENS, STYLE_CHANNELS = 256, 50, 256

TOKENS = {
    "text_token": "vector_estimator.tts.ttl.uncond_masker.text_special_token",
    "key_uncond": "vector_estimator.tts.ttl.uncond_masker.style_key_special_token",
    "value_uncond": "vector_estimator.tts.ttl.uncond_masker.style_value_special_token",
}


def _tile_by_two(model):
    """Names of the Tile nodes that double the batch (repeats [2,1,1])."""
    inits = {i.name: numpy_helper.to_array(i) for i in model.graph.initializer}
    return [n for n in model.graph.node if n.op_type == "Tile" and n.input[1] in inits and inits[n.input[1]].tolist() == [2, 1, 1]]


def _prune(g, keep_outputs):
    """Removes the nodes and initializers nothing needs any more."""
    needed = set(keep_outputs)
    kept = []
    for node in reversed(list(g.node)):
        if any(o in needed for o in node.output):
            needed.update(i for i in node.input if i)
            kept.append(node)
    kept.reverse()
    del g.node[:]
    g.node.extend(kept)
    inits = [i for i in g.initializer if i.name in needed]
    del g.initializer[:]
    g.initializer.extend(inits)


def split(model):
    """The original estimator -> (body model, guidance vector)."""
    g = model.graph
    inits = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    by_out = {o: n for n in g.node for o in n.output}
    tiles = _tile_by_two(model)
    if len(tiles) != 4:
        raise ValueError(f"expected 4 Tile [2,1,1], found {len(tiles)}")
    alias = {t.output[0]: t.input[0] for t in tiles}  # Tile(x) -> x
    # The three Concats that put the unconditioned row after the conditioned one.
    concats = {n.name.rsplit("/", 1)[1]: n for n in g.node if n.op_type == "Concat" and n.input and n.input[0] in ("text_emb", "style_ttl")}
    text_cat, value_cat = concats["Concat_5"], concats["Concat_7"]
    key_cat = next(n for n in g.node if n.name.endswith("/Concat_6"))
    key_cond_tile = by_out[key_cat.input[0]]
    assert key_cond_tile.op_type == "Tile" and key_cond_tile.input[0] in inits
    alias[text_cat.output[0]] = "text_emb"
    alias[value_cat.output[0]] = "style_ttl"
    alias[key_cat.output[0]] = "style_key"

    guidance = np.concatenate([
        inits[TOKENS["text_token"]].reshape(-1),
        inits[key_cond_tile.input[0]].reshape(-1),
        inits[TOKENS["key_uncond"]].reshape(-1),
        inits[TOKENS["value_uncond"]].reshape(-1),
    ]).astype("<f4")

    velocity = "/vector_estimator/vector_field/proj_out/Mul_output_0"
    drop = {id(t) for t in tiles} | {id(text_cat), id(value_cat), id(key_cat)}
    nodes = []
    for node in g.node:
        if id(node) in drop:
            continue
        for index, name in enumerate(node.input):
            if name in alias:
                node.input[index] = alias[name]
        nodes.append(node)
    del g.node[:]
    g.node.extend(nodes)

    # New interface: the same inputs plus style_key (N rows), and the velocity as output.
    batch = "batch_size"
    del g.output[:]
    g.output.append(helper.make_tensor_value_info("velocity", TensorProto.FLOAT, [batch, 144, "latent_length"]))
    g.node.append(helper.make_node("Identity", [velocity], ["velocity"]))
    g.input.append(helper.make_tensor_value_info("style_key", TensorProto.FLOAT, [batch, STYLE_TOKENS, STYLE_CHANNELS]))
    _prune(g, ["velocity"])
    return model, guidance


def unpack(guidance):
    """guidance.bin (as a flat float32 array) -> dict of arrays."""
    style = STYLE_TOKENS * STYLE_CHANNELS
    sizes = [("text_token", TEXT_CHANNELS), ("key_cond", style), ("key_uncond", style), ("value_uncond", style)]
    out, offset = {}, 0
    for name, size in sizes:
        out[name] = guidance[offset:offset + size].astype(np.float32)
        offset += size
    assert offset == len(guidance), f"guidance.bin: {len(guidance)} floats, expected {offset}"
    for name in ("key_cond", "key_uncond", "value_uncond"):
        out[name] = out[name].reshape(1, STYLE_TOKENS, STYLE_CHANNELS)
    return out


def rows(guidance, text_emb, style_ttl, guided):
    """The N rows a step feeds the body: (text_emb, style_value, style_key) for N = 2 (guided) or 1."""
    t = unpack(guidance)
    if not guided:
        return text_emb, style_ttl, t["key_cond"]
    uncond_text = np.broadcast_to(t["text_token"].reshape(1, TEXT_CHANNELS, 1), text_emb.shape)
    return (np.concatenate([text_emb, uncond_text]).astype(np.float32),
            np.concatenate([style_ttl, t["value_uncond"]]).astype(np.float32),
            np.concatenate([t["key_cond"], t["key_uncond"]]).astype(np.float32))


def combine(velocity, guided, scale=GUIDANCE_SCALE):
    """The velocity of one step from the body's output rows."""
    if not guided:
        return velocity[:1]
    return scale * velocity[:1] - (scale - 1.0) * velocity[1:2]


def step(session, guidance, latent, text_emb, style_ttl, text_mask, latent_mask, current, total, guided, scale=GUIDANCE_SCALE):
    """One denoising step with the body graph: the original's  denoised_latent  for guided=True."""
    n = 2 if guided else 1
    emb, value, key = rows(guidance, text_emb, style_ttl, guided)
    rep = lambda a: np.repeat(a, n, axis=0) if a.shape[0] == 1 else a
    velocity = session.run(["velocity"], {
        "noisy_latent": rep(latent), "text_emb": emb, "style_ttl": value, "style_key": key,
        "latent_mask": rep(latent_mask), "text_mask": rep(text_mask),
        "current_step": np.repeat(current, n), "total_step": np.repeat(total, n),
    })[0]
    return (latent + combine(velocity, guided, scale) / total.reshape(-1, 1, 1)) * latent_mask


def split_dir(src, dst):
    if os.path.abspath(src) != os.path.abspath(dst):
        shutil.copytree(src, dst, dirs_exist_ok=True)
    path = os.path.join(src, "onnx", "vector_estimator.onnx")
    body, guidance = split(onnx.load(path))
    onnx.checker.check_model(body)
    out = os.path.join(dst, "onnx", "vector_estimator.onnx")
    if os.path.exists(out):
        os.chmod(out, 0o644)
        os.remove(out)
    onnx.save(body, out)
    guidance.tofile(os.path.join(dst, "onnx", "guidance.bin"))
    print("vector_estimator body:", os.path.getsize(out), "bytes; guidance.bin:", guidance.size, "floats")


def verify(original_dir, split_dir_):
    """The body + caller-side rows must reproduce the original (guided) and its cond half (scale 1)."""
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    original = ort.InferenceSession(os.path.join(original_dir, "onnx", "vector_estimator.onnx"), opts, providers=["CPUExecutionProvider"])
    # The original with scales (1, 0): v = 1*cond - 0*uncond, i.e. its conditioned half.
    model = onnx.load(os.path.join(original_dir, "onnx", "vector_estimator.onnx"))
    patched = 0
    for init in model.graph.initializer:
        if init.name in ("/Constant_3_output_0", "/Constant_4_output_0"):
            init.CopyFrom(numpy_helper.from_array(np.array(1.0 if init.name.startswith("/Constant_3") else 0.0, dtype=np.float32), init.name))
            patched += 1
    assert patched == 2, "the guidance scale constants were not found"
    cond_only_original = ort.InferenceSession(model.SerializeToString(), opts, providers=["CPUExecutionProvider"])
    body = ort.InferenceSession(os.path.join(split_dir_, "onnx", "vector_estimator.onnx"), opts, providers=["CPUExecutionProvider"])
    guidance = np.fromfile(os.path.join(split_dir_, "onnx", "guidance.bin"), dtype="<f4")
    rng = np.random.default_rng(7)
    worst = {"guided": 0.0, "cond": 0.0}
    for length, text_len, current in [(40, 30, 0), (97, 61, 2), (160, 120, 3)]:
        latent = rng.standard_normal((1, 144, length)).astype(np.float32)
        text_emb = rng.standard_normal((1, 256, text_len)).astype(np.float32)
        style = rng.standard_normal((1, 50, 256)).astype(np.float32)
        text_mask = np.ones((1, 1, text_len), np.float32)
        latent_mask = np.zeros((1, 1, length), np.float32)
        latent_mask[:, :, :length - 5] = 1
        feeds = {"noisy_latent": latent, "text_emb": text_emb, "style_ttl": style, "text_mask": text_mask, "latent_mask": latent_mask,
                 "current_step": np.array([current], np.float32), "total_step": np.array([4], np.float32)}
        total = feeds["total_step"]
        for guided, reference in ((True, original), (False, cond_only_original)):
            want = reference.run(None, feeds)[0]
            got = step(body, guidance, latent, text_emb, style, text_mask, latent_mask, feeds["current_step"], total, guided)
            err = float(np.abs(want - got).max())
            worst["guided" if guided else "cond"] = max(worst["guided" if guided else "cond"], err)
            print(f"L={length} T={text_len} step={current} {'guided' if guided else 'cond-only'}: max |diff| = {err:.2e} (signal {np.abs(want).max():.2f})")
    return worst


if __name__ == "__main__":
    if sys.argv[1] == "split":
        split_dir(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "verify":
        w = verify(sys.argv[2], sys.argv[3])
        sys.exit(0 if max(w.values()) < 1e-4 else 1)
