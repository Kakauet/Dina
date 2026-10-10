"""Fills frases.tsv from a dictation file without printing any phrase (Dina-Real is blind).

Usage: python benchmark/dina_real/importar.py dictado.txt <persona>
Each line: an id (F001a, f1a or 1a) followed by the phrase exactly as the dictation wrote it.
"""
import csv, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
LINE = re.compile(r"^\s*[Ff]?0*(\d{1,3})\s*([a-hA-H])\b[\s:.\-–]*(.*)$")

def main(path, persona):
    tsv = os.path.join(HERE, "frases.tsv")
    with open(tsv, encoding="utf-8") as f:
        rows = list(csv.reader(f, delimiter="\t"))
    header, rows = rows[0], [r + [""] * (3 - len(r)) for r in rows[1:] if r]
    ids = {r[0] for r in rows}
    found, unknown = {}, []
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            m = LINE.match(line)
            if not m:
                continue
            fid = f"F{int(m.group(1)):03d}{m.group(2).lower()}"
            phrase = m.group(3).strip()
            if fid not in ids:
                unknown.append(fid)
            elif phrase:
                found[fid] = phrase
    template = {r[0] for r in rows if not r[1] and not r[2]}
    kept = [r for r in rows if not (r[0] in template)] + [r for r in rows if r[0] in template and r[0] not in found]
    kept = [r for r in kept if not (r[1] == persona)]
    kept += [[fid, persona, found[fid]] for fid in found]
    order = {fid: i for i, fid in enumerate(r[0] for r in rows)}
    kept.sort(key=lambda r: (order.get(r[0], 10**6), r[1]))
    with open(tsv, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(header)
        w.writerows(kept)
    missing = sorted(ids - set(found))
    print(f"{persona}: {len(found)} frases importadas de {len(ids)}.")
    if missing:
        print(f"Faltan {len(missing)}: {', '.join(missing[:30])}{' …' if len(missing) > 30 else ''}")
    if unknown:
        print(f"Ids que no existen (revisa la línea): {', '.join(unknown)}")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
