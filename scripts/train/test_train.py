"""Data preparation checks without torch, a GPU, or benchmark inputs."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import train


def example(utterance, completion):
    return {"prompt": f"system: cuerpo e internet\n[antes]\nusuario: tienes cuerpo\n[ahora]\n{utterance}{train.END}",
            "completion": completion}


class TrainingFilterTest(unittest.TestCase):
    def test_obsolete_zone_action_is_removed_from_multi_action(self):
        self.assertEqual("no_zonas", train.training_rejection(example("otra hora", 'say("Hola")\nno(zonas)')))
        self.assertIsNone(train.training_rejection(example("sin música", "no(música)")))

    def test_unsolicited_disclaimers_ignore_system_and_history(self):
        self.assertEqual("unsolicited_body", train.training_rejection(example("hola", 'say("No tengo cuerpo.")')))
        self.assertEqual("unsolicited_internet", train.training_rejection(example("hola", 'say("No tengo internet.")')))

    def test_requested_topics_are_kept(self):
        self.assertIsNone(train.training_rejection(example("¿Tienes cuerpo?", 'say("No tengo cuerpo.")')))
        self.assertIsNone(train.training_rejection(example("¿Tienes conexión a la red?", 'say("No uso internet.")')))
        self.assertIsNone(train.training_rejection(example("¿Tienes cuerpo e internet?", 'say("No tengo cuerpo ni internet.")')))

    def test_each_disclaimer_must_be_requested(self):
        self.assertEqual("unsolicited_internet", train.training_rejection(example("¿Tienes cuerpo?", 'say("No tengo cuerpo ni internet.")')))

    def test_only_say_text_is_filtered(self):
        self.assertIsNone(train.training_rejection(example("apunta el libro", 'list.add("El cuerpo")')))
        self.assertIsNone(train.training_rejection(example("no me hables de eso", 'say("Todo claro.")')))


class DataPreparationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def batch(self, name, rows, dev=(), suffix=""):
        directory = self.root / name
        directory.mkdir(exist_ok=True)
        for split, values in (("train", rows), ("dev", dev)):
            with (directory / f"{split}{suffix}.jsonl").open("w", encoding="utf-8", newline="") as output:
                for row in values:
                    output.write(json.dumps(row, ensure_ascii=False) + "\n")
        return directory

    def test_filters_training_but_keeps_development_unchanged(self):
        obsolete = example("la hora allí", "no(zonas)")
        good = example("apunta arroz", 'list.add("arroz")')
        directory = self.batch("a", [obsolete, good], [obsolete, obsolete])
        rows, dev, report = train.prepare_data([directory])
        self.assertEqual([good], rows)
        self.assertEqual([obsolete, obsolete], dev)
        self.assertEqual({"no_zonas": 1}, report["batches"][0]["filtered"])

    def test_dedup_runs_before_weighting_and_across_batches(self):
        row = example("hola", 'say("Hola.")')
        first = self.batch("a", [row, row])
        second = self.batch("b", [row])
        rows, _, report = train.prepare_data([first, second], weights="2+1", dedup=True)
        self.assertEqual([row, row], rows)
        self.assertEqual([1, 1], [b["duplicates"] for b in report["batches"]])

    def test_dedup_excludes_training_prompts_present_in_any_dev_batch(self):
        row = example("hola", 'say("Hola.")')
        other_label = {**row, "completion": 'say("Buenas.")'}
        first = self.batch("a", [row])
        second = self.batch("b", [], [other_label])
        rows, dev, report = train.prepare_data([first, second], dedup=True)
        self.assertEqual([], rows)
        self.assertEqual([other_label], dev)
        self.assertEqual(1, report["batches"][0]["dev_overlap"])

    def test_fractional_weights_are_reproducible_and_cover_more_than_a_prefix(self):
        source = [example(f"saludo {i}", 'say("Hola.")') for i in range(10)]
        directory = self.batch("a", source)
        rows, _, _ = train.prepare_data([directory], weights="1.5")
        self.assertEqual(15, len(rows))
        self.assertEqual(rows, train.prepare_data([directory], weights="1.5")[0])
        self.assertTrue(all(row in rows for row in source))

    def test_zero_weight_does_not_hide_later_duplicates(self):
        row = example("hola", 'say("Hola.")')
        first = self.batch("a", [row])
        second = self.batch("b", [row])
        self.assertEqual([row], train.prepare_data([first, second], weights="0+1", dedup=True)[0])

    def test_weights_reject_invalid_values_and_wrong_length(self):
        for weights in ("1", "-1+1", "nan+1", "inf+1"):
            with self.subTest(weights=weights), self.assertRaises(ValueError):
                train.parse_weights(weights, 2)
        self.assertEqual([1.0, 2.0], train.parse_weights("1,2", 2))

    def test_missing_batch_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "Falta el lote"):
            train.prepare_data([self.root / "missing"])

    def test_history_zero_uses_the_matching_render(self):
        row = example("hola", 'say("Hola.")')
        directory = self.batch("a", [row], suffix="-h0")
        self.assertEqual([row], train.prepare_data([directory], history=0)[0])


if __name__ == "__main__":
    unittest.main()
