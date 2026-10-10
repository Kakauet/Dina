"""Compare local RW2 evaluations by paired episode outcomes; never export utterances."""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def project(file: Path, fields: tuple[str, ...]) -> list[dict]:
    """Read only selected scalar fields through rg, including from turns.jsonl."""
    pattern = r'"(?:' + "|".join(fields) + r')"\s*:\s*(?:"[^"\\]*"|true|false)'
    result = subprocess.run(["rg", "--no-heading", "--no-filename", "--line-number", "--only-matching", pattern, str(file)],
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode not in (0, 1):
        raise ValueError(result.stderr.strip())
    rows = {}
    for line in result.stdout.splitlines():
        number, _, scalar = line.partition(":")
        rows.setdefault(int(number), {}).update(json.loads("{" + scalar + "}"))
    if any(set(row) != set(fields) for row in rows.values()):
        raise ValueError(f"Faltan campos {fields} en {file}")
    return list(rows.values())


def outcomes(rows: list[dict]) -> dict[str, bool]:
    episodes = {}
    for row in rows:
        if not isinstance(row["pass"], bool):
            raise ValueError("pass debe ser booleano")
        episode = row["episode"]
        episodes[episode] = episodes.get(episode, True) and row["pass"]
    return episodes


def mcnemar(losses: int, gains: int) -> float:
    """Exact two-sided McNemar test (binomial on discordant pairs)."""
    n = losses + gains
    if n == 0:
        return 1.0
    return min(1.0, 2 * sum(math.comb(n, k) for k in range(min(losses, gains) + 1)) / 2**n)


def rate(hits: int, n: int) -> str:
    if not n:
        return "—"
    p = hits / n
    z = 1.96
    d = 1 + z * z / n
    center = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return f"{100 * p:.1f} % [{100 * max(0, center - half):.1f}–{100 * min(1, center + half):.1f}] ({hits}/{n})".replace(".", ",")


def compare(before: dict[str, bool], after: dict[str, bool], categories: dict[str, str]) -> str:
    if not before or not after:
        raise ValueError("La comparación necesita dos pasadas completas")
    if missing := before.keys() - after.keys():
        raise ValueError(f"Faltan {len(missing)} episodios de referencia en el candidato")
    if unknown := (before.keys() | after.keys()) - categories.keys():
        raise ValueError(f"Hay {len(unknown)} episodios sin categoría")
    groups = {category: [episode for episode in before if categories[episode] == category]
              for category in sorted({categories[episode] for episode in before})}
    groups = {"Total pareado": list(before), **groups}
    lines = ["# Comparación pareada de RW2", "", "IC de Wilson al 95 %; McNemar exacto bilateral. Una bajada es significativa si p < 0,05.", "",
             "| Categoría | Referencia (IC 95 %) | Candidato (IC 95 %) | Pierde / gana | p | Decisión |",
             "|---|---:|---:|---:|---:|---|"]
    for category, ids in groups.items():
        losses = sum(before[i] and not after[i] for i in ids)
        gains = sum(not before[i] and after[i] for i in ids)
        p = mcnemar(losses, gains)
        decision = "Sin cambio significativo"
        if p < 0.05:
            decision = "Bajada significativa" if losses > gains else "Mejora significativa"
        probability = f"{p:.4f}" if p >= 0.0001 else "<0.0001"
        lines.append(f"| {category} | {rate(sum(before[i] for i in ids), len(ids))} | {rate(sum(after[i] for i in ids), len(ids))} | {losses} / {gains} | {probability} | {decision} |")
    extra = after.keys() - before.keys()
    if extra:
        lines += ["", "Secciones nuevas, sin pareja en la referencia:", ""]
        for category in sorted({categories[i] for i in extra}):
            ids = [i for i in extra if categories[i] == category]
            lines.append(f"- {category}: {rate(sum(after[i] for i in ids), len(ids))}.")
    return "\n".join(lines) + "\n"


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path, help="reference evaluation folder with turns.jsonl")
    parser.add_argument("after", type=Path, help="candidate evaluation folder with turns.jsonl")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    try:
        before = outcomes(project(args.before / "turns.jsonl", ("episode", "pass")))
        after = outcomes(project(args.after / "turns.jsonl", ("episode", "pass")))
        categories = {row["id"]: row["cat"] for file in (ROOT / "benchmark/realworld_v2/episodes").glob("*.jsonl")
                      for row in project(file, ("id", "cat"))}
        report = compare(before, after, categories)
    except ValueError as error:
        parser.error(str(error))
    if args.out:
        with args.out.open("w", encoding="utf-8", newline="") as output:
            output.write(report)
    print(report, end="")


if __name__ == "__main__":
    main()
