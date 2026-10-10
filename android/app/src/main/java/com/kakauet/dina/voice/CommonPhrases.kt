package com.kakauet.dina.voice

import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.dialog.Responder

/**
 * The short fixed answers Dina gives all the time. The service synthesizes them in the background when
 * the phone is idle, so the first time the user hears one it is already in the [SpeechCache].
 * Texts come from the responder, so they cannot drift from what she really says.
 */
object CommonPhrases {
    val ALL: List<String> = Responder.CHAT + listOf(
        "Hecho.", "Silenciado.", "Vale, en silencio.", "Vale, lo dejo.", "De acuerdo, no hago nada.", "Vale, olvídalo.",
        "Vale. ¿Qué necesitas?", "¿Qué necesitas?", "No hay nada que deshacer.", "Cronómetro en marcha.",
        "Perdona, no te he entendido. ¿Me lo repites?", "No te he entendido bien. ¿Me lo dices de otra forma?",
        ConversationController.FAILED,
    )
}
