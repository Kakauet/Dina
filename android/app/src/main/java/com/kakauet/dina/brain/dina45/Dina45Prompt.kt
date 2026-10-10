package com.kakauet.dina.brain.dina45

import com.kakauet.dina.dialog.Action

/**
 * The Dina 4.5 prompt in LFM2.5's chat template: a fixed system part (always in the prompt cache), then
 * `[estado]`, `[antes]` (one turn, compressed to actions and result) and `[ahora]` (the phrase).
 * Training data is rendered with this same object, so app and training never diverge.
 */
object Dina45Prompt {
    const val SYSTEM = "Eres Dina, asistente de voz local. Lee el estado y la frase y escribe solo acciones, una por línea: " +
        "dominio.op(objetivo, valor, clave=valor). Sin petición: say(\"…\"). Si no sabes qué quiere: ask(). Si no puedes: no(tema)."

    /** Longest output: a few actions (a typical one is 8–22 tokens). */
    const val MAX_TOKENS = 48

    /** Longest output that starts with `say(` (pure chat). */
    const val MAX_SAY_TOKENS = 120

    /** Token budget of an output that began with [head]: chat may talk longer, actions keep [MAX_TOKENS]. */
    fun budget(head: String) = if (head.startsWith("say(") || "say(".startsWith(head)) MAX_SAY_TOKENS else MAX_TOKENS

    fun render(state: String, before: String?, utterance: String): String = complete(prefix(state, before), utterance)

    /** A [prefix] followed by the phrase and the assistant turn. */
    fun complete(prefix: String, utterance: String): String = prefix + clean(utterance) + "<|im_end|>\n<|im_start|>assistant\n"

    /** Everything before the phrase: known while the user is still speaking (prefilled ahead, see Dina45Brain.prepare). */
    fun prefix(state: String, before: String?): String = buildString {
        append("<|startoftext|><|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        append("<|im_start|>user\n[estado]\n").append(state).append('\n')
        if (!before.isNullOrBlank()) append("[antes]\n").append(before).append('\n')
        append("[ahora]\n")
    }

    /** The previous turn: "usuario: …\ndina: alarm.list() → 2 alarmas". */
    fun history(user: String, actions: List<Action>, brief: String): String =
        "usuario: ${clean(user)}\ndina: ${actions.joinToString(" ; ") { it.encode() }.ifEmpty { "—" }} → $brief"

    /** Whitespace as the prompt carries it (training data renders through the same function). */
    fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()
}
