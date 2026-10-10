"""All locally installed Spanish Piper speakers and Supertonic styles; never downloads."""
from __future__ import annotations
import argparse
import io
import json
import math
import wave
from pathlib import Path

import numpy as np
from scipy import signal
from wakeword_core import load_wav


def has_speech(path: Path) -> bool:
    return path.is_file() and bool(np.any(load_wav(path)))


def merge_cached_rows(existing, generated, root: Path):
    """Keep cached voices after their temporary synthesis models have been removed."""
    rows = {row['file']: row for row in existing
            if not row.get('continuous') and has_speech(root / row['file'])}
    for row in generated:
        previous = rows.get(row['file'])
        if previous and any(previous.get(key) != row.get(key)
                            for key in ('label', 'text', 'speaker_id', 'split')):
            raise ValueError(f"Cached clip labels changed: {row['file']}; use a new corpus directory")
        rows[row['file']] = row
    return list(rows.values())


def write_wave(path: Path, audio: np.ndarray, rate: int) -> None:
    if rate != 16000:
        divisor = math.gcd(rate, 16000)
        audio = signal.resample_poly(audio.astype(np.float64), 16000 // divisor, rate // divisor)
    with wave.open(str(path), "wb") as wav:
        wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        wav.writeframes(np.clip(audio, -32768, 32767).astype("<i2").tobytes())


def speaker_split(identity: str) -> str:
    # Frozen identities, assigned before synthesis/augmentation; ald qualities share one identity.
    if any(name in identity for name in ("davefx", "mls_9972", "supertonic:M4", "supertonic:F4")):
        return "test"
    if any(name in identity for name in ("daniela", "carlfm", "supertonic:M5", "supertonic:F5")):
        return "calibration"
    return "train"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("data/wakeword/corpus"))
    parser.add_argument("--piper", type=Path, default=Path("models/voice/piper"))
    parser.add_argument("--supertonic", type=Path, default=Path("models/tts/supertonic3"))
    parser.add_argument("--limit", type=int, help="Smoke test: number of phrases per label")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest_path = args.output / 'manifest.json'
    existing = json.loads(manifest_path.read_text(encoding='utf-8-sig')) if manifest_path.exists() else []
    phrases = json.loads(Path(__file__).with_name("phrases.json").read_text(encoding="utf-8"))
    if args.limit:
        phrases = {k: v[:args.limit] for k, v in phrases.items()}
    rows = []
    windows = args.output / "windows.json"
    if windows.exists():
        rows.extend(json.loads(windows.read_text(encoding="utf-8-sig")))
        for row in rows:
            if not has_speech(args.output / row['file']):
                raise ValueError(f"Empty Windows speech: {row['file']}; regenerate before training")
    from piper import PiperVoice, SynthesisConfig
    piper_voices = sorted(args.piper.glob("es_*.onnx"))
    for model in piper_voices:
        config = json.loads(Path(str(model) + ".json").read_text(encoding="utf-8"))
        voice = PiperVoice.load(model)
        family = model.stem.rsplit("-", 1)[0]
        for speaker in range(config.get("num_speakers", 1)):
            identity = f"piper:{family}:{speaker}"
            for speed in (.8, 1., 1.2):
                index = 0
                for label, texts in phrases.items():
                    for text in texts:
                        name = f"piper_{model.stem}_{speaker}_{speed}_{index}.wav"
                        if not has_speech(args.output / name):
                            buffer = io.BytesIO()
                            with wave.open(buffer, "wb") as wav:
                                voice.synthesize_wav(text, wav, syn_config=SynthesisConfig(speaker_id=speaker, length_scale=1/speed))
                            buffer.seek(0)
                            with wave.open(buffer, "rb") as wav:
                                audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
                                write_wave(args.output / name, audio, wav.getframerate())
                        rows.append(dict(file=name, label=label, text=text, voice=identity, speaker_id=identity,
                                         split=speaker_split(identity), engine="piper", style="tts", rate=speed,
                                         source="synthetic", license_card=f"{model.stem}.MODEL_CARD"))
                        index += 1
                        if index % 20 == 0: print(f"Piper {identity} rate={speed} {index} clips", flush=True)
            print(f"Piper {identity}", flush=True)
    from supertonic import TTS
    tts = TTS(model_dir=args.supertonic, auto_download=False, intra_op_num_threads=4, inter_op_num_threads=1)
    for style_name in tts.voice_style_names:
        style = tts.get_voice_style(style_name)
        identity = f"supertonic:{style_name}"
        for speed in (.8, 1., 1.2):
            index = 0
            for label, texts in phrases.items():
                for text in texts:
                    name = f"supertonic_{style_name}_{speed}_{index}.wav"
                    if not has_speech(args.output / name):
                        for attempt in range(3):
                            audio, _ = tts.synthesize(text, voice_style=style, lang="es", speed=speed, total_steps=8)
                            write_wave(args.output / name, np.asarray(audio).reshape(-1) * 32767, tts.sample_rate)
                            if has_speech(args.output / name):
                                break
                        else:
                            raise ValueError(f"Empty Supertonic speech after retries: {name}")
                    rows.append(dict(file=name, label=label, text=text, voice=identity, speaker_id=identity,
                                     split=speaker_split(identity), engine="supertonic", style=style_name, rate=speed,
                                     source="synthetic", license_card="models/tts/supertonic3/LICENSE"))
                    index += 1
                    if index % 20 == 0: print(f"Supertonic {style_name} speed={speed} {index} clips", flush=True)
        print(f"Supertonic {identity}", flush=True)
    rows = merge_cached_rows(existing, rows, args.output)
    manifest_path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"clips": len(rows), "speakers": sorted({r['speaker_id'] for r in rows}),
                      "supertonic_styles": tts.voice_style_names, "piper_models": [v.name for v in piper_voices]}))


if __name__ == "__main__":
    main()
