"""Tests for the pure parts of scripts/tts (no models needed):

  python -m unittest discover -s scripts/tts
"""
import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import supertonic_cfg as cfg  # noqa: E402
import supertonic_pipeline as sp  # noqa: E402


class JavaRandomTest(unittest.TestCase):
    def test_matches_the_values_of_java_util_random(self):
        # new java.util.Random(42).nextGaussian() and new java.util.Random(0).nextGaussian()
        self.assertAlmostEqual(sp.JavaRandom(42).gaussian(), 1.1419053154730547, places=12)
        self.assertAlmostEqual(sp.JavaRandom(0).gaussian(), 0.8025330637390305, places=12)

    def test_the_polar_method_gives_two_values_per_round(self):
        a = sp.JavaRandom(7)
        first, second = a.gaussian(), a.gaussian()
        b = sp.JavaRandom(7)
        self.assertEqual((first, second), (b.gaussian(), b.gaussian()))
        self.assertNotEqual(first, second)

    def test_noise_is_drawn_row_major_and_the_padding_zeroed(self):
        noise = sp.java_noise(20261007, channels=3, frames=5, masked=2)
        self.assertEqual(noise.shape, (1, 3, 5))
        self.assertTrue((noise[:, :, 2:] == 0).all())
        flat = [sp.JavaRandom(20261007)]
        draws = [flat[0].gaussian() for _ in range(15)]
        self.assertAlmostEqual(float(noise[0, 1, 0]), draws[5], places=6)


class GuidanceTest(unittest.TestCase):
    def setUp(self):
        style = cfg.STYLE_TOKENS * cfg.STYLE_CHANNELS
        self.bin = np.concatenate([
            np.arange(cfg.TEXT_CHANNELS, dtype="<f4"),
            np.full(style, 1, "<f4"), np.full(style, 2, "<f4"), np.full(style, 3, "<f4"),
        ])

    def test_unpack_follows_the_documented_order(self):
        t = cfg.unpack(self.bin)
        self.assertEqual(t["text_token"][5], 5)
        self.assertEqual(float(t["key_cond"][0, 0, 0]), 1)
        self.assertEqual(float(t["key_uncond"][0, 0, 0]), 2)
        self.assertEqual(float(t["value_uncond"][0, 0, 0]), 3)

    def test_rows_put_the_conditioned_row_first(self):
        text = np.full((1, cfg.TEXT_CHANNELS, 4), -1, np.float32)
        style = np.full((1, cfg.STYLE_TOKENS, cfg.STYLE_CHANNELS), 9, np.float32)
        emb, value, key = cfg.rows(self.bin, text, style, guided=True)
        self.assertEqual(emb.shape, (2, cfg.TEXT_CHANNELS, 4))
        self.assertTrue((emb[0] == -1).all())
        self.assertTrue((emb[1, 7] == 7).all())  # channel 7's token at every position
        self.assertEqual(float(value[0, 0, 0]), 9)
        self.assertEqual(float(value[1, 0, 0]), 3)
        self.assertEqual((float(key[0, 0, 0]), float(key[1, 0, 0])), (1, 2))
        single = cfg.rows(self.bin, text, style, guided=False)
        self.assertIs(single[0], text)
        self.assertEqual(single[2].shape[0], 1)

    def test_combine_is_four_cond_minus_three_uncond(self):
        v = np.stack([np.full((144, 3), 1.0), np.full((144, 3), 2.0)]).astype(np.float32)
        self.assertAlmostEqual(float(cfg.combine(v, True)[0, 0, 0]), 4 * 1 - 3 * 2)
        self.assertAlmostEqual(float(cfg.combine(v[:1], False)[0, 0, 0]), 1.0)


if __name__ == "__main__":
    unittest.main()
