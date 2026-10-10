package com.kakauet.dina.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.kakauet.dina.ui.character.CharacterDesign

/**
 * The launcher shows the chosen character: each design has an activity alias in the manifest
 * (`.ui.LauncherBrote`, `.ui.LauncherMusgo`) and only the chosen one stays enabled.
 * Call it when the app goes to the background: some launchers restart or redraw on the switch.
 */
object LauncherIcon {
    private fun component(context: Context, design: CharacterDesign) = ComponentName(
        context,
        "com.kakauet.dina.ui.Launcher" + design.name.lowercase().replaceFirstChar { it.uppercase() },
    )

    fun apply(context: Context, chosen: CharacterDesign) {
        val packages = context.packageManager
        fun enabled(design: CharacterDesign) = when (packages.getComponentEnabledSetting(component(context, design))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> design == CharacterDesign.BROTE // manifest default
        }
        if (enabled(chosen) && CharacterDesign.entries.none { it != chosen && enabled(it) }) return
        // Enable the new entry before disabling the old one, so the app is never missing from the launcher.
        packages.setComponentEnabledSetting(component(context, chosen), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (design in CharacterDesign.entries) if (design != chosen && enabled(design)) {
            packages.setComponentEnabledSetting(component(context, design), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
