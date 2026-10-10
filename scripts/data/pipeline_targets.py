"""Coverage targets of the matrix (docs/datos.md), same names as the phenomena of ScenarioGenerator.MIXES."""

TARGETS = {
    "base": [
        ("directas", 18), ("paráfrasis", 8), ("referencias", 12), ("falta dato", 7), ("pendiente", 7), ("correcciones", 10),
        ("multiacción", 9), ("interrupciones", 7), ("confirmaciones", 4), ("fuera de alcance", 4), ("errores", 2), ("charla", 12),
    ],
    "refuerzo": [
        ("volumen", 10), ("cuentas", 8), ("parar", 10), ("referencias", 14), ("edición", 8), ("horas y fechas", 12), ("multiacción", 10),
        ("nombres", 5), ("correcciones", 8), ("falta dato", 8), ("consultas", 5), ("charla", 6), ("interrupciones", 6),
    ],
    "conversiones": [
        ("conversiones", 50), ("cocina", 18), ("conv pendiente", 9), ("conv seguimiento", 7), ("conv imposible", 3), ("ciudades", 13), ("distractores", 12),
    ],
}

PHENOMENA = TARGETS["base"]


def for_batch(phenomena: set[str]) -> list[tuple[str, int]]:
    """The targets of the mix a batch was made with: the one that names most of its phenomena."""
    return max(TARGETS.values(), key=lambda targets: len(phenomena & {name for name, _ in targets}))
