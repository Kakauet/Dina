package com.kakauet.dina.tools

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The real Android store: same SharedPreferences file and key as app 1.4, written in the background. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidToolStoreTest {
    @Test
    fun theAppReadsAndWritesTheStateWhereApp14LeftIt() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("dina_tools", Context.MODE_PRIVATE)
        prefs.edit().putString("state", APP_14_STATE).commit()

        val engine = DinaTools.get(context)
        assertEquals(listOf("leche", "pan"), engine.state.value.shopping.map { it.name })
        engine.execute(ShoppingCommand.Add("sal"))
        DinaTools.flush()

        val saved = requireNotNull(ToolWorldJson.decode(prefs.getString("state", null)))
        assertEquals(listOf("i1", "i2", "i3"), saved.shopping.map { it.id })
        assertEquals(engine.state.value, saved)
    }
}
