"""Draws the README charts and the turn diagram (docs/img/*.svg) from the measured numbers in docs/HISTORY.md.

Self-contained SVGs in the app's look: cream card, Dina's greens, Fredoka + Nunito subset and
embedded as WOFF2 (the fonts are OFL, from android/app/src/main/res/font). Needs fontTools and
brotli: `pip install fonttools brotli`, then `python scripts/charts/make_charts.py`.
"""

from __future__ import annotations

import base64
import io
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / "android/app/src/main/res/font"
OUT = ROOT / "docs/img"

# Dina's light theme (ui/theme/Theme.kt); series colors validated for contrast and colour blindness.
BG, CARD_LINE, INK, MUTED, FAINT, GRID = "#FBF7EE", "#E6DECB", "#1D3628", "#4A6152", "#7D8F81", "#ECE5D5"
GREEN, BLUE, TERRA = "#2A7A4B", "#4A7FC1", "#C8682F"
TERRA_SOFT = "#D98E62"  # TERRA a notch lighter: the part of the voice the «Rápida» option keeps

FACES = {  # css family/weight -> file
    ("Fredoka", 600): "fredoka_semibold.ttf",
    ("Nunito", 400): "nunito_regular.ttf",
    ("Nunito", 700): "nunito_bold.ttf",
    ("Nunito", 800): "nunito_extrabold.ttf",
}
_metrics: dict[str, TTFont] = {}


def width(text: str, size: float, face: tuple[str, int]) -> float:
    """Advance width of `text` in px, to place things after right- or left-aligned text."""
    font = _metrics.setdefault(FACES[face], TTFont(FONTS / FACES[face]))
    cmap, hmtx, upm = font.getBestCmap(), font["hmtx"], font["head"].unitsPerEm
    return sum(hmtx[cmap.get(ord(c), cmap[ord("?")])][0] for c in text) * size / upm


def font_css(chars: str) -> str:
    rules = []
    for (family, weight), file in FACES.items():
        options = subset.Options()
        options.flavor = "woff2"
        options.layout_features = ["kern", "liga"]
        font = TTFont(FONTS / file)
        subsetter = subset.Subsetter(options)
        subsetter.populate(text=chars)
        subsetter.subset(font)
        buffer = io.BytesIO()
        font.flavor = "woff2"
        font.save(buffer)
        data = base64.b64encode(buffer.getvalue()).decode()
        rules.append(f"@font-face{{font-family:'{family}';font-weight:{weight};src:url(data:font/woff2;base64,{data}) format('woff2')}}")
    return "\n".join(rules)


def es(value: float, decimals: int = 1) -> str:
    """Spanish number format: 2.533,5."""
    return f"{value:,.{decimals}f}".replace(",", " ").replace(".", ",").replace(" ", ".")


class Svg:
    def __init__(self, w: int, h: int, title: str, desc: str):
        self.w, self.h, self.title, self.desc = w, h, title, desc
        self.parts: list[str] = []

    def add(self, s: str) -> None:
        self.parts.append(s)

    def text(self, x: float, y: float, s: str, size: float, face=("Nunito", 400), fill=INK, anchor="start", extra="") -> None:
        family, weight = face
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{family}" font-weight="{weight}" font-size="{size}" '
                 f'fill="{fill}" text-anchor="{anchor}"{extra}>{s}</text>')

    def bar(self, x: float, y: float, w: float, h: float, fill: str, tip: str) -> None:
        """Square at the baseline, 4 px rounded at the data end."""
        r = min(4.0, w / 2, h / 2)
        self.add(f'<path d="M{x:.1f},{y:.1f} h{w - r:.1f} a{r},{r} 0 0 1 {r},{r} v{h - 2 * r:.1f} a{r},{r} 0 0 1 -{r},{r} '
                 f'h-{w - r:.1f} z" fill="{fill}"><title>{tip}</title></path>')

    def card(self, title: str, subtitle: str, legend: list[tuple[str, str]]) -> None:
        self.add(f'<rect x="0.5" y="0.5" width="{self.w - 1}" height="{self.h - 1}" rx="18" fill="{BG}" stroke="{CARD_LINE}"/>')
        self.text(32, 46, title, 24, ("Fredoka", 600))
        self.text(32, 71, subtitle, 14.5, ("Nunito", 400), MUTED)
        x = 32
        for color, label in legend:
            self.add(f'<rect x="{x}" y="88" width="13" height="13" rx="3" fill="{color}"/>')
            self.text(x + 19, 99.5, label, 14, ("Nunito", 700), INK)
            x += 19 + width(label, 14, ("Nunito", 700)) + 26

    def note(self, text: str) -> None:
        """A muted paragraph just above the footer, wrapped to the card (its height is `note_height`)."""
        lines = wrap(text, 13.5, ("Nunito", 400), self.w - 64)
        for k, line in enumerate(lines):
            self.text(32, self.h - 54 - 19 * (len(lines) - 1 - k), line, 13.5, ("Nunito", 400), MUTED)

    def footer(self, note: str) -> None:
        self.add(f'<line x1="32" y1="{self.h - 40}" x2="{self.w - 32}" y2="{self.h - 40}" stroke="{GRID}"/>')
        self.text(32, self.h - 17, note, 12.5, ("Nunito", 400), FAINT)

    def save(self, name: str) -> None:
        body = "\n".join(self.parts)
        chars = "".join(sorted(set(self.title + self.desc + body))) + "0123456789,.%·→−–ms "
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" '
               f'role="img" aria-labelledby="t d">\n<title id="t">{self.title}</title>\n<desc id="d">{self.desc}</desc>\n'
               f'<style>\n{font_css(chars)}\n</style>\n{body}\n</svg>\n')
        (OUT / name).write_text(svg, encoding="utf-8", newline="\n")
        print(f"{name}: {len(svg) // 1024} KB")


def axis(svg: Svg, x0: float, x1: float, top: float, bottom: float, vmax: float, step: float, fmt) -> None:
    v = 0.0
    while v <= vmax + 1e-9:
        x = x0 + (x1 - x0) * v / vmax
        svg.add(f'<line x1="{x:.1f}" y1="{top}" x2="{x:.1f}" y2="{bottom}" stroke="{GRID}" stroke-width="1"/>')
        svg.text(x, bottom + 19, fmt(v), 13, ("Nunito", 700), FAINT, "middle")
        v += step


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """95 % Wilson interval of k successes out of n, in %."""
    p = k / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * ((p * (1 - p) / n + z * z / (4 * n * n)) ** 0.5) / (1 + z * z / n)
    return 100 * (centre - half), 100 * (centre + half)


def note_height(text: str, w: int = 880) -> int:
    """Extra height a `Svg.note` of more than one line needs."""
    return 19 * (len(wrap(text, 13.5, ("Nunito", 400), w - 64)) - 1)


def wrap(text: str, size: float, face: tuple[str, int], max_w: float) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if line and width(candidate, size, face) > max_w:
            lines.append(line)
            line = word
        else:
            line = candidate
    return lines + [line]


def evolution() -> None:
    """RW2 v2.0 and Dina-Real of every app brain, with Wilson intervals."""
    groups = [
        ("LLAMADAS JSON", [("Dina 2", "350M", 154, "35,6", None), ("Dina 2.5", "350M", 167, "38,7", None),
                           ("Dina 3", "1.2B · app 2.0", 196, "45,4", 42), ("Dina 3", "1.2B · app 2.1", 243, "56,3", 54)]),
        ("CONTRATO + MOTOR DE DIÁLOGO", [("Dina 4 Preview", "350M", 224, "51,9", 53), ("Dina 4 Preview", "1.2B", 268, "62,0", 62),
                                         ("Dina 4", "350M", 284, "65,7", 64), ("Dina 4", "1.2B · app 2.2", 303, "70,1", 74),
                                         ("Dina 4.5", "350M", 345, "79,9", 79), ("Dina 4.5", "1.2B", 372, "86,1", 85)]),
    ]
    W, left, right, top, row, header = 880, 220, 120, 130, 52, 30
    n = sum(len(g[1]) for g in groups)
    bottom = top + n * row + header * len(groups) + 4
    H = bottom + 78
    x0, x1, vmax = left, W - right, 100.0
    X = lambda v: x0 + (x1 - x0) * v / vmax
    desc = "; ".join(f"{m} ({d}): RW2 {r} %" + (f", Dina-Real {real} %" if real is not None else "")
                     for _, rows in groups for m, d, _, r, real in rows)
    svg = Svg(W, H, "Evolución de Dina en RW2 y Dina-Real", desc)
    svg.card("De 35,6 % a 86,1 % en RW2",
             "Episodios correctos por modelo · la raya fina es el intervalo de confianza del 95 %",
             [(GREEN, "RW2 v2.0 · 432 episodios"), (TERRA, "Dina-Real · prueba ciega, 100 situaciones")])
    axis(svg, x0, x1, top - 6, bottom, vmax, 20, lambda v: f"{v:.0f} %")
    svg.add(f'<line x1="{x0}" y1="{top - 6}" x2="{x0}" y2="{bottom}" stroke="#B9B09A" stroke-width="1.5"/>')
    y = top
    for name, rows in groups:
        # each design gets a labelled band across the chart: JSON calls first, then contract + dialogue engine
        svg.add(f'<rect x="24" y="{y + 2}" width="{W - 48}" height="{header - 8}" rx="6" fill="{GRID}"/>')
        svg.text(36, y + header - 12, name, 12, ("Nunito", 800), MUTED, extra=' letter-spacing="1.3"')
        y += header
        for model, detail, k, shown, real in rows:
            last = model == "Dina 4.5"
            if last and detail.startswith("350M"):  # one band behind the two brains of the 2.4 APKs
                svg.add(f'<rect x="24" y="{y - 4}" width="{W - 48}" height="{2 * row - 2}" rx="10" fill="{GREEN}" fill-opacity="0.08"/>')
                svg.text(W - 36, y + 12, "APP 2.4", 11, ("Nunito", 800), GREEN, "end", ' letter-spacing="1.2"')
            svg.text(left - 16, y + 20, model, 15.5, ("Nunito", 800 if last else 700), INK, "end")
            svg.text(left - 16, y + 37, detail, 13, ("Nunito", 400), MUTED, "end")
            for j, (count, total, label, color, metric) in enumerate(((k, 432, shown, GREEN, "RW2"),
                                                                      (real, 100, f"{real},0" if real else "", TERRA, "Dina-Real"))):
                by = y + 8 + j * 18
                if count is None:
                    svg.text(x0 + 8, by + 11.5, "no medido", 12.5, ("Nunito", 400), FAINT)
                    continue
                value = 100 * count / total
                lo, hi = wilson(count, total)
                svg.bar(x0, by, X(value) - x0, 14, color, f"{model} {detail} · {metric}: {label} % [{es(lo)}–{es(hi)}]")
                cy = by + 7
                svg.add(f'<path d="M{X(lo):.1f},{cy - 4} v8 M{X(lo):.1f},{cy} H{X(hi):.1f} M{X(hi):.1f},{cy - 4} v8" '
                        f'stroke="{INK}" stroke-opacity="0.55" stroke-width="1.3" fill="none"/>')
                svg.text(X(hi) + 8, by + 12, f"{label} %", 14, ("Nunito", 800), INK)
            y += row
    svg.footer("Fuente: docs/HISTORY.md · .\\dev.ps1 eval (mismo motor, llama.cpp y GGUF que la APK)")
    svg.save("evolucion.svg")


def categories() -> None:
    """RW2 v2.1 by category: Dina 4 with the 2.3 engine against Dina 4.5 1.2B (paired McNemar)."""
    rows = [("Directas", 40, 87.5, 97.5, False, 95.0), ("Conversiones", 64, 9.4, 95.3, True, 92.2),
            ("Consultas", 40, 82.5, 95.0, False, 95.0), ("Charla", 34, 91.2, 94.1, False, 88.2),
            ("Fuera de alcance", 39, 94.9, 89.7, False, 92.3), ("Pendiente", 40, 70.0, 87.5, False, 75.0),
            ("Referencias", 44, 59.1, 86.4, True, 70.5), ("Errores", 39, 74.4, 84.6, False, 79.5),
            ("Ambigüedad", 40, 70.0, 82.5, False, 72.5), ("Correcciones", 40, 57.5, 82.5, True, 75.0),
            ("Multiacción", 40, 57.5, 77.5, False, 70.0), ("Interrupciones", 36, 55.6, 69.4, False, 66.7)]
    total = ("Total RW2 v2.1", 496, 64.3, 87.3, True, 81.5)
    W, left, top, row = 880, 236, 150, 36
    x0, x1, vmax = left, W - 210, 100.0
    X = lambda v: x0 + (x1 - x0) * v / vmax
    bottom = top + (len(rows) + 1) * row + 14
    H = bottom + 66
    desc = "; ".join(f"{c} ({n}): {es(a)} → {es(b)} %" + (" (significativo)" if s else "") for c, n, a, b, s, _ in [total] + rows)
    svg = Svg(W, H, "RW2 v2.1 por categoría: Dina 4 frente a Dina 4.5", desc)
    svg.card("Dina 4 → Dina 4.5 por categoría",
             "RW2 v2.1, episodios correctos · los tres modelos con el motor de la app 2.3, para aislar el modelo", [])
    legend = 14, ("Nunito", 700)
    svg.add(f'<circle cx="38" cy="94" r="5.5" fill="{BG}" stroke="{MUTED}" stroke-width="2"/>')
    svg.text(51, 99.5, "Dina 4", *legend, INK)
    lx = 51 + width("Dina 4", *legend) + 28
    svg.add(f'<circle cx="{lx:.1f}" cy="94" r="6.5" fill="{GREEN}"/>')
    svg.text(lx + 13, 99.5, "Dina 4.5 1.2B (Dina)", *legend, INK)
    lx += 13 + width("Dina 4.5 1.2B (Dina)", *legend) + 28
    svg.add(f'<rect x="{lx - 5:.1f}" y="89" width="10" height="10" rx="2" fill="{BLUE}" transform="rotate(45 {lx:.1f} 94)"/>')
    svg.text(lx + 13, 99.5, "Dina 4.5 350M (Dina Lite)", *legend, INK)
    head = 12, ("Nunito", 800), FAINT
    svg.text(left - 16, top - 12, "N", *head, "end")
    svg.text(W - 96, top - 12, "DINA 4 → 1.2B", *head, "end", ' letter-spacing="0.8"')
    svg.text(W - 32, top - 12, "PUNTOS", *head, "end", ' letter-spacing="0.8"')
    axis(svg, x0, x1, top - 4, bottom, vmax, 20, lambda v: f"{v:.0f} %")
    for i, (cat, n, before, after, sig, small) in enumerate([total] + rows):
        y = top + i * row + (14 if i else 0) + row / 2
        svg.text(left - 56, y + 5, cat, 15, ("Nunito", 800 if i == 0 else 700), INK, "end")
        svg.text(left - 16, y + 5, str(n), 13, ("Nunito", 400), FAINT, "end")
        color = GREEN if after >= before else TERRA
        svg.add(f'<line x1="{X(before):.1f}" y1="{y}" x2="{X(after):.1f}" y2="{y}" stroke="{color}" stroke-opacity="0.45" stroke-width="4"/>')
        svg.add(f'<circle cx="{X(before):.1f}" cy="{y}" r="5.5" fill="{BG}" stroke="{MUTED}" stroke-width="2"><title>{cat} · Dina 4: {es(before)} %</title></circle>')
        svg.add(f'<rect x="{X(small) - 4.5:.1f}" y="{y - 4.5}" width="9" height="9" rx="2" fill="{BLUE}" fill-opacity="0.85" '
                f'transform="rotate(45 {X(small):.1f} {y})"><title>{cat} · Dina 4.5 350M: {es(small)} %</title></rect>')
        svg.add(f'<circle cx="{X(after):.1f}" cy="{y}" r="6.5" fill="{GREEN}"><title>{cat} · Dina 4.5 1.2B: {es(after)} %</title></circle>')
        delta = after - before
        svg.text(W - 96, y + 5, f"{es(before)} → {es(after)}", 13.5, ("Nunito", 700 if sig else 400), INK if sig else MUTED, "end")
        delta_color = TERRA if delta < 0 else (INK if sig else MUTED)
        svg.text(W - 32, y + 5, ("+" if delta >= 0 else "−") + es(abs(delta)) + ("*" if sig else " "), 13.5,
                 ("Nunito", 800 if sig else 400), delta_color, "end")
        if i == 0:
            svg.add(f'<line x1="32" y1="{y + row / 2 + 7}" x2="{W - 32}" y2="{y + row / 2 + 7}" stroke="{GRID}"/>')
    svg.add(f'<line x1="{x0}" y1="{top - 4}" x2="{x0}" y2="{bottom}" stroke="#B9B09A" stroke-width="1.5"/>')
    svg.footer("* significativa (McNemar pareado, p &lt; 0,05) · ninguna categoría empeora de forma significativa "
               "· Fuente: eval-results/v2")
    svg.save("categorias.svg")


def latency() -> None:
    """Transcript end to the first sound you hear, by edition (eval-results/latency/*.md, PC, p50 of 24 turns).

    Before 2.4 the ~0.5 s of silence Supertonic puts before every sentence was played; 2.4 trims it. That silence is
    measured with the 2.4 voice at 2.3's settings (8 steps, no trim): the guided graph gives the 2.3 audio bit for bit.
    Dina's "Rápida" voice (4 steps) is not a row of its own: its total is a note beside the 8-step bar.
    """
    stages = [("Prefill", "Prefill", GREEN), ("Decodificación", "LLM", BLUE), ("Síntesis de voz", "Voz", TERRA),
              ("Silencio inicial", "Silencio", "url(#hatch)")]
    runs = [("Sin optimizar", ("Dina 4 1.2B · 8 pasos", "toda la respuesta de golpe"), [700, 226, 1549, 424], 2957, "≈"),
            ("Dina 2.3", ("Dina 4.5 1.2B · 8 pasos", "voz desde la primera frase"), [111, 100, 939, 424], 1681, "≈"),
            ("Dina 2.4", ("Dina 4.5 1.2B · 8 pasos", "sin el silencio"), [96, 97, 662, 0], 880, ""),
            ("Dina Lite 2.4", ("Dina 4.5 350M · 4 pasos", "sin el silencio"), [43, 55, 364, 0], 491, "")]
    fast = {"Dina 2.4": 581}  # Dina with the «Rápida» voice (4 pasos): its total, same batch
    note = ("Supertonic genera ~0,5 s de silencio antes de cada frase; la 2.4 lo recorta. "
            "Una respuesta ya dicha sale de la caché en 0,18 s.")
    W, left, right, top, row = 880, 230, 70, 150, 104
    H = top + len(runs) * row + 84 + note_height(note)
    x0, x1, vmax = left, W - right, 3200.0
    desc = "; ".join(f"{name}: {approx}{total} ms hasta el primer sonido (" +
                     ", ".join(f"{s[0]} {v} ms" for s, v in zip(stages, values) if v) + ")"
                     for name, _, values, total, approx in runs)
    svg = Svg(W, H, "Latencia del fin de la frase al primer sonido", desc)
    svg.add(f'<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
            f'<rect width="6" height="6" fill="{GRID}"/><line x1="0" y1="0" x2="0" y2="6" stroke="{FAINT}" stroke-width="2.5"/></pattern></defs>')
    svg.card("Tiempo hasta oír la respuesta",
             "Del fin de la transcripción al primer audio · PC, mediana de 24 turnos",
             [(c, s) for s, _, c in stages[:3]] + [(TERRA_SOFT, "Síntesis con la voz Rápida"), (stages[3][2], stages[3][0])])
    bottom = top + len(runs) * row - 26
    axis(svg, x0, x1, top - 14, bottom, vmax, 500, lambda v: "0" if v == 0 else f"{es(v / 1000, 1)} s")
    for i, (name, detail, values, total, approx) in enumerate(runs):
        y = top + i * row
        svg.text(left - 16, y + 22, name, 16, ("Nunito", 800), INK, "end")
        for k, line in enumerate(detail):
            svg.text(left - 16, y + 42 + k * 16, line, 12.5, ("Nunito", 400), MUTED, "end")
        # (segment, chip label, colour, ms, gap after)
        parts = []
        for (stage, short, color), value in zip(stages, values, strict=True):
            if not value:
                continue
            done = sum(v for _, _, _, v, _, _ in parts)  # time before this stage
            if name in fast and color == TERRA:  # the voice, lighter up to where the 4-step one would have ended
                quick = fast[name] - done
                parts += [(f"{stage}, hasta donde acabaría la voz Rápida", short, TERRA_SOFT, quick, value, 0),
                          (f"{stage}, lo que añaden los 8 pasos", None, TERRA, value - quick, value, 2)]
            else:
                parts.append((stage, short, color, value, value, 2))
        x = x0
        for j, (stage, _, color, length, value, gap) in enumerate(parts):
            w = (x1 - x0) * length / vmax
            tip = f"{name} · {stage}: {value} ms"
            if j == len(parts) - 1:  # the data end of the stack gets the rounded corner
                svg.bar(x, y + 4, w, 26, color, tip)
            else:
                svg.add(f'<rect x="{x:.1f}" y="{y + 4:.1f}" width="{w:.1f}" height="26" fill="{color}"><title>{tip}</title></rect>')
            x += w + gap  # 2 px surface gap between stages
        label = f"{approx}{es(total / 1000, 2)} s"
        svg.text(x + 10, y + 23, label, 17, ("Nunito", 800), INK)
        if name in fast:
            svg.text(x + 20 + width(label, 17, ("Nunito", 800)), y + 23, f"·  voz Rápida (4 pasos): {es(fast[name] / 1000, 2)} s", 14,
                     ("Nunito", 700), MUTED)
        cx = x0  # breakdown under the bar: every stage named, even the thin ones
        chips = sum(15 + width(f"{short} {es(v, 0)} ms", 13, ("Nunito", 700)) + 22 for _, short, _, _, v, _ in parts if short)
        svg.add(f'<rect x="{x0 - 4}" y="{y + 36}" width="{chips:.1f}" height="22" fill="{BG}"/>')
        for _, short, color, _, value, _ in parts:
            if not short:
                continue
            svg.add(f'<circle cx="{cx + 5}" cy="{y + 47}" r="4.5" fill="{TERRA if color == TERRA_SOFT else color}"/>')
            chip = f"{short} {es(value, 0)} ms"
            svg.text(cx + 15, y + 51.5, chip, 13, ("Nunito", 700), INK)
            cx += 15 + width(chip, 13, ("Nunito", 700)) + 22
    svg.note(note)
    svg.footer("Fuente: .\\dev.ps1 latency · las medianas por tramo no suman exactamente el total · sin verificar en el móvil")
    svg.save("latencia.svg")


# Model files each edition loads by default, as staged for the APK (android/model-assets/<edition>/). Sizes in bytes,
# measured from those files; used when they are not on disk (they are not in the repository).
WEIGHTS = {
    "brain": {"full": ("models/llm/dina-4.5-1.2b-Q4_K_M.gguf", 730_898_368), "lite": ("models/llm/dina-4.5-350m-Q8_0.gguf", 379_219_904)},
    "voice": [("tts/supertonic3-v2/onnx/vector_estimator.onnx", 65_506_604), ("tts/supertonic3-v2/onnx/vocoder.onnx", 25_789_382),
              ("tts/supertonic3-v2/onnx/text_encoder.onnx", 9_702_733), ("tts/supertonic3-v2/onnx/duration_predictor.onnx", 1_188_757),
              ("tts/supertonic3-v2/onnx/guidance.bin", 154_624)],
    "wake": [("models/wake/melspectrogram.onnx", 1_087_958), ("models/wake/embedding_model.onnx", 1_326_578),
             ("models/wake/dina_wakeword_head.onnx", 800_297)],
    "moonshine": [("models/stt/base-es/encoder_model.ort", 20_964_320), ("models/stt/base-es/decoder_model_merged.ort", 43_612_200)],
}


def weight_mb(edition: str, files: list[tuple[str, int]]) -> float:
    """Total size of `files` in MB (10^6 bytes), from android/model-assets when staged, else the recorded sizes."""
    total = 0
    for path, recorded in files:
        file = ROOT / "android/model-assets" / edition / path
        size = file.stat().st_size if file.is_file() else recorded
        if size != recorded:
            print(f"  {path}: {size} bytes on disk, {recorded} recorded in WEIGHTS")
        total += size
    return total / 1e6


def memory() -> None:
    """Raw weights of every model each edition keeps loaded while it listens and speaks (default settings).

    Brain (GGUF), Supertonic (8-bit ONNX + guidance constants) and the "Dina" detector. Android's recognizer is the default
    and lives outside the app; Moonshine, its in-app alternative, is shown apart. The runtime adds the context, the activations
    and the prompt cache on top: that is measured on the phone (Diagnóstico, PSS), not here.
    """
    stages = [("LLM (Dina 4.5)", "LLM", BLUE), ("TTS (Supertonic 3)", "TTS", TERRA), ("Wake word (openWakeWord)", "Wake word", GREEN)]
    runs = []
    for name, edition, detail in (("Dina", "full", ("Dina 4.5 1.2B", "GGUF Q4_K_M")), ("Dina Lite", "lite", ("Dina 4.5 350M", "GGUF Q8_0"))):
        values = [weight_mb(edition, [WEIGHTS["brain"][edition]]), weight_mb(edition, WEIGHTS["voice"]), weight_mb(edition, WEIGHTS["wake"])]
        runs.append((name, detail, values, sum(values)))
    moonshine = weight_mb("full", WEIGHTS["moonshine"])
    mb = lambda v: f"{es(v, 1)} MB"
    (full, lite) = runs
    less_total = round(100 * (1 - lite[3] / full[3]))
    less_brain = round(100 * (1 - lite[2][0] / full[2][0]))
    note = (f"No incluye el STT de Android (fuera del proceso de la app; Moonshine, el STT integrado alternativo, añade "
            f"{mb(moonshine)}) ni la caché KV, las instantáneas de prompt y las activaciones en ejecución.")
    W, left, right, top, row = 880, 230, 70, 150, 104
    H = top + len(runs) * row + 84 + note_height(note)
    x0, x1, vmax = left, W - right, 1000.0
    desc = "; ".join(f"{name}: {mb(total)} de modelos (" + ", ".join(f"{s[0]} {mb(v)}" for s, v in zip(stages, values)) + ")"
                     for name, _, values, total in runs) + f"; Moonshine, opcional: {mb(moonshine)}"
    svg = Svg(W, H, "Peso de los modelos en memoria: Dina y Dina Lite", desc)
    svg.card(f"Dina Lite: un {less_total} % menos de pesos en memoria",
             f"Pesos residentes durante la sesión de voz (LLM + TTS + wake word) · LLM: −{less_brain} %",
             [(c, s) for s, _, c in stages])
    bottom = top + len(runs) * row - 26
    axis(svg, x0, x1, top - 14, bottom, vmax, 200, lambda v: "0" if v == 0 else f"{es(v, 0)} MB")
    for i, (name, detail, values, total) in enumerate(runs):
        y = top + i * row
        svg.text(left - 16, y + 22, name, 16, ("Nunito", 800), INK, "end")
        for k, line in enumerate(detail):
            svg.text(left - 16, y + 42 + k * 16, line, 12.5, ("Nunito", 400), MUTED, "end")
        x = x0
        for j, ((stage, _, color), value) in enumerate(zip(stages, values)):
            w = max(2.0, (x1 - x0) * value / vmax)
            tip = f"{name} · {stage}: {mb(value)}"
            if j == len(stages) - 1:
                svg.bar(x, y + 4, w, 26, color, tip)
            else:
                svg.add(f'<rect x="{x:.1f}" y="{y + 4:.1f}" width="{w:.1f}" height="26" fill="{color}"><title>{tip}</title></rect>')
            x += w + 2
        svg.text(x + 10, y + 23, f"{es(total, 0)} MB", 17, ("Nunito", 800), INK)
        cx = x0
        chips = sum(15 + width(f"{short} {mb(v)}", 13, ("Nunito", 700)) + 22 for (_, short, _), v in zip(stages, values))
        svg.add(f'<rect x="{x0 - 4}" y="{y + 36}" width="{chips:.1f}" height="22" fill="{BG}"/>')
        for (_, short, color), value in zip(stages, values):
            svg.add(f'<circle cx="{cx + 5}" cy="{y + 47}" r="4.5" fill="{color}"/>')
            label = f"{short} {mb(value)}"
            svg.text(cx + 15, y + 51.5, label, 13, ("Nunito", 700), INK)
            cx += 15 + width(label, 13, ("Nunito", 700)) + 22
    svg.note(note)
    svg.footer("Fuente: archivos de cada APK (android/model-assets) · MB = 10^6 bytes · en el móvil: Ajustes › Diagnóstico")
    svg.save("memoria.svg")


def pipeline() -> None:
    """One turn from wake word to speech, following a real example through the contract."""
    steps = [
        (TERRA, "Wake word", "openWakeWord con una cabeza propia, cada 80 ms en el dispositivo. Hasta oír «Dina», nada se "
         "transcribe.", "«Dina»"),
        (TERRA, "STT", "El reconocedor de Android o Moonshine, en el dispositivo, pasa el audio a texto.", "despiértame mañana a las siete"),
        (BLUE, "Dina 4.5 · LLM", "Lee la transcripción tras el estado (hora, alarmas, lista…), ya en la caché KV desde que "
         "empezaste a hablar. Decodifica operaciones del contrato con gramática GBNF y, a veces, texto libre filtrado.",
         "alarm.add(7:00 mañana, day=mañana)"),
        (GREEN, "Motor de diálogo", "Resuelve referencias («esa», «la primera»), pide los datos que faltan y mantiene lo pendiente.",
         "alarma nueva, no falta nada"),
        (GREEN, "ToolEngine", "Valida, ejecuta y persiste el estado. La alarma queda programada en AlarmManager.",
         "alarma · mañana 7:00 · una vez"),
        (GREEN, "Respuesta", "Una plantilla con los datos del resultado real, nunca el texto del modelo.",
         "«Alarma puesta para mañana a las 7:00.»"),
        (TERRA, "TTS", "Supertonic 3 sintetiza la primera frase, recorta su silencio y la reproduce mientras genera el resto.",
         "primer audio a ~0,9 s (PC)"),
    ]
    W, top = 880, 128
    tx, desc_w, ex_x = 82, 425, 520
    rows = []
    for color, title, text, example in steps:
        lines = wrap(text, 13, ("Nunito", 400), desc_w)
        rows.append((color, title, lines, example, 42 + 17 * len(lines)))
    H = top + sum(r[4] for r in rows) + 86
    svg = Svg(W, H, "Cómo funciona un turno de Dina", "; ".join(f"{t}: {' '.join(l)} Ejemplo: {e}" for _, t, l, e, _ in rows))
    svg.card("Cómo responde Dina", "Un turno de principio a fin, con lo que pasa en cada paso al pedir una alarma",
             [(TERRA, "Audio"), (BLUE, "LLM: interpreta"), (GREEN, "Kotlin determinista: decide y ejecuta")])
    svg.text(ex_x, top - 6, "EN EL EJEMPLO", 11.5, ("Nunito", 800), FAINT, extra=' letter-spacing="1.4"')
    y = top + 8
    centres = []
    for color, title, lines, example, h in rows:
        cy = y + 16
        centres.append(cy)
        svg.text(tx, cy + 6, title, 17, ("Fredoka", 600))
        for k, line in enumerate(lines):
            svg.text(tx, cy + 26 + 17 * k, line, 13, ("Nunito", 400), MUTED)
        svg.add(f'<rect x="{ex_x}" y="{cy - 13}" width="{W - 32 - ex_x}" height="34" rx="9" fill="#FFFFFF" '
                f'stroke="{color}" stroke-opacity="0.5"/>')
        mono = color == BLUE
        family = "ui-monospace, Consolas, Menlo, monospace" if mono else "Nunito"
        svg.add(f'<text x="{ex_x + 14}" y="{cy + 9}" font-family="{family}" font-weight="{600 if mono else 700}" '
                f'font-size="{14 if mono else 14.5}" fill="{INK}">{example}</text>')
        y += h
    svg.add(f'<line x1="50" y1="{centres[0]}" x2="50" y2="{centres[-1]}" stroke="{CARD_LINE}" stroke-width="3"/>')
    for i, (cy, (color, *_)) in enumerate(zip(centres, rows)):
        svg.add(f'<circle cx="50" cy="{cy}" r="15" fill="{color}"/>')
        svg.text(50, cy + 5.5, str(i + 1), 15, ("Nunito", 800), "#FFFFFF", "middle")
    svg.text(32, H - 52, "Si el modelo entiende mal, falla la acción, no la respuesta: Dina no puede decir que hizo algo que no hizo.",
             13, ("Nunito", 700), INK)
    svg.footer("Detalle: docs/ARCHITECTURE.md · docs/contrato.md · docs/motor.md")
    svg.save("como-funciona.svg")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    pipeline()
    evolution()
    categories()
    latency()
    memory()


