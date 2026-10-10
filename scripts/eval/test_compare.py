import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from compare import compare, mcnemar, outcomes, rate


class PairedComparisonTest(unittest.TestCase):
    def test_exact_threshold_for_one_direction(self):
        self.assertEqual(1.0, mcnemar(0, 0))
        self.assertEqual(0.0625, mcnemar(5, 0))
        self.assertEqual(0.03125, mcnemar(6, 0))
        self.assertEqual(mcnemar(2, 8), mcnemar(8, 2))
        self.assertEqual(1.0, mcnemar(3, 3))

    def test_whole_episode_fails_if_one_turn_fails(self):
        self.assertEqual({"a": False, "b": True}, outcomes([
            {"episode": "a", "pass": True}, {"episode": "b", "pass": True}, {"episode": "a", "pass": False}]))

    def test_reports_regression_and_new_section_separately(self):
        before = {str(i): True for i in range(6)}
        after = {str(i): False for i in range(6)} | {"new": True}
        categories = {str(i): "directas" for i in range(6)} | {"new": "conversiones"}
        report = compare(before, after, categories)
        self.assertIn("Bajada significativa", report)
        self.assertIn("6 / 0 | 0.0312", report)
        self.assertIn("conversiones: 100,0 %", report)

    def test_refuses_incomplete_or_unknown_pairs(self):
        with self.assertRaises(ValueError):
            compare({"a": True}, {"b": True}, {"a": "directas", "b": "directas"})
        with self.assertRaises(ValueError):
            compare({"a": True}, {"a": True}, {})

    def test_wilson_limits_for_all_successes(self):
        self.assertEqual("100,0 % [94,3–100,0] (64/64)", rate(64, 64))


if __name__ == "__main__":
    unittest.main()
