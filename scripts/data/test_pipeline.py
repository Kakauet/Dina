"""Unit tests of the data scripts (no network): python -m unittest discover scripts/data"""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import pipeline  # noqa: E402
import text as tx  # noqa: E402


class NoiseTest(unittest.TestCase):
    def test_noise_keeps_every_value(self):
        phrase = "Dina, ponme una alarma a las 7:30 y un temporizador de 25 minutos para la pasta."
        for seed in range(200):
            out = tx.noisy(phrase, str(seed))
            self.assertNotRegex(out, r"[¿?¡!.,]")
            self.assertTrue("7:30" in out or "siete y media" in out, out)
            self.assertTrue("25" in out or "veinticinco" in out, out)
            self.assertIn("pasta", out)
        self.assertEqual(tx.noisy(phrase, "x"), tx.noisy(phrase, "x"))

    def test_numbers_both_ways(self):
        self.assertEqual("treinta y cinco", tx.number_words(35))
        out = {tx.noisy("apunta treinta y cinco huevos", str(s)) for s in range(40)}
        self.assertTrue(any("35 huevos" in o for o in out) and any("treinta y cinco" in o for o in out), out)


class ContaminationTest(unittest.TestCase):
    def test_eight_grams_and_near_duplicates(self):
        bench = "oye pon una alarma para mañana a las siete y media que tengo médico"
        self.assertTrue(tx.ngrams("venga pon una alarma para mañana a las siete y media porfa") & tx.ngrams(bench))
        self.assertFalse(tx.ngrams("pon una alarma a las ocho") & tx.ngrams(bench))
        index = tx.MinHashIndex()
        index.add("apunta leche en la lista de la compra")
        self.assertTrue(index.near("apunta leche en la lista de la compra porfa"))
        self.assertFalse(index.near("¿cuánto le queda al temporizador?"))


class PipelineTest(unittest.TestCase):
    def test_clean_lines(self):
        text = "Aquí tienes:\n1. Ponme una alarma\n- «pon una alarma»\n2) Despiértame a las 7\n\nDespiértame a las 7"
        self.assertEqual(["Ponme una alarma", "pon una alarma", "Despiértame a las 7"], pipeline.clean_lines(text))


class AgentsTest(unittest.TestCase):
    def test_writers_keep_their_shares(self):
        counts = pipeline.collections.Counter()
        for i in range(100):
            pipeline.pick_writer(f"s{i}", {"luna-6": 0.6, "sol-6.1": 0.2, "sol-6": 0.2}, counts)
        self.assertEqual({"luna-6": 60, "sol-6.1": 20, "sol-6": 20}, dict(counts))

    def test_helpers_fall_back_tier_by_tier(self):
        counts = pipeline.collections.Counter()
        self.assertEqual("sol-6", pipeline.pick_helper("a", [[], ["sol-6"]], counts))
        self.assertEqual({"sol-6.1", "sol-6"}, {pipeline.pick_helper(f"b{i}", [["sol-6.1", "sol-6"]], counts) for i in range(3)})
        self.assertIsNone(pipeline.pick_helper("c", [[], []], counts))

    def test_judges_are_of_a_third_family(self):
        family = {"claude-sonnet-5.5": "claude", "luna-6": "luna", "sol-6.1": "sol", "sol-6": "sol"}
        codex_text = pipeline.judge_tiers(family, "luna", {"family": "sol", "interpreter": "sol-6"})
        self.assertEqual(["claude-sonnet-5.5"], codex_text[0])
        claude_text = pipeline.judge_tiers(family, "claude", {"family": "luna", "interpreter": "luna-6"})
        self.assertEqual(["sol-6.1", "sol-6"], claude_text[0])
        checked_by_sol = pipeline.judge_tiers(family, "claude", {"family": "sol", "interpreter": "sol-6.1"})
        self.assertEqual(["luna-6"], checked_by_sol[0])
        self.assertNotIn("claude-sonnet-5.5", checked_by_sol[1] + claude_text[1])

    def test_a_batch_keeps_its_own_writers(self):
        with tempfile.TemporaryDirectory() as tmp:
            batch = pipeline.Batch.__new__(pipeline.Batch)
            batch.dir = Path(tmp)
            self.assertEqual(pipeline.load_agents()["write"], pipeline.writer_shares(batch))
            shares = pipeline.writer_shares(batch, "claude-haiku-5.5,claude-sonnet-5.5=2")
            self.assertEqual({"claude-haiku-5.5": 1.0, "claude-sonnet-5.5": 2.0}, shares)
            self.assertEqual(shares, pipeline.writer_shares(batch, "luna-6"))

    def test_answers_of_another_model_than_assigned_are_left_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "reparto.txt").write_text("in-00.txt luna-6 xhigh codex\nin-01.txt sol-6 medium codex\n", encoding="utf-8")
            for n, model in (("00", "luna-6"), ("01", "luna-6")):
                (folder / f"ids-{n}.json").write_text(json.dumps([f"id{n}"]), encoding="utf-8")
                (folder / f"out-{n}.txt").write_text(f"modelo: {model}\n1: 3\n", encoding="utf-8")
            got = [(ids, text, name, family) for ids, text, name, family in pipeline.helper_answers(folder)]
            self.assertEqual([(["id00"], "1: 3\n", "luna-6", "luna")], got)
            self.assertTrue((folder / "out-00.merged").exists())
            self.assertFalse((folder / "out-01.merged").exists())
            self.assertEqual({"id01"}, pipeline.taken_ids(folder))


class RoundTripParseTest(unittest.TestCase):
    def test_headers_with_and_without_hashes(self):
        self.assertEqual({1: "ask()", 2: "alarm.add(7:00)\nlist.add(\"pan\")"}, pipeline.parse_blocks("### 1\nask()\n### 2\nalarm.add(7:00)\nlist.add(\"pan\")"))
        self.assertEqual({1: "ask()", 2: "no(noticias)"}, pipeline.parse_blocks("1\nask()\n2\nno(noticias)"))
        self.assertEqual({1: "timer.add()", 2: "yes()"}, pipeline.parse_blocks("```\n1. timer.add(?)\n2: yes()\n```"))
        self.assertEqual({1: 'timer.add("pizza")', 2: "alarm.edit(@, at=?)"}, pipeline.parse_blocks('1\ntimer.add(?, "pizza")\n2\nalarm.edit(@, at=?)'))
        self.assertEqual({1: "calc()", 2: 'calc("39/3")'}, pipeline.parse_blocks('1\ncalc("39 / ?")\n2\ncalc("39/3")'))


class ChatTest(unittest.TestCase):
    def test_reply_lines_split_and_bad_ones_drop(self):
        self.assertEqual(("hola, ¿qué tal?", "¡Muy bien! Aquí, esperando a que me pidas algo."),
                         pipeline.split_reply("hola, ¿qué tal? || ¡Muy bien! Aquí, esperando a que me pidas algo."))
        self.assertEqual(("dime algo", "Te digo 'hola'"), pipeline.split_reply('«dime algo» || Te digo "hola"'))
        self.assertIsNone(pipeline.split_reply("solo la frase"))
        self.assertIsNone(pipeline.split_reply("frase || "))

    def test_history_uses_the_generated_reply_of_a_chat_turn(self):
        ficha = {"id": "s", "turns": [{"answer": "¡Claro!", "reply": "charla"}, {"answer": "Temporizador de 5 minutos.", "reply": "coletilla"}, {"answer": "Hecho."}]}
        replies = {("s", 1): "¡Hola! ¿Qué tal?", ("s", 2): "¡Que aproveche!"}
        self.assertEqual("¡Hola! ¿Qué tal?", pipeline.answer_of(ficha, 1, replies))
        self.assertEqual("Temporizador de 5 minutos. ¡Que aproveche!", pipeline.answer_of(ficha, 2, replies))
        self.assertEqual("Hecho.", pipeline.answer_of(ficha, 3, replies))


if __name__ == "__main__":
    unittest.main()
