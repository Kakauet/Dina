package com.kakauet.dina.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DinaStoreTest {
    @Test
    fun loadStagesCoverZeroToOneInOrder() {
        assertEquals(0f, LoadStage.STORAGE.progress(), 1e-6f)
        assertEquals(1f, LoadStage.WAKE.progress(1f), 1e-6f)
        val starts = LoadStage.entries.map { it.progress() }
        assertEquals(starts.sorted(), starts)
        // Each step ends where the next one starts, so skipping one (Moonshine off) never goes back.
        LoadStage.entries.zipWithNext().forEach { (a, b) -> assertEquals(b.progress(), a.progress(1f), 1e-6f) }
        assertEquals(LoadStage.COPY.progress(1f), LoadStage.COPY.progress(5f), 1e-6f)
    }

    @Test
    fun loadingProgressOnlyMovesForwardUntilANewLoadStarts() {
        val store = DinaStore()
        store.setLoading(LoadStage.STORAGE, "Comprobando")
        store.setLoading(LoadStage.COPY, "Copiando 1/2", 0f)
        store.setLoading(LoadStage.COPY, "Copiando 2/2", 0.5f)
        val copying = store.session.value
        assertEquals(VoiceState.LOADING, copying.state)
        assertFalse(copying.ready)
        assertEquals("Copiando 2/2", copying.detail)
        assertEquals(LoadStage.COPY.progress(0.5f), copying.loadProgress, 1e-6f)

        store.setLoading(LoadStage.COPY, "Copiando 1/2", 0f)
        assertEquals(LoadStage.COPY.progress(0.5f), store.session.value.loadProgress, 1e-6f)

        store.setLoading(LoadStage.BRAIN, "Cargando")
        assertTrue(store.session.value.loadProgress > copying.loadProgress)

        // A reload (settings changed, retry) starts again from zero.
        store.setLoading(LoadStage.STORAGE, "Comprobando")
        assertEquals(0f, store.session.value.loadProgress, 1e-6f)
    }
}
