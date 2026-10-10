package com.kakauet.dina.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce

/**
 * Synthesizes [text] one piece at a time ([SpeechPlanner]: sentences, the first one cut at a comma when it is
 * long): each one is sent as soon as it exists, with the pause that follows it, and the next is synthesized
 * while the previous plays (at most one finished piece waits).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CoroutineScope.speechSentences(
    voice: SpeechVoice,
    text: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Synthesis seconds per audio second measured on this phone (decides whether cutting the first sentence leaves a gap). */
    rtf: Double = SpeechPlanner.DEFAULT_RTF,
    planner: SpeechPlanner.Config = SpeechPlanner.Config(),
): ReceiveChannel<SpeechAudio> = produce(dispatcher, capacity = 1) {
    for (chunk in SpeechPlanner.plan(text, rtf, planner)) send(voice.synthesize(chunk.text).copy(pauseAfterMs = chunk.pauseAfterMs))
}
