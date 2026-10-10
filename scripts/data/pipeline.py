"""Training data pipeline (docs/datos.md), written by helper agents. Every step is resumable.

    python scripts/data/pipeline.py next --batch lote3 --n 2000   # merges answers, exports what is due, lists the files
    python scripts/data/pipeline.py run --batch lote3             # when `next` has nothing left: verify … report
    python scripts/data/pipeline.py <step> --batch lote3          # one step

Steps: scenarios (Kotlin) → generate → roundtrip → verify (Kotlin) → judge → contamination → select
(with STT noise) → render (Kotlin) → report. Files live in data/batches/<batch>/ (git-ignored).
Labels always come from the engine; agents only write phrases, interpret them back and rate them.
Agents (agents.json) work through in-NN.txt/out-NN.txt files; reparto.txt assigns each file to one model, so writer,
checker and judge of an item are of different families. No step calls an API.
Chat: for turns marked "reply" the writer also writes what Dina answers, with her personality sheet; the judge rates it
and rejects any claim of facts, and the app's say filter checks it.
Nothing from benchmark/ is ever sent anywhere: contamination is checked here, locally, and only counted.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import random
import re
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import text as tx  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).parent
DATA = ROOT / "data" / "batches"
STEPS = ["scenarios", "generate", "roundtrip", "verify", "judge", "contamination", "select", "render", "report"]
NOISE_SHARE = 0.35
ROUNDTRIP_PACK = 32
MIN_JUDGE = 2

GENERATE_SYSTEM = """Escribes lo que una persona le dice en voz alta a Dina, la asistente de voz de su móvil. Dina funciona sin internet y sabe de alarmas, temporizadores, cronómetros, la lista de la compra, el volumen, la hora y la fecha (también la hora de otras ciudades), cuentas y conversiones de unidades.

Te doy una persona y una ficha con lo que quiere decir en este turno. Escribe {n} variantes distintas de lo que diría esa persona, como se habla y no como se escribe:
- Cortas y naturales, con el registro y las costumbres de la persona. Muchas veces sin saludar ni decir «Dina».
- Cada variante pide exactamente lo de la ficha: los mismos datos, ni uno más ni uno menos. Respeta sus restricciones.
- Que no empiecen todas igual: varía el verbo, el orden y la forma de decir horas y números.
- Que alguna sea difícil, como se habla de verdad: larga, con titubeos («eh…», «a ver»), con la petición al final o con números dichos de otra forma («un cuarto para las siete», «mil doscientos», «tres y medio»), pero pidiendo lo mismo.
- Sin comillas, sin numerar y sin explicaciones: una variante por línea."""

ROUNDTRIP_SYSTEM = """Traduces lo que alguien le dice a Dina, una asistente de voz, a acciones con un formato fijo. Usa el estado (lo que hay ahora) y lo anterior de la conversación.

{contract}
Te daré una o varias situaciones, cada una con su estado (y lo anterior, si lo hay) y varias frases independientes; las frases van numeradas seguidas. Para cada frase responde con su número y sus acciones, sin nada más:
### 1
acción
### 2
acción
acción"""

PERSONALITY = """Dina es cercana y cálida, tutea y habla en español de España. Es breve: una o dos frases cortas, como se habla. Tiene un punto de humor cuando viene a cuento, sin pasarse. Sabe hacer alarmas, temporizadores, cronómetros, la lista de la compra, el volumen, la hora y la fecha (también la hora de otras ciudades), cuentas y conversiones de unidades, y contar chistes y curiosidades; nada más, y no se atribuye otras cosas (agenda, correo, música, mensajes, buscar en internet). Es honesta con sus límites: si no sabe algo o no puede hacerlo, lo dice con naturalidad y no se lo inventa. Solo habla de lo que no tiene (internet, cuerpo, memoria) si se lo preguntan, y cada vez con otras palabras. Nunca dice que ha hecho algo, ni da horas, fechas, cifras o datos del mundo (tiempo, noticias, resultados). Sin emojis."""

CHAT_SYSTEM = """Escribes diálogos breves entre una persona y Dina, la asistente de voz de su móvil. Dina funciona sin internet y sabe de alarmas, temporizadores, cronómetros, la lista de la compra, el volumen, la hora y la fecha (también la hora de otras ciudades), cuentas y conversiones de unidades; también cuenta chistes y curiosidades si se los piden.

Cómo es Dina: {personality}

Te doy una persona y una ficha con lo que dice en este turno (no pide nada que Dina pueda hacer). Escribe {n} variantes distintas, cada una en una línea con este formato:
lo que dice la persona || lo que le contesta Dina
- La persona habla como se habla, con su registro y sus costumbres; muchas veces sin decir «Dina». Que no empiecen todas igual.
- Dina contesta en una o dos frases (máximo 30 palabras) y cada respuesta es distinta de las demás.
- Sin comillas, sin numerar y sin explicaciones."""

COLETILLA_SYSTEM = """Escribes lo que una persona le dice en voz alta a Dina, la asistente de voz de su móvil, y la coletilla con la que Dina cierra su respuesta. Dina funciona sin internet y sabe de alarmas, temporizadores, cronómetros, la lista de la compra, el volumen, la hora y la fecha (también la hora de otras ciudades), cuentas y conversiones de unidades.

Cómo es Dina: {personality}

Te doy una persona y una ficha: pide algo y, de paso, comenta algo personal. Dina ya confirma la petición con su frase de siempre (esa no la escribes). Escribe {n} variantes distintas, cada una en una línea con este formato:
lo que dice la persona || coletilla de Dina
- La persona pide exactamente lo de la ficha (los mismos datos, ni uno más ni uno menos) y menciona su comentario con sus palabras, como se habla.
- La coletilla es un comentario breve y cálido sobre lo que ha contado (máximo 12 palabras): sin números ni horas, sin repetir la petición y sin decir que la ha hecho.
- Sin comillas, sin numerar y sin explicaciones."""

JUDGE_REPLY_SYSTEM = """Valoras respuestas de Dina, la asistente de voz de un móvil que funciona sin internet y sabe de alarmas, temporizadores, cronómetros, la lista de la compra, el volumen, la hora y la fecha (también la hora de otras ciudades), cuentas y conversiones de unidades; también cuenta chistes y curiosidades. Te doy lo que dijo la persona y lo que contestó Dina (si Dina hizo algo, el sistema ya lo confirmó aparte; esto es solo su comentario). Para cada una responde una línea «número: nota»:
3 = natural, cálida y breve; suena a una buena respuesta hablada;
2 = aceptable, aunque algo sosa, rara o larga;
1 = fuera de lugar, empalagosa, repetitiva o que nadie diría; o saca sin que venga a cuento que no tiene cuerpo, internet o memoria;
X = afirma algo que Dina no sabe o no ha hecho: que ha hecho algo, horas, fechas, cifras o datos del mundo (tiempo, noticias, resultados); o se atribuye capacidades que no tiene (agenda, correo, música, mensajes, buscar en internet) o promete cosas que no puede hacer."""

JUDGE_SYSTEM = """Valoras frases que una persona le dice en voz alta a la asistente de voz de su móvil. Para cada una, puntúa de 1 a 3 si suena a algo que alguien diría de verdad hablando en español:
3 = natural, alguien lo diría así;
2 = posible, aunque algo rara o escrita;
1 = artificial, incoherente o que nadie diría así.
No valores si la petición tiene sentido ni la ortografía. Responde solo una línea por frase: «número: nota»."""


# ---------------------------------------------------------------------------------------------
# Files
# ---------------------------------------------------------------------------------------------

def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def append_jsonl(path: Path, rows: list[dict]) -> None:
    if rows:
        with path.open("a", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def h(*parts) -> int:
    return zlib.crc32("|".join(map(str, parts)).encode("utf-8"))


class Batch:
    def __init__(self, name: str):
        self.name = name
        self.dir = DATA / name
        self.dir.mkdir(parents=True, exist_ok=True)
        personas = json.loads((HERE / "personas.json").read_text(encoding="utf-8"))["personas"]
        self.personas = {p["id"]: p for p in personas}
        self.train_personas = [p for p in personas if not p.get("reserved")]
        self.dev_personas = [p for p in personas if p.get("reserved")]

    def path(self, name: str) -> Path:
        return self.dir / name

    def fichas(self) -> dict[str, dict]:
        return {f["id"]: f for f in read_jsonl(self.path("fichas.jsonl"))}


def kotlin(step: str, batch: Batch, **options) -> None:
    args = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "dev.ps1"), "data", step, f"batch={batch.name}"]
    args += [f"{k}={v}" for k, v in options.items()]
    # output to a file, not a pipe: a Gradle daemon started by this call inherits the handle and a pipe would never close
    with tempfile.TemporaryFile() as log:
        code = subprocess.run(args, stdout=log, stderr=subprocess.STDOUT).returncode
        log.seek(0)
        output = log.read().decode("utf-8", errors="replace")
    lines = [line for line in output.splitlines() if line.strip()]
    if code != 0 or not lines or not lines[-1].startswith("OK"):
        sys.exit(f"Falló el paso Kotlin {step}:\n" + "\n".join(lines[-15:]))
    print(f"  {lines[-1]}")


def clean_lines(text: str, limit: int = 200) -> list[str]:
    out = []
    for line in text.splitlines():
        line = re.sub(r"^\s*(?:[-*•]|\d+[.)]|variante \d+:)\s*", "", line).strip().strip("\"«»“”").strip()
        if line and not line.endswith(":") and len(line) < limit and line.lower() not in (o.lower() for o in out):
            out.append(line)
    return out


def parse_blocks(text: str) -> dict[int, str]:
    """Round-trip answer → actions by phrase number. Headers «### 1», «1», «1.» or «1:» (actions may follow on the same line);
    `(?)` for a missing required value is the contract's `()`."""
    blocks: dict[int, list[str]] = {}
    current = None
    for line in re.sub(r"```\w*", "", text).splitlines():
        line = line.strip()
        m = re.match(r"^(?:#+\s*)?(\d+)\s*[.:)]?\s*(.*)$", line)
        if m and (not m.group(2) or "(" in m.group(2)):  # actions never start with a digit
            current = int(m.group(1))
            blocks[current] = [m.group(2)] if m.group(2) else []
        elif current is not None and line and not line.startswith("#"):  # an echoed «## Situación 2» is not an action
            blocks[current].append(line)
    def drop_missing(actions: str) -> str:  # a bare positional `?` is the contract's missing value: left out
        actions = re.sub(r'calc\("[^"\n]*\?[^"\n]*"\)', "calc()", actions)  # «39 / ?»: the operation is missing a number
        actions = re.sub(r"\(\s*\?\s*\)", "()", actions)
        return re.sub(r",\s*\?\s*(?=[,)])", "", re.sub(r"\(\s*\?\s*,\s*", "(", actions))
    return {n: drop_missing("\n".join(lines)).strip() for n, lines in blocks.items()}


def split_reply(line: str) -> tuple[str, str] | None:
    """«frase || respuesta» → (frase, respuesta); None when the line has no reply."""
    if "||" not in line:
        return None
    phrase, reply = (part.strip() for part in line.split("||", 1))
    phrase = phrase.strip("\"«»“”").strip()
    if reply[:1] in "\"«“" and reply[-1:] in "\"»”":
        reply = reply[1:-1].strip()
    reply = reply.replace('"', "'")
    return (phrase, reply) if phrase and reply and "||" not in reply else None


def answer_of(ficha: dict, turn: int, replies: dict) -> str:
    """What Dina answered in [turn] (1-based) of the conversation of variant 0: the generated reply, else the engine's."""
    t = ficha["turns"][turn - 1]
    reply = replies.get((ficha["id"], turn))
    if reply and t.get("reply") == "charla":
        return reply
    return t["answer"] + (f" {reply}" if reply else "")


# ---------------------------------------------------------------------------------------------
# Helper agents: each in-NN.txt is read by one agent, which writes out-NN.txt (first line «modelo: <name>»);
# ids-NN.json says what each number is, reparto.txt which model must do the file
# ---------------------------------------------------------------------------------------------

def load_agents() -> dict:
    return json.loads((HERE / "agents.json").read_text(encoding="utf-8"))


def writer_shares(batch: Batch, spec: str | None = None) -> dict[str, float]:
    """Writers of a batch: `--writers a=0.5,b` on its first export (kept in writers.json; a bare name gets an equal share),
    else agents.json's `write`."""
    path = batch.path("writers.json")
    if spec and not path.exists():
        models = load_agents()["models"]
        shares = {}
        for part in filter(None, (p.strip() for p in spec.split(","))):
            name, _, share = part.partition("=")
            if name not in models:
                sys.exit(f"Escritor desconocido: {name} (agents.json: {', '.join(models)})")
            shares[name] = float(share) if share else 1.0
        path.write_text(json.dumps(shares), encoding="utf-8")
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else load_agents()["write"]


def pick_writer(sid: str, shares: dict[str, float], counts: collections.Counter) -> str:
    """The writer furthest below its share (ties by hash), so shares hold for any number of scenarios."""
    model = min(shares, key=lambda m: (counts[m] / shares[m], h(sid, m)))
    counts[model] += 1
    return model


def pick_helper(seed: str, tiers: list[list[str]], counts: collections.Counter, weight: int = 1) -> str | None:
    """The least loaded model of the first non-empty tier (ties by hash); None when every tier is empty."""
    for tier in tiers:
        if tier:
            model = min(tier, key=lambda m: (counts[m], h(seed, m)))
            counts[model] += weight
            return model
    return None


def helper_model(text: str, default: str) -> tuple[str, str]:
    """(name, family) of the helper agent that wrote an out-NN.txt: its first line «modelo: <name>», else [default]."""
    m = re.match(r"\s*modelo:\s*([\w.-]+)", text)
    name = m.group(1).lower() if m else default
    return name, name.split("-")[0]


def reparto(folder: Path) -> dict[str, list[str]]:
    """File number → [model, effort, runner] from reparto.txt («in-NN.txt modelo esfuerzo quién»)."""
    path = folder / "reparto.txt"
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    return {parts[0][3:-4]: parts[1:] for parts in map(str.split, lines) if len(parts) >= 2 and parts[0].startswith("in-")}


def taken_ids(folder: Path) -> set:
    """Ids handed to agents and not merged yet; what a merged answer left out goes back to the queue."""
    return {tuple(i) if isinstance(i, list) else i for f in folder.glob("ids-*.json") if not (folder / f"out-{f.stem[4:]}.merged").exists()
            for i in json.loads(f.read_text(encoding="utf-8"))}


def write_helper_file(folder: Path, text: str, ids: list, model: str | None, what: str) -> None:
    folder.mkdir(exist_ok=True)
    name = f"{len(list(folder.glob('in-*.txt'))):02d}"
    (folder / f"in-{name}.txt").write_text(text, encoding="utf-8")
    (folder / f"ids-{name}.json").write_text(json.dumps(ids), encoding="utf-8")
    if model:
        spec = load_agents()["models"][model]
        with (folder / "reparto.txt").open("a", encoding="utf-8") as f:
            f.write(f"in-{name}.txt {model} {spec['effort']} {spec['runner']}\n")
    print(f"  {folder.name}/in-{name}.txt: {what}" + (f" → {model}" if model else ""))


def helper_answers(folder: Path):
    """(ids, text, model, family) of each answered file not merged yet, marked merged once the caller has used it. An answer
    from another model than the one reparto.txt asks for is left out, to be redone."""
    expected = reparto(folder)
    for out in sorted(folder.glob("out-*.txt")) if folder.exists() else []:
        marker = out.with_suffix(".merged")
        ids_path = folder / ("ids-" + out.stem[4:] + ".json")
        if marker.exists() or not ids_path.exists():
            continue
        text = out.read_text(encoding="utf-8")
        want = expected.get(out.stem[4:], [None])[0]
        name, family = helper_model(text, want or "claude-sonnet")
        if want and name != want:
            print(f"  aviso: {out.name} es de {name} y el reparto pide {want}: no se usa (bórralo y que lo rehaga {want})")
            continue
        yield json.loads(ids_path.read_text(encoding="utf-8")), re.sub(r"^\s*modelo:.*\n", "", text), name, family
        marker.write_text("ok", encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------------------------

def step_scenarios(batch: Batch, args) -> None:
    if batch.path("fichas.jsonl").exists() and not args.force:
        print("  fichas.jsonl ya existe (usa --force para regenerarlas; cambia las que ya tienen frases)")
        return
    # agents never write the development set: it stays the one written by a family that writes no training phrases
    kotlin("scenarios", batch, n=args.n, seed=args.seed, dev=0, mix=args.mix)


def step_generate(batch: Batch, args) -> None:
    merge_gen_helpers(batch)
    print(f"  frases: {len(read_jsonl(batch.path('phrases.jsonl')))} (las escriben los agentes: paso next)")


GEN_HELPERS = "gen-ayuda"
GEN_FORMAT = """
Te paso varias fichas numeradas, cada una con su persona. Para cada ficha escribe su número y debajo sus {n} variantes, una por línea, así:
### 1
variante
variante
### 2
...
Nada más: sin comentarios ni explicaciones."""


def step_export_gen(batch: Batch, args) -> int:
    """Hands phrase writing to the writers of agents.json, by share: the next pending turn of every train scenario,
    grouped by writer and kind of ficha, in gen-ayuda/in-NN.txt; they write out-NN.txt. Run it again after merging to
    export the following turns."""
    merge_gen_helpers(batch)
    fichas = batch.fichas()
    folder = batch.path(GEN_HELPERS)
    phrases = read_jsonl(batch.path("phrases.jsonl"))
    have = {(p["scenario"], p["turn"]) for p in phrases}
    first_variant = {(p["scenario"], p["turn"]): p["text"] for p in phrases if p["variant"] == 0}
    first_reply = {(p["scenario"], p["turn"]): p["reply"] for p in phrases if p["variant"] == 0 and "reply" in p}
    taken = taken_ids(folder)
    assign_path = batch.path("assign.json")
    assign = json.loads(assign_path.read_text(encoding="utf-8")) if assign_path.exists() else {}
    agents = load_agents()
    shares = writer_shares(batch, getattr(args, "writers", None))
    counts = collections.Counter(a.get("writer") for a in assign.values())
    groups = collections.defaultdict(list)
    for sid, ficha in fichas.items():
        if ficha["split"] != "train":
            continue
        turn = next((k for k in range(1, len(ficha["turns"]) + 1) if (sid, k) not in have), None)
        if turn is None or (sid, turn) in taken or (turn > 1 and (sid, turn - 1) not in first_variant):
            continue
        if sid not in assign:
            persona = batch.train_personas[h(sid, "persona") % len(batch.train_personas)]
            writer = pick_writer(sid, shares, counts)
            assign[sid] = {"split": "train", "family": agents["models"][writer]["family"], "writer": writer, "persona": persona["id"]}
        persona = batch.personas[assign[sid]["persona"]]
        history = "".join(f"Persona: {first_variant[(sid, k)]}\nDina: {answer_of(ficha, k, first_reply)}\n" for k in range(1, turn))
        t = ficha["turns"][turn - 1]
        text = f"Persona: {persona['text']}\n" + (f"Conversación hasta ahora:\n{history}" if history else "") + f"Ficha: {t['ficha']}"
        groups[(assign[sid].get("writer") or "", t.get("reply") or "")].append(((sid, turn), text))
    assign_path.write_text(json.dumps(assign, ensure_ascii=False, indent=0), encoding="utf-8")
    files = 0
    for (writer, kind), items in sorted(groups.items()):
        system = {"charla": CHAT_SYSTEM, "coletilla": COLETILLA_SYSTEM}.get(kind, GENERATE_SYSTEM).format(n=args.variants, personality=PERSONALITY)
        for start in range(0, len(items), args.per):
            chunk = items[start:start + args.per]
            body = "\n\n".join(f"### {i}\n{text}" for i, (_, text) in enumerate(chunk, 1))
            write_helper_file(folder, system + "\n" + GEN_FORMAT.format(n=args.variants) + "\n\n" + body + "\n", [key for key, _ in chunk],
                              writer or None, f"{len(chunk)} fichas ({kind or 'peticiones'})")
            files += 1
    if not files:
        print("  nada pendiente que exportar")
    return files


def merge_gen_helpers(batch: Batch) -> None:
    """Phrases of helper agents (gen-ayuda/out-NN.txt) into phrases.jsonl; the writer is named in the file's first
    line («modelo: luna-6»)."""
    folder = batch.path(GEN_HELPERS)
    if not folder.exists():
        return
    path = batch.path("phrases.jsonl")
    assign = json.loads(batch.path("assign.json").read_text(encoding="utf-8"))
    fichas = batch.fichas()
    for keys, text, provider, family in helper_answers(folder):
        keys = [tuple(k) for k in keys]
        blocks: dict[int, list[str]] = {}
        current = None
        for line in text.splitlines():
            m = re.match(r"^\s*#+\s*(\d+)\s*$", line)
            if m:
                current = int(m.group(1))
                blocks[current] = []
            elif current is not None and line.strip():
                blocks[current].append(line)
        have = {(p["scenario"], p["turn"]) for p in read_jsonl(path)}
        new = []
        for n, (sid, turn) in enumerate(keys, 1):
            if (sid, turn) in have or n not in blocks:
                continue
            kind = fichas[sid]["turns"][turn - 1].get("reply")
            lines = clean_lines("\n".join(blocks[n]), limit=400 if kind else 200)
            pairs = [pair for pair in map(split_reply, lines) if pair] if kind else [(line, None) for line in lines]
            for v, (phrase, reply) in enumerate(pairs[:6]):
                row = {"id": f"{sid}/{turn}/{v}", "scenario": sid, "turn": turn, "variant": v, "text": phrase,
                       "persona": assign[sid]["persona"], "provider": provider, "family": family, "split": "train"}
                if reply:
                    row["reply"] = reply
                new.append(row)
        append_jsonl(path, new)
        print(f"  {provider}: {len(new)} frases")


def roundtrip_packs(batch: Batch, done: set[str]) -> tuple[list, dict, dict]:
    """Pending phrases in packs of situations (up to ROUNDTRIP_PACK phrases, same writer family): the contract goes once per call."""
    phrases = read_jsonl(batch.path("phrases.jsonl"))
    first_variant = {(p["scenario"], p["turn"]): p["text"] for p in phrases if p["variant"] == 0}
    first_reply = {(p["scenario"], p["turn"]): p["reply"] for p in phrases if p["variant"] == 0 and "reply" in p}
    groups = collections.defaultdict(list)
    for p in phrases:
        if p["id"] not in done:
            groups[(p["scenario"], p["turn"])].append(p)
    packs: list[list[tuple[str, int, list[dict]]]] = []
    for (sid, turn), group in sorted(groups.items(), key=lambda kv: kv[1][0]["family"]):  # same writer family together: full packs
        last = packs[-1] if packs else None
        if last and last[0][2][0]["family"] == group[0]["family"] and sum(len(g) for _, _, g in last) + len(group) <= ROUNDTRIP_PACK:
            last.append((sid, turn, group))
        else:
            packs.append([(sid, turn, group)])
    return packs, first_variant, first_reply


def roundtrip_text(fichas: dict, pack: list, first_variant: dict, first_reply: dict, start: int = 0) -> tuple[str, list[dict], list[str]]:
    """The situations of a pack as the interpreter reads them: text, phrases in their numbering (from start + 1), canonical answers."""
    sections, numbered, answers = [], [], []
    for k, (sid, turn, group) in enumerate(pack, 1):
        ficha = fichas[sid]
        t = ficha["turns"][turn - 1]
        before = ""
        if turn > 1:
            before = f"[antes]\nusuario: {first_variant.get((sid, turn - 1), '')}\ndina: {answer_of(ficha, turn - 1, first_reply)}\n"
        lines = ""
        for p in group:
            numbered.append(p)
            lines += f"{start + len(numbered)}. {p['text']}\n"
        answers += [t["actions"]] * len(group)
        sections.append((f"## Situación {k}\n" if len(pack) > 1 else "") + f"[estado]\n{t['state']}\n{before}[frases]\n{lines}")
    return "\n".join(sections), numbered, answers


def done_ids(batch: Batch) -> set[str]:
    merge_helpers(batch)
    return {r["id"] for r in read_jsonl(batch.path("roundtrip.jsonl"))}


def step_roundtrip(batch: Batch, args) -> None:
    merge_helpers(batch)
    print(f"  ida y vuelta: {len(read_jsonl(batch.path('roundtrip.jsonl')))} (la hacen los agentes: paso next)")


HELPERS = "rt-ayuda"


def step_export_rt(batch: Batch, args) -> int:
    """Hands round-trip checking to helper agents: rt-ayuda/in-NN.txt to read, out-NN.txt to write in the «### n» format.
    Each situation goes to a checker of agents.json of another family than its writer."""
    fichas = batch.fichas()
    system = ROUNDTRIP_SYSTEM.format(contract=batch.path("contract.txt").read_text(encoding="utf-8"))
    folder = batch.path(HELPERS)
    packs, first_variant, first_reply = roundtrip_packs(batch, done_ids(batch) | taken_ids(folder))
    situations = [item for pack in packs for item in pack]
    groups = collections.defaultdict(list)
    agents, counts = load_agents(), collections.Counter()
    for item in situations:
        writer = item[2][0]["family"]
        model = pick_helper(item[0], [[m for m in agents["check"] if agents["models"][m]["family"] != writer]], counts, len(item[2]))
        groups[model].append(item)
    groups.pop(None, None)
    files = 0
    for model, items in sorted(groups.items()):
        for start in range(0, len(items), args.per):
            chunk = items[start:start + args.per]
            text, numbered, _ = roundtrip_text(fichas, chunk, first_variant, first_reply)
            write_helper_file(folder, system + "\n\n" + text, [p["id"] for p in numbered], model, f"{len(chunk)} situaciones, {len(numbered)} frases")
            files += 1
    return files


def merge_helpers(batch: Batch) -> None:
    """Answers of helper agents (rt-ayuda/out-NN.txt) into roundtrip.jsonl; the pipeline is the only writer of that file.
    A phrase the answer left out goes back to the queue."""
    path = batch.path("roundtrip.jsonl")
    for ids, text, name, family in helper_answers(batch.path(HELPERS)):
        done = {r["id"] for r in read_jsonl(path)}
        blocks = parse_blocks(text)
        phrase = {p["id"]: p for p in read_jsonl(batch.path("phrases.jsonl"))}
        append_jsonl(path, [{"id": i, "scenario": phrase[i]["scenario"], "turn": phrase[i]["turn"], "variant": phrase[i]["variant"],
                             "actions": blocks[n], "interpreter": name, "family": family}
                            for n, i in enumerate(ids, 1) if i not in done and n in blocks])


def step_verify(batch: Batch, args) -> None:
    merge_helpers(batch)
    kotlin("verify", batch)


def step_judge(batch: Batch, args) -> None:
    merge_judge_helpers(batch)
    merge_reply_helpers(batch)
    ok = {v["id"] for v in read_jsonl(batch.path("verified.jsonl")) if v["ok"]}
    replies = [p for p in read_jsonl(batch.path("phrases.jsonl")) if p["id"] in ok and "reply" in p]
    print(f"  juez: {len(read_jsonl(batch.path('judge.jsonl')))} frases puntuadas de {len(ok)} que pasan la ida y vuelta")
    if replies:
        print(f"  juez de respuestas de Dina: {len(read_jsonl(batch.path('judge-reply.jsonl')))} de {len(replies)}")


JUDGE_HELPERS = "juez-ayuda"


def step_export_judge(batch: Batch, args) -> int:
    """Hands naturalness judging to helper agents: juez-ayuda/in-NN.txt to read, out-NN.txt with «n: nota» lines. Each
    phrase goes to a judge of agents.json of neither the writer's nor the interpreter's family (else at least not the
    writer's family nor the interpreter itself)."""
    ok = {v["id"] for v in read_jsonl(batch.path("verified.jsonl")) if v["ok"]}
    interpreter = {r["id"]: r for r in read_jsonl(batch.path("roundtrip.jsonl"))}
    folder = batch.path(JUDGE_HELPERS)
    merge_judge_helpers(batch)
    done = {r["id"] for r in read_jsonl(batch.path("judge.jsonl"))} | taken_ids(folder)
    todo = [p for p in read_jsonl(batch.path("phrases.jsonl")) if p["id"] in ok and p["id"] not in done]
    groups = judge_groups(todo, interpreter)
    return export_lists(folder, groups, args.per, JUDGE_SYSTEM, lambda p: p["text"], "frases")


def judge_tiers(family: dict[str, str], writer: str, checker: dict | None) -> list[list[str]]:
    """Judges for an item: of neither the writer's nor the interpreter's family, else at least not the writer's family
    nor the interpreter itself."""
    checker = checker or {"family": None, "interpreter": None}
    return [[m for m in family if family[m] not in (writer, checker["family"])],
            [m for m in family if family[m] != writer and m != checker["interpreter"]]]


def judge_groups(items: list[dict], interpreter: dict) -> dict:
    """Items by judge model (judge_tiers, least loaded first); items without a valid judge are left out."""
    agents, counts = load_agents(), collections.Counter()
    family = {m: agents["models"][m]["family"] for m in agents["judge"]}
    groups = collections.defaultdict(list)
    for p in items:
        groups[pick_helper(p["id"], judge_tiers(family, p["family"], interpreter.get(p["id"])), counts)].append(p)
    groups.pop(None, None)
    return groups


def export_lists(folder: Path, groups: dict, per: int, system: str, line, what: str) -> int:
    """Numbered lists for judges, per model, in files of up to [per] items."""
    files = 0
    for model, items in sorted(groups.items()):
        for start in range(0, len(items), per):
            chunk = items[start:start + per]
            write_helper_file(folder, system + "\n\n" + "".join(f"{i}. {line(p)}\n" for i, p in enumerate(chunk, 1)), [p["id"] for p in chunk],
                              model, f"{len(chunk)} {what}")
            files += 1
    return files


def merge_scores(batch: Batch, folder: str, path: str, marks: str) -> None:
    """«n: nota» answers of judge agents into [path]; X (fact claims, only in [marks]) scores 0."""
    path = batch.path(path)
    for ids, text, name, _ in helper_answers(batch.path(folder)):
        done = {r["id"] for r in read_jsonl(path)}
        scores = {int(n): (0 if v.upper() == "X" else int(v)) for n, v in re.findall(rf"^\s*(\d+)\s*[:.)-]\s*([{marks}])\b", text, flags=re.M)}
        append_jsonl(path, [{"id": i, "score": scores[n], "judge": name} for n, i in enumerate(ids, 1) if n in scores and i not in done])


def merge_judge_helpers(batch: Batch) -> None:
    merge_scores(batch, JUDGE_HELPERS, "judge.jsonl", "123")


REPLY_HELPERS = "juez-respuestas-ayuda"


def step_export_judge_reply(batch: Batch, args) -> int:
    """Hands the judging of Dina's generated answers to helper agents: juez-respuestas-ayuda/in-NN.txt to read, out-NN.txt
    with «n: nota» lines (1-3 or X), each to a judge of agents.json chosen like the phrase judge (judge_tiers)."""
    ok = {v["id"] for v in read_jsonl(batch.path("verified.jsonl")) if v["ok"]}
    folder = batch.path(REPLY_HELPERS)
    merge_reply_helpers(batch)
    done = {r["id"] for r in read_jsonl(batch.path("judge-reply.jsonl"))} | taken_ids(folder)
    todo = [p for p in read_jsonl(batch.path("phrases.jsonl")) if p["id"] in ok and "reply" in p and p["id"] not in done]
    groups = judge_groups(todo, {r["id"]: r for r in read_jsonl(batch.path("roundtrip.jsonl"))})
    return export_lists(folder, groups, args.per, JUDGE_REPLY_SYSTEM, lambda p: f"Persona: {p['text']}\n   Dina: {p['reply']}", "respuestas")


def merge_reply_helpers(batch: Batch) -> None:
    merge_scores(batch, REPLY_HELPERS, "judge-reply.jsonl", "123xX")


def pending_files(batch: Batch) -> dict[tuple[str, str, str], list[str]]:
    """Agent files without a merged answer: (runner, model, effort) → their in-NN.txt."""
    todo = collections.defaultdict(list)
    for name in (GEN_HELPERS, HELPERS, JUDGE_HELPERS, REPLY_HELPERS):
        folder = batch.path(name)
        plan = reparto(folder)
        for f in sorted(folder.glob("in-*.txt")):
            n = f.stem[3:]
            if not (folder / f"out-{n}.merged").exists():
                model, effort, runner = (plan.get(n, []) + ["?"] * 3)[:3]
                todo[(runner, model, effort)].append(f.relative_to(ROOT).as_posix())
    return todo


def step_next(batch: Batch, args) -> None:
    """Merges every answer, exports what is due (the next turns once the writers are done, the round trip of whatever is
    written; when all is written and checked, verify and the judges) and lists the files to do per model. Writers and
    checkers do not wait for each other. Run it again when they are written; when nothing is left, `run` finishes the batch."""
    if not batch.path("fichas.jsonl").exists():
        step_scenarios(batch, args)
    for merge in (merge_gen_helpers, merge_helpers, merge_judge_helpers, merge_reply_helpers):
        merge(batch)
    per = load_agents()["per"]

    def with_per(kind: str):
        return argparse.Namespace(**{**vars(args), "per": per[kind]})

    def busy(name: str) -> bool:
        folder = batch.path(name)
        return any(not (folder / f"out-{f.stem[3:]}.merged").exists() for f in folder.glob("in-*.txt"))

    writing = busy(GEN_HELPERS) or step_export_gen(batch, with_per("gen"))
    checking = step_export_rt(batch, with_per("rt")) or busy(HELPERS)
    if not writing and not checking and not busy(JUDGE_HELPERS) and not busy(REPLY_HELPERS):
        rt, verified = batch.path("roundtrip.jsonl"), batch.path("verified.jsonl")
        if not verified.exists() or verified.stat().st_mtime < rt.stat().st_mtime:
            step_verify(batch, args)
        step_export_judge(batch, with_per("judge"))
        step_export_judge_reply(batch, with_per("reply"))
    todo = pending_files(batch)
    if not todo:
        print(f"  listo: python scripts/data/pipeline.py run --batch {batch.name}")
        return
    print(f"  por hacer: {sum(map(len, todo.values()))} archivos (cada uno: leer in-NN.txt, escribir out-NN.txt con primera línea «modelo: <modelo>»)")
    for (runner, model, effort), files in sorted(todo.items()):
        print(f"  [{runner}] {model} ({effort}): {len(files)} archivos")
        for f in files:
            print(f"    {f}")


def _utterances(turns: list) -> list[str]:
    """Every `u` of an RW2-format episode, branches (`x` options and their `then`) included."""
    out = []
    for t in turns:
        out.append(t.get("u", ""))
        for option in t.get("x", []):
            out += _utterances(option.get("then", []))
        out += _utterances(t.get("then", []))
    return out


def benchmark_phrases() -> list[str]:
    """RW2 (v2.1, conversions included), RW200 and Dina-Real utterances, read locally and only for this filter. Never printed or sent."""
    out = []
    for path in sorted((ROOT / "benchmark" / "realworld_v2" / "episodes").glob("*.jsonl")):
        out += [u for line in read_jsonl(path) for u in _utterances(line.get("turns", []))]
    for path in sorted((ROOT / "benchmark" / "realworld200" / "episodes").glob("*.jsonl")):
        out += [t.get("user", "") for line in read_jsonl(path) for t in line.get("turns", [])]
    blind = ROOT / "benchmark" / "dina_real" / "frases.tsv"
    if blind.exists():
        with blind.open(encoding="utf-8", newline="") as f:
            out += [row.get("frase") or "" for row in csv.DictReader(f, delimiter="\t")]
    return [p for p in out if p and p.strip()]


def step_contamination(batch: Batch, args) -> None:
    phrases = read_jsonl(batch.path("phrases.jsonl"))
    grams: set[tuple[str, ...]] = set()
    index = tx.MinHashIndex()
    for p in benchmark_phrases():
        grams |= tx.ngrams(p)
        index.add(p)
    rows = [{"id": p["id"], "ngram": bool(tx.ngrams(p["text"]) & grams), "near": bool(index.near(p["text"]))} for p in phrases]
    write_jsonl(batch.path("contamination.jsonl"), rows)
    print(f"  contaminación: {sum(r['ngram'] for r in rows)} con un 8-grama de los benchmarks, {sum(r['near'] for r in rows)} casi iguales (de {len(rows)})")


def step_select(batch: Batch, args) -> None:
    fichas = batch.fichas()
    phrases = {p["id"]: p for p in read_jsonl(batch.path("phrases.jsonl"))}
    verified = {v["id"]: v["ok"] for v in read_jsonl(batch.path("verified.jsonl"))}
    judged = {j["id"]: j["score"] for j in read_jsonl(batch.path("judge.jsonl"))}
    judged_reply = {j["id"]: j["score"] for j in read_jsonl(batch.path("judge-reply.jsonl"))}
    dirty = {c["id"] for c in read_jsonl(batch.path("contamination.jsonl")) if c["ngram"] or c["near"]}
    reasons = collections.Counter()

    def usable(pid: str, ficha_text: str) -> bool:
        p = phrases.get(pid)
        why = ("sin frase" if p is None else "ida y vuelta" if not verified.get(pid) else "juez" if judged.get(pid, 0) < MIN_JUDGE
               else "respuesta de Dina" if "reply" in p and judged_reply.get(pid, 0) < MIN_JUDGE
               else "contaminada" if pid in dirty else "larga" if len(p["text"].split()) > 30 else "copia la ficha" if tx.ngrams(p["text"], 6) & tx.ngrams(ficha_text, 6) else None)
        if why:
            reasons[why] += 1
        return why is None

    chains = []
    replies_of = {}
    for sid, ficha in fichas.items():
        for v in range(args.variants):
            texts = []
            for turn, t in enumerate(ficha["turns"], 1):
                pid = f"{sid}/{turn}/{v}"
                if not usable(pid, t["ficha"]):
                    break
                texts.append(phrases[pid]["text"])
            if texts:
                chains.append((sid, v, texts))
                replies_of[(sid, v)] = [phrases[f"{sid}/{turn}/{v}"].get("reply") for turn in range(1, len(texts) + 1)]
    random.Random(args.seed).shuffle(chains)
    total_turns = sum(len(c[2]) for c in chains)
    cap_first = max(3, int(0.05 * total_turns))
    first_two = collections.Counter()
    indexes: dict[str, tx.MinHashIndex] = {}
    group_size = collections.Counter()
    selected = []
    for sid, v, texts in chains:
        ficha = fichas[sid]
        kept = []
        for text in texts:
            key = " ".join(tx.words(text)[:2])
            cell = f"{ficha['phenomenon']}|{ficha['domain']}"
            index = indexes.setdefault(cell, tx.MinHashIndex())
            near = index.near(text)
            group = f"{cell}|{min(near)}" if near else None
            if first_two[key] >= cap_first:
                reasons["arranque repetido"] += 1
                break
            if group and group_size[group] >= 3:
                reasons["casi duplicada"] += 1
                break
            first_two[key] += 1
            if group:
                group_size[group] += 1
            else:
                index.add(text)
            kept.append(text)
        if kept:
            noisy = h(sid, v, "noise") % 1000 < NOISE_SHARE * 1000
            heard = [tx.noisy(t, f"{sid}/{v}/{i}") for i, t in enumerate(kept)] if noisy else kept
            selected.append({"scenario": sid, "variant": v, "texts": heard, "clean": kept, "noisy": noisy, "split": ficha["split"],
                             "replies": replies_of[(sid, v)][: len(kept)]})
    selected.sort(key=lambda r: (r["scenario"], r["variant"]))
    write_jsonl(batch.path("selected.jsonl"), selected)
    batch.path("select-reasons.json").write_text(json.dumps(reasons, ensure_ascii=False), encoding="utf-8")
    print(f"  selección: {len(selected)} conversaciones, {sum(len(s['texts']) for s in selected)} turnos")


def step_render(batch: Batch, args) -> None:
    kotlin("render", batch)


# ---------------------------------------------------------------------------------------------
# One-page report
# ---------------------------------------------------------------------------------------------

def pct(a: int, b: int) -> str:
    return f"{100 * a / b:.0f} %" if b else "–"


def step_report(batch: Batch, args) -> None:
    from pipeline_targets import for_batch  # noqa: WPS433 (small table kept apart)

    fichas = batch.fichas()
    targets = for_batch({f["phenomenon"] for f in fichas.values()})
    phrases = read_jsonl(batch.path("phrases.jsonl"))
    verified = read_jsonl(batch.path("verified.jsonl"))
    judged = read_jsonl(batch.path("judge.jsonl"))
    contamination = read_jsonl(batch.path("contamination.jsonl"))
    selected = read_jsonl(batch.path("selected.jsonl"))
    train = read_jsonl(batch.path("train.jsonl"))
    dev = read_jsonl(batch.path("dev.jsonl"))
    reasons = json.loads(batch.path("select-reasons.json").read_text(encoding="utf-8")) if batch.path("select-reasons.json").exists() else {}
    family_of = {p["id"]: p["family"] for p in phrases}
    ok_ids = {v["id"] for v in verified if v["ok"]}
    selected_turns = [(s, i) for s in selected for i in range(len(s["texts"]))]

    out = [f"# Lote {batch.name}", ""]
    out.append(f"{len(fichas)} escenarios ({sum(f['split'] == 'dev' for f in fichas.values())} de desarrollo) · {len(phrases)} frases · "
               f"{len(ok_ids)} pasan la ida y vuelta ({pct(len(ok_ids), len(verified))}) · **{len(train)} ejemplos de entrenamiento y {len(dev)} de desarrollo**.")
    out += ["", "| Fenómeno | Objetivo | Escenarios | Pasan ida y vuelta | Turnos elegidos |", "|---|---:|---:|---:|---:|"]
    by_phen = collections.Counter(f["phenomenon"] for f in fichas.values())
    phen_of = {p["id"]: fichas[p["scenario"]]["phenomenon"] for p in phrases}
    ver_by = collections.Counter(phen_of[v["id"]] for v in verified)
    ok_by = collections.Counter(phen_of[i] for i in ok_ids)
    sel_by = collections.Counter(fichas[s["scenario"]]["phenomenon"] for s, _ in selected_turns)
    for name, target in targets:
        out.append(f"| {name} | {target} % | {pct(by_phen[name], len(fichas))} | {pct(ok_by[name], ver_by[name])} | {sel_by[name]} |")
    fam = collections.Counter(family_of[f"{s['scenario']}/{i + 1}/{s['variant']}"] for s, i in selected_turns)
    gen_ok = collections.Counter(family_of[i] for i in ok_ids)
    gen_all = collections.Counter(family_of[v["id"]] for v in verified)
    out += ["", "| Escritor | Frases | Pasan ida y vuelta | Parte de lo elegido |", "|---|---:|---:|---:|"]
    for family in sorted(gen_all):
        share = fam[family] / max(1, sum(fam.values()))
        out.append(f"| {family} | {gen_all[family]} | {pct(gen_ok[family], gen_all[family])} | {100 * share:.0f} % |")
    why = collections.Counter(v["why"].split(":")[0] for v in verified if not v["ok"])
    scores = collections.Counter(j["score"] for j in judged)
    out += ["", f"- **Ida y vuelta, rechazos**: {', '.join(f'{k} {n}' for k, n in why.most_common(4)) or 'ninguno'}.",
            f"- **Juez** (1–3): {', '.join(f'{k}: {scores[k]}' for k in (1, 2, 3))}.",
            f"- **Contaminación** (8-gramas o casi iguales a RW2, RW200 y Dina-Real; solo recuento): "
            f"{sum(c['ngram'] for c in contamination)} y {sum(c['near'] for c in contamination)} de {len(contamination)} descartadas.",
            f"- **Selección, descartes**: {', '.join(f'{k} {n}' for k, n in collections.Counter(reasons).most_common(6)) or 'ninguno'}."]
    texts = [s["clean"][i] for s, i in selected_turns]
    first_two = collections.Counter(" ".join(tx.words(t)[:2]) for t in texts)
    top = ", ".join(f"«{k}» {pct(n, len(texts))}" for k, n in first_two.most_common(5))
    structures = collections.defaultdict(collections.Counter)
    for s, i in selected_turns:
        f = fichas[s["scenario"]]
        acts = f["turns"][i]["actions"]
        structures[f"{f['phenomenon']}|{f['domain']}"][" ; ".join(re.sub(r"\(.*", "", line) + "(" + ",".join(sorted(re.findall(r"(\w+)=", line))) + ")" for line in acts.splitlines())] += 1
    worst = max(((cell, c.most_common(1)[0][1] / sum(c.values())) for cell, c in structures.items() if sum(c.values()) >= 10), key=lambda x: x[1], default=("–", 0))
    personas = collections.Counter(f"{s['scenario']}" for s in selected)
    persona_use = collections.Counter(json.loads(batch.path("assign.json").read_text(encoding="utf-8"))[sid]["persona"] for sid in personas) if batch.path("assign.json").exists() else collections.Counter()
    latam = sum(n for pid, n in persona_use.items() if batch.personas[pid]["variant"] == "latam")
    chat_train = [r for r in train if r["completion"].startswith(("say(", "fun.")) or "\nsay(" in r["completion"]]
    replies_judged = collections.Counter(j["score"] for j in read_jsonl(batch.path("judge-reply.jsonl")))
    out += [f"- **Charla** (say o fun.*): {pct(len(chat_train), len(train))} de los ejemplos de entrenamiento (objetivo 8–10 %); "
            f"respuestas de Dina según el juez: 3: {replies_judged[3]}, 2: {replies_judged[2]}, 1: {replies_judged[1]}, afirma hechos: {replies_judged[0]}; "
            f"say filtrado por la app: {why.get('say filtrado', 0)}."]
    out += [f"- **Diversidad**: {len(set(texts))} frases distintas de {len(texts)}; dos primeras palabras más usadas: {top or '–'} (tope 5 %); "
            f"{sum(len(c) for c in structures.values())} secuencias de acciones distintas en {len(structures)} celdas (la más concentrada: {worst[0]} {100 * worst[1]:.0f} %).",
            f"- **Personas**: {len(persona_use)} usadas, {pct(latam, sum(persona_use.values()))} latinoamericanas; ruido de STT en {pct(sum(s['noisy'] for s in selected), len(selected))} de las conversaciones.",
            f"- **Multiturno**: {pct(sum(len(s['texts']) > 1 for s in selected), len(selected))} de las conversaciones elegidas."]
    batch.path("report.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"  informe: {batch.path('report.md').relative_to(ROOT)}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=STEPS + ["run", "next", "export_gen", "export_rt", "export_judge", "export_judge_reply"])
    parser.add_argument("--batch", required=True)
    parser.add_argument("--n", type=int, default=200, help="escenarios (paso scenarios)")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--mix", default="base", help="tipo de lote (paso scenarios): base, refuerzo o conversiones")
    parser.add_argument("--writers", help="escritores del lote, solo la primera vez (p. ej. claude-haiku-5.5,claude-sonnet-5.5); si no, los de agents.json")
    parser.add_argument("--variants", type=int, default=4, help="frases por ficha")
    parser.add_argument("--per", type=int, default=20, help="elementos por archivo de agente (pasos export_*; next usa los de agents.json)")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    batch = Batch(args.batch)
    for step in STEPS if args.step == "run" else [args.step]:
        print(f"[{step}]")
        globals()[f"step_{step}"](batch, args)


if __name__ == "__main__":
    main()
