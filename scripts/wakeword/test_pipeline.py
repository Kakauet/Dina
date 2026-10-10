"""Invariants of the detector tools. Fixtures are generated here, never benchmark audio.

    python -m unittest discover -s scripts/wakeword     (from the repository root; parity needs models/voice/openwakeword)
"""
import json
import tempfile
import unittest
import wave
from unittest import mock
from pathlib import Path

import numpy as np

import dataset as ds
import wakeword_core as core
from real_corpus import load_sessions
from synthesize import merge_cached_rows, speaker_split
from synthesize_speech import mentions_dina, text_split


class DecisionTest(unittest.TestCase):
    def test_two_consecutive_steps_then_a_pause(self):
        ends = np.arange(1, 60) * core.CHUNK
        scores = np.full(len(ends), .99)
        found = core.events(ends, scores, .95)
        # Same as WakeDecisionTest: after an activation, 25 steps (2 s) pass before two new hits.
        self.assertEqual(found[:2], [2 * core.CHUNK, 28 * core.CHUNK])

    def test_a_single_step_or_nan_never_activates(self):
        ends = np.arange(1, 7) * core.CHUNK
        self.assertEqual(core.events(ends, np.array([.99, .1, .99, np.nan, .99, .2]), .95), [])


class GateTest(unittest.TestCase):
    def test_silence_sleeps_and_a_whisper_wakes(self):
        rng = np.random.default_rng(1)
        quiet = rng.normal(0, 15, 60 * core.CHUNK)
        whisper = rng.normal(0, 120, core.CHUNK)
        awake = core.gate_awake(np.concatenate((quiet, whisper, quiet[:30 * core.CHUNK])).astype(np.int16))
        self.assertFalse(awake[59])
        self.assertTrue(awake[60])
        self.assertTrue(awake[60 + core.GATE_HOLD_CHUNKS - 1])
        self.assertFalse(awake[60 + core.GATE_HOLD_CHUNKS])

    def test_noise_keeps_it_running(self):
        noise = np.random.default_rng(2).normal(0, 200, 400 * core.CHUNK).astype(np.int16)
        self.assertTrue(core.gate_awake(noise)[1:].all())


@unittest.skipUnless((core.FRONT_END / "melspectrogram.onnx").exists(), "openWakeWord models not installed")
class StreamingParityTest(unittest.TestCase):
    """FrontEnd.features (whole recording) == WakeWordDetector.kt's chunk-by-chunk stream."""

    def test_features_equal_the_app_stream(self):
        front = core.FrontEnd()
        rng = np.random.default_rng(3)
        audio = (np.sin(np.arange(40 * core.CHUNK) / 9) * 3000 + rng.normal(0, 200, 40 * core.CHUNK)).astype(np.int16)
        ends, features = front.features(audio)
        raw = np.zeros(1760, np.float32)
        mel = np.ones((76, 32), np.float32)
        embeddings = np.zeros((16, 96), np.float32)
        for k in range(40):
            raw[:-core.CHUNK] = raw[core.CHUNK:]
            raw[-core.CHUNK:] = audio[k * core.CHUNK:(k + 1) * core.CHUNK]
            frames = front.mel.run(None, {"input": raw[None]})[0].reshape(-1, 32)
            mel = np.concatenate((mel[8:], frames[-8:] / 10 + 2))
            embedding = front.embedding.run(None, {"input_1": mel[None, :, :, None]})[0].reshape(-1)
            embeddings = np.concatenate((embeddings[1:], embedding[None]))
            if (k + 1) * core.CHUNK in ends:
                index = list(ends).index((k + 1) * core.CHUNK)
                np.testing.assert_array_equal(features[index], embeddings.reshape(-1))
        self.assertEqual(ends[0], core.WARMUP_CHUNKS * core.CHUNK)


class CorpusTest(unittest.TestCase):
    def test_voice_qualities_share_speaker_split(self):
        self.assertEqual(speaker_split('piper:es_MX-ald:0'), 'train')
        self.assertEqual(speaker_split('supertonic:M4'), 'test')
        self.assertEqual(speaker_split('piper:es_AR-daniela:0'), 'calibration')

    def test_sentences_with_dina_are_found_and_split_by_text(self):
        self.assertTrue(mentions_dina("Hola, Dina, ¿qué hora es?"))
        self.assertFalse(mentions_dina("Esta tarta está divina"))
        self.assertEqual(text_split("Pon una alarma"), text_split("PON UNA ALARMA"))

    def test_conversations_use_every_utterance_once(self):
        rows = tuple({"seconds": 30.0} for _ in range(25))
        with mock.patch.object(ds, "talk", lambda split: rows):
            groups = ds.conversations("test", np.random.default_rng(0), minutes=4)
        self.assertEqual(sorted(i for g in groups for i in g), list(range(25)))
        self.assertEqual([len(g) for g in groups], [8, 8, 8, 1])

    def write_session(self, root, name, split, clip_split=None):
        folder = root / name
        folder.mkdir()
        (folder / 'session.json').write_text(json.dumps(dict(session_id=name, split=split, consent=True)))
        with wave.open(str(folder / 'clip.wav'), 'wb') as wav:
            wav.setparams((1, 2, 16000, 0, 'NONE', ''))
            wav.writeframes(np.arange(320, dtype='<i2').tobytes())
        row = dict(session_id=name, split=clip_split or split, label='positive', condition='whisper', file='clip.wav', samples=320)
        (folder / 'manifest.jsonl').write_text(json.dumps(row) + '\n')

    def test_cached_voice_survives_synthesis_model_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_session(root, 'voice', 'train')
            row = dict(file='voice/clip.wav', speaker_id='piper:removed', split='train', label='positive', text='Dina')
            self.assertEqual(merge_cached_rows([row], [], root), [row])
            with self.assertRaisesRegex(ValueError, 'labels changed'):
                merge_cached_rows([row], [{**row, 'split': 'test'}], root)

    def test_clip_cannot_change_its_session_split(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_session(Path(tmp), 'one', 'test', 'train')
            with self.assertRaisesRegex(ValueError, 'changed'):
                load_sessions(Path(tmp))

    def test_duplicate_audio_cannot_leak_between_sessions(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_session(Path(tmp), 'one', 'train')
            self.write_session(Path(tmp), 'two', 'test')
            with self.assertRaisesRegex(ValueError, 'Identical'):
                load_sessions(Path(tmp))

    def test_word_boundary_must_stay_inside_recording(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_session(root, 'one', 'train')
            (root / 'one' / 'annotations.json').write_text(json.dumps({'clip.wav': {'word_start_s': 0, 'word_end_s': 1}}))
            with self.assertRaisesRegex(ValueError, 'interval'):
                load_sessions(root)


if __name__ == '__main__':
    unittest.main()
