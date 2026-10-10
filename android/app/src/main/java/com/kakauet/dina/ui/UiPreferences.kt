package com.kakauet.dina.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kakauet.dina.ui.character.CharacterDesign

enum class ThemeMode(val label: String) { SYSTEM("Automático"), LIGHT("Claro"), DARK("Oscuro") }

/**
 * Look-only preferences (character and theme). They live in ui/ because nothing outside the
 * interface reads them; Compose observes them, so a change applies at once.
 */
class UiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("dina_ui", Context.MODE_PRIVATE)

    var character by mutableStateOf(load(KEY_CHARACTER, CharacterDesign.BROTE))
        private set

    var theme by mutableStateOf(load(KEY_THEME, ThemeMode.SYSTEM))
        private set

    fun updateCharacter(value: CharacterDesign) {
        character = value
        prefs.edit().putString(KEY_CHARACTER, value.name).apply()
    }

    fun updateTheme(value: ThemeMode) {
        theme = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    private inline fun <reified E : Enum<E>> load(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val KEY_CHARACTER = "character"
        const val KEY_THEME = "theme"
    }
}
