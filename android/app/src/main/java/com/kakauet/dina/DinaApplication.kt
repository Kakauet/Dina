package com.kakauet.dina

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.kakauet.dina.brain.BrainRegistry
import com.kakauet.dina.brain.BrainSpec
import com.kakauet.dina.config.DinaSettings
import com.kakauet.dina.core.ConversationController
import com.kakauet.dina.core.DinaStore
import com.kakauet.dina.tools.DinaTools
import com.kakauet.dina.tools.ToolEngine
import com.kakauet.dina.voice.DinaService

/** Process-wide singletons. Activities, the service and receivers share these instances. */
class AppGraph(private val context: Context) {
    val settings = DinaSettings(context)
    val store = DinaStore()
    val controller = ConversationController(store)
    val tools: ToolEngine = DinaTools.get(context)
    val brainSpec: BrainSpec get() = BrainRegistry.default
}

val Context.dina: AppGraph get() = (applicationContext as DinaApplication).graph

class DinaApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            VOICE_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) })
        notifications.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = getString(R.string.alert_channel_description) })

        // The voice service follows app visibility: foreground → listen, background → stop unless enabled.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private fun micGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0 && micGranted()) DinaService.appForeground(this@DinaApplication)
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && micGranted()) DinaService.appBackground(this@DinaApplication)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        const val VOICE_CHANNEL = "dina_voice"
        const val ALERT_CHANNEL = "dina_alerts"
    }
}
