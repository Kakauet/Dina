package com.kakauet.dina.tools

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kakauet.dina.DinaApplication
import com.kakauet.dina.ui.MainActivity

/**
 * Process-wide [ToolEngine] wired to Android: AlarmManager, AudioManager and SharedPreferences
 * (written in the background: a turn never waits for the disk).
 */
object DinaTools {
    @Volatile private var instance: ToolEngine? = null
    @Volatile private var store: AsyncToolStore? = null

    fun get(context: Context): ToolEngine = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            val persisted = AsyncToolStore(PrefsToolStore(app), onError = { Log.e("DinaTools", "No se pudo guardar el estado", it) }).also { store = it }
            ToolEngine(persisted, AndroidAlertScheduler(app), AndroidVolumeControl(app))
        }.also { instance = it }
    }

    /** Waits for pending writes; receivers call it before returning (the process may die right after). */
    fun flush() { runCatching { store?.flush() } }
}

private class PrefsToolStore(context: Context) : ToolStore {
    private val prefs = context.getSharedPreferences("dina_tools", Context.MODE_PRIVATE)

    override fun load(): ToolWorld? = ToolWorldJson.decode(prefs.getString("state", null))

    override fun save(world: ToolWorld) {
        check(prefs.edit().putString("state", ToolWorldJson.encode(world)).commit()) { "tool_state_commit_failed" }
    }
}

private class AndroidVolumeControl(context: Context) : VolumeControl {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC
    private val maximum get() = audio.getStreamMaxVolume(stream).coerceAtLeast(1)

    override fun percent() = (audio.getStreamVolume(stream) * 100f / maximum).toInt().coerceIn(0, 100)
    override fun isMuted() = audio.isStreamMute(stream)
    override fun setPercent(percent: Int) {
        // A stream muted from the system stays silent whatever its level: a level above 0 unmutes it.
        if (percent > 0 && audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(stream, ((percent.coerceIn(0, 100) / 100f) * maximum).toInt(), 0)
    }
}

private class AndroidAlertScheduler(private val context: Context) : AlertScheduler {
    override fun schedule(kind: AlertKind, id: String, atMs: Long, label: String?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(kind, id, label, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    }

    override fun cancel(kind: AlertKind, id: String) {
        val operation = pendingIntent(kind, id, null, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    private fun pendingIntent(kind: AlertKind, id: String, label: String?, flag: Int): PendingIntent? {
        val intent = Intent(context, ToolAlarmReceiver::class.java).apply {
            data = Uri.parse("dina://alert/${kind.code}/$id")
            putExtra("type", kind.code)
            putExtra("id", id)
            putExtra("label", label)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ToolAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = AlertKind.of(intent.getStringExtra("type") ?: "timer")
        val id = intent.getStringExtra("id") ?: return
        val label = intent.getStringExtra("label")
        DinaTools.get(context).markRinging(kind, id)
        DinaTools.flush()
        val title = if (kind == AlertKind.ALARM) "Alarma de Dina" else "Temporizador de Dina"
        val text = label?.takeIf { it.isNotBlank() } ?: if (kind == AlertKind.ALARM) "Es la hora." else "Se ha acabado el tiempo."
        val open = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DinaApplication.ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }
}

class ToolBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DinaTools.get(context).rescheduleAll()
            DinaTools.flush()
        }
    }
}
