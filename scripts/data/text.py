"""Spanish text helpers: normalization, n-grams, MinHash with LSH, and rule-based STT noise."""
from __future__ import annotations

import random
import re
import unicodedata
import zlib


def fold(text: str) -> str:
    """Lower case, no accents or punctuation (digits and ':' kept), single spaces."""
    text = unicodedata.normalize("NFD", text.lower())
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    text = re.sub(r"[^a-z0-9: ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def words(text: str) -> list[str]:
    return fold(text).split()


def ngrams(text: str, n: int = 8) -> set[tuple[str, ...]]:
    w = words(text)
    return {tuple(w[i:i + n]) for i in range(len(w) - n + 1)}


def shingles(text: str) -> set[str]:
    t = f" {fold(text)} "
    return {t[i:i + 3] for i in range(len(t) - 2)}


def jaccard(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


class MinHashIndex:
    """Near-duplicate search on character trigrams (Jaccard estimate, confirmed exactly)."""

    P = (1 << 61) - 1

    def __init__(self, bands: int = 16, rows: int = 4, seed: int = 1):
        rnd = random.Random(seed)
        self.bands, self.rows = bands, rows
        self.params = [(rnd.randrange(1, self.P), rnd.randrange(0, self.P)) for _ in range(bands * rows)]
        self.buckets: dict[tuple, list[int]] = {}
        self.sets: list[set[str]] = []

    def signature(self, items: set[str]) -> list[int]:
        hashes = [zlib.crc32(s.encode("utf-8")) for s in items] or [0]
        return [min((a * h + b) % self.P for h in hashes) for a, b in self.params]

    def add(self, text: str) -> int:
        items = shingles(text)
        index = len(self.sets)
        self.sets.append(items)
        sig = self.signature(items)
        for band in range(self.bands):
            self.buckets.setdefault((band, tuple(sig[band * self.rows:(band + 1) * self.rows])), []).append(index)
        return index

    def near(self, text: str, threshold: float = 0.7) -> list[int]:
        items = shingles(text)
        sig = self.signature(items)
        seen: set[int] = set()
        for band in range(self.bands):
            seen.update(self.buckets.get((band, tuple(sig[band * self.rows:(band + 1) * self.rows])), []))
        return [i for i in seen if jaccard(items, self.sets[i]) > threshold]


# ---------------------------------------------------------------------------------------------
# STT noise by rules (docs/datos.md): what Android's recognizer does to speech. Values never change.
# ---------------------------------------------------------------------------------------------

UNITS = ["cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
         "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós",
         "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"]
TENS = {30: "treinta", 40: "cuarenta", 50: "cincuenta", 60: "sesenta", 70: "setenta", 80: "ochenta", 90: "noventa"}


def number_words(n: int) -> str:
    if n < 30:
        return UNITS[n]
    if n < 100:
        return TENS[n - n % 10] + ("" if n % 10 == 0 else " y " + UNITS[n % 10])
    return "cien" if n == 100 else str(n)


WORD_NUMBERS = {fold(w): i for i, w in enumerate(UNITS)} | {v: k for k, v in TENS.items()} | {"cien": 100, "un": 1, "una": 1}


def _clock_words(match: re.Match) -> str:
    h, m = int(match.group(1)), int(match.group(2))
    hour = "una" if h in (1, 13) else number_words(h)
    if m == 0:
        return hour
    if m == 30:
        return f"{hour} y media"
    if m == 15:
        return f"{hour} y cuarto"
    return f"{hour} {number_words(m)}"


FILLERS = ["eh", "a ver", "oye", "pues", "mmm", "venga", "bueno"]
HOMOPHONES = [("a ver", "haber"), ("ahí", "hay"), ("has", "haz"), ("echa", "hecha"), ("hola", "ola"), ("vaya", "valla")]
SPLITS = [("ponme", "pon me"), ("apúntame", "apunta me"), ("quítala", "quita la"), ("quítalo", "quita lo"), ("dime", "di me"),
          ("despiértame", "despierta me"), ("avísame", "avisa me"), ("ponlo", "pon lo"), ("bórrala", "borra la")]


def noisy(text: str, seed: str) -> str:
    """One plausible recognizer transcript of [text]: lower case, no punctuation, numbers in the other form, fillers, cuts."""
    rnd = random.Random(zlib.crc32(seed.encode("utf-8")))
    t = text.lower()
    t = re.sub(r"^(oye |eh )?dina[,]?\s+", r"\1", t) if rnd.random() < 0.6 else t  # the wake word is eaten by the detector
    if rnd.random() < 0.5:
        t = re.sub(r"\b(\d{1,2}):(\d{2})\b", _clock_words, t)
        t = re.sub(r"\b\d{1,3}\b", lambda m: number_words(int(m.group())) if int(m.group()) <= 100 else m.group(), t)
    else:
        t = re.sub(r"\b(" + "|".join(TENS.values()) + r") y (" + "|".join(UNITS[1:10]) + r")\b",
                   lambda m: str(WORD_NUMBERS[m.group(1)] + WORD_NUMBERS[fold(m.group(2))]), t)
        t = re.sub(r"\b(" + "|".join(sorted((re.escape(w) for w in UNITS[2:] + list(TENS.values())), key=len, reverse=True)) + r")\b",
                   lambda m: str(WORD_NUMBERS.get(fold(m.group()), m.group())), t)
    t = re.sub(r"[¿?¡!.,;«»\"“”…]+", " ", t)
    if rnd.random() < 0.25:
        t = rnd.choice(FILLERS) + " " + t
    if rnd.random() < 0.2:
        t = re.sub(r"\bpara\b", lambda m: "pa" if rnd.random() < 0.6 else m.group(), t)
    if rnd.random() < 0.15:
        for a, b in HOMOPHONES:
            t = re.sub(rf"\b{a}\b", b, t)
    if rnd.random() < 0.12:
        for a, b in SPLITS:
            t = re.sub(rf"\b{a}\b", b, t)
    if rnd.random() < 0.05:
        first = t.split(" ", 1)[0]
        t = f"{first} {t}"
    return re.sub(r"\s+", " ", t).strip()
