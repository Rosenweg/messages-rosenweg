package org.fossify.messages.rosenweg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.messages.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Notfall wie in der PWA (protokoll.md "Notfall"): Live-Nachricht im Kanal "notfall"
 * oder mit priority "emergency" → rotes Vollbild, Sirene, Vibration – auch bei
 * "Nicht stören" (Alarm-Lautstaerke, sofern Zugriff auf "Nicht stören" erteilt ist).
 * Entwarnung nur im Kanal "notfall": clear:true oder Stopp-Wort → Sirene aus, sanfter Ton.
 */
object RosenwegNotfall {
    private const val TAG = "RosenwegNotfall"
    const val KANAL_NOTFALL = "rosenweg_notfall"
    private const val KANAL_KRISE = "rosenweg_krise"
    private const val NOTIF_NOTFALL = 4304
    private const val NOTIF_KRISE = 4305
    private const val SIRENE_MAX_MS = 3 * 60_000L

    private val STOPP = setOf("stop", "stopp", "entwarnung", "entwarnt", "ende", "vorbei", "aufgehoben", "alles ok", "alles gut")

    fun istEntwarnung(text: String?): Boolean {
        val norm = (text ?: "").trim().lowercase().replace(Regex("[.!?\\s]+$"), "")
        return norm in STOPP
    }

    fun istNotfall(chatId: String, priority: String?) = chatId == "notfall" || priority == "emergency"

    /** Aufruf fuer jede neue LIVE-Nachricht anderer (nicht fuer den Verlauf). */
    fun pruefe(context: Context, chat: RwChat, d: RwNachrichtDaten, titel: String, absender: String) {
        if (chat.id == "notfall" && (d.clear || istEntwarnung(d.body))) {
            entwarnung(context, d.body)
            return
        }
        if (istNotfall(chat.id, d.priority)) {
            merke(context, OffenerAlarm(chat.id, d.clientMsgId, titel, absender, d.body ?: "", System.currentTimeMillis()))
            alarm(context, titel, absender, d.body ?: "")
            planeWiederholung(context)
        }
    }

    // ── Wiederholung bis gelesen (alle 2 min, hoechstens 60 min) ─────────

    private const val PREFS = "rosenweg_notfall"
    private const val WIEDERHOLEN_MS = 2 * 60_000L
    private const val HOECHSTENS_MS = 60 * 60_000L
    const val AKTION_WIEDERHOLEN = "ch.rosenweg4303.messages.NOTFALL_WIEDERHOLEN"

    data class OffenerAlarm(val chatId: String, val clientMsgId: String?, val titel: String, val absender: String, val text: String, val start: Long)

    /** offener Alarm ueberlebt das Schliessen der App (SharedPreferences) */
    private fun merke(context: Context, a: OffenerAlarm) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("chat", a.chatId).putString("msg", a.clientMsgId).putString("titel", a.titel)
            .putString("absender", a.absender).putString("text", a.text).putLong("start", a.start)
            .apply()
    }

    fun offen(context: Context): OffenerAlarm? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val chat = p.getString("chat", null) ?: return null
        return OffenerAlarm(chat, p.getString("msg", null), p.getString("titel", "") ?: "", p.getString("absender", "") ?: "",
            p.getString("text", "") ?: "", p.getLong("start", 0))
    }

    private fun vergiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            context.getSystemService(android.app.AlarmManager::class.java)?.cancel(wiederholIntent(context))
        } catch (_: Exception) {
        }
    }

    private fun wiederholIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext, 4306,
        Intent(context.applicationContext, RosenwegNotfallReceiver::class.java).setAction(AKTION_WIEDERHOLEN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun planeWiederholung(context: Context) {
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val zeit = System.currentTimeMillis() + WIEDERHOLEN_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, zeit, wiederholIntent(context))
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, zeit, wiederholIntent(context))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wiederholung planen", e)
        }
    }

    /** Wiederhol-Alarm (auch nach Neustart des Prozesses): erneut alarmieren, solange nicht gelesen */
    fun wiederhole(context: Context) {
        val app = context.applicationContext
        val a = offen(app) ?: return
        if (System.currentTimeMillis() - a.start > HOECHSTENS_MS) {
            Log.i(TAG, "Notfall: 60 min ohne Quittung - Wiederholung endet")
            vergiss(app)
            return
        }
        val gelesen = a.clientMsgId?.let { RosenwegSpeicher.get(app).nachricht(a.chatId, it) }?.let { it.rcptSoll >= RwQuittung.GELESEN } == true
        if (gelesen) {
            vergiss(app)
            return
        }
        alarm(app, a.titel, a.absender, a.text)
        planeWiederholung(app)
    }

    // ── Alarm ───────────────────────────────────────────────────────────

    @Volatile
    var letzterAlarm: Triple<String, String, String>? = null
        private set

    fun alarm(context: Context, titel: String, absender: String, text: String) {
        val app = context.applicationContext
        letzterAlarm = Triple(titel, absender, text)
        Log.w(TAG, "NOTFALL von $absender in $titel")
        Sirene.start(app)
        vibriere(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(KANAL_NOTFALL) == null) {
            nm.createNotificationChannel(NotificationChannel(KANAL_NOTFALL, "Rosenweg Notfall", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notfall-Alarm des Rosenweg Messengers"
                setBypassDnd(true)
                setSound(null, null) // die Sirene spielt selbst (Alarm-Lautstaerke)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val oeffnen = PendingIntent.getActivity(
            app, NOTIF_NOTFALL,
            Intent(app, NotfallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, KANAL_NOTFALL)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle("🚨 NOTFALL – $titel")
            .setContentText(text.ifBlank { absender })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$absender: $text"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFD32F2F.toInt())
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(oeffnen)
            .setFullScreenIntent(oeffnen, true)
            .build()
        try {
            nm.notify(NOTIF_NOTFALL, n)
        } catch (e: Exception) {
            Log.w(TAG, "Notfall-Benachrichtigung", e)
        }
        weckeBildschirm(app)
        // zusaetzlich direkt starten (laufender Vordergrund-Dienst + Full-Screen-Recht)
        try {
            app.startActivity(Intent(app, NotfallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "NotfallActivity direkt", e)
        }
    }

    /** Bildschirm einschalten (auch bei aus/gesperrt), 30 s */
    @Suppress("DEPRECATION")
    private fun weckeBildschirm(context: Context) {
        try {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                "RosenwegMessenger:notfall"
            ).acquire(30_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Bildschirm wecken", e)
        }
    }

    /**
     * Quittiert = gelesen ("Verstanden" oder Notfall-Chat geoeffnet): Sirene aus,
     * Wiederholung beendet, "read"-Quittung fuer den Notfall-Chat senden.
     */
    fun quittieren(context: Context) {
        val app = context.applicationContext
        Sirene.stop(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_NOTFALL)
        val a = offen(app)
        vergiss(app)
        letzterAlarm = null
        Thread {
            try {
                val s = RosenwegSpeicher.get(app)
                if (a != null && s.quittiereNotfall(a.chatId, a.clientMsgId)) MessengerVerbindung.sendeAusstehende(app)
            } catch (e: Exception) {
                Log.w(TAG, "Quittung", e)
            }
        }.start()
    }

    /** eine eigene Lese-Quittung (auch von einem anderen eigenen Geraet) beendet den Alarm */
    fun gelesenErkannt(context: Context, chatId: String, clientMsgId: String) {
        val a = offen(context) ?: return
        if (a.chatId == chatId && (a.clientMsgId == null || a.clientMsgId == clientMsgId)) {
            Sirene.stop(context.applicationContext)
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_NOTFALL)
            vergiss(context)
            letzterAlarm = null
            NotfallActivity.schliesseAlle()
        }
    }

    fun entwarnung(context: Context, text: String?) {
        val app = context.applicationContext
        Log.i(TAG, "Entwarnung")
        val warAktiv = Sirene.laeuft() || letzterAlarm != null || offen(app) != null
        Sirene.stop(app)
        letzterAlarm = null
        vergiss(app)
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_NOTFALL)
        NotfallActivity.schliesseAlle()
        if (warAktiv) Sirene.entwarnungsTon(app)
    }

    private fun vibriere(context: Context) {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            val muster = longArrayOf(0, 300, 150, 300, 150, 300, 150, 600, 800)
            val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(muster, 0), attr)
        } catch (e: Exception) {
            Log.w(TAG, "Vibration", e)
        }
    }

    fun vibrationAus(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java).cancel()
            }
        } catch (_: Exception) {
        }
    }

    // ── sys/crisis ──────────────────────────────────────────────────────

    @Volatile
    var kriseAktiv = false
        private set

    /** Retained sys/crisis {active}: nur ein Hinweis (wie das Banner der PWA). */
    fun krise(context: Context, aktiv: Boolean) {
        val app = context.applicationContext
        if (aktiv == kriseAktiv) return
        kriseAktiv = aktiv
        val nm = app.getSystemService(NotificationManager::class.java)
        if (!aktiv) {
            nm.cancel(NOTIF_KRISE)
            return
        }
        if (nm.getNotificationChannel(KANAL_KRISE) == null) {
            nm.createNotificationChannel(NotificationChannel(KANAL_KRISE, "Rosenweg Krisenmodus", NotificationManager.IMPORTANCE_HIGH))
        }
        nm.notify(
            NOTIF_KRISE,
            NotificationCompat.Builder(app, KANAL_KRISE)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle("⚠️ Krisenmodus aktiv")
                .setContentText("Die Siedlung ist im Krisenmodus. Achte auf Ankündigungen und den Notfall-Kanal.")
                .setOngoing(true)
                .setColor(0xFFD32F2F.toInt())
                .build()
        )
    }

    // ── Sirene (synthetisch, Alarm-Lautstaerke) ─────────────────────────

    object Sirene {
        @Volatile
        private var spur: AudioTrack? = null

        @Volatile
        private var faden: Thread? = null
        private var alteLautstaerke = -1
        private var alterKlingelmodus = -1
        private var alterFilter = -1

        fun laeuft() = faden?.isAlive == true

        @Synchronized
        fun start(context: Context) {
            if (laeuft()) return
            lautMachen(context)
            val rate = 22050
            val t = Thread {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(rate)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                spur = track
                track.play()
                val ende = System.currentTimeMillis() + SIRENE_MAX_MS
                val puffer = ShortArray(rate / 10)
                var phase = 0.0
                var zeit = 0.0
                try {
                    while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < ende) {
                        for (i in puffer.indices) {
                            // auf- und abschwellender Ton 600–1200 Hz, Periode 1.6 s
                            val f = 900 + 300 * sin(2 * PI * zeit / 1.6)
                            phase += 2 * PI * f / rate
                            puffer[i] = (sin(phase) * 0.9 * Short.MAX_VALUE).toInt().toShort()
                            zeit += 1.0 / rate
                        }
                        if (track.write(puffer, 0, puffer.size) < 0) break
                    }
                } finally {
                    try {
                        track.stop()
                    } catch (_: Exception) {
                    }
                    track.release()
                    spur = null
                    leiseMachen(context)
                    vibrationAus(context)
                }
            }
            faden = t
            t.start()
        }

        @Synchronized
        fun stop(context: Context) {
            faden?.interrupt()
            faden = null
            vibrationAus(context)
        }

        /** Alarm-Lautstaerke voll, "Nicht stören" kurz aus (wie Telefon suchen, falls erlaubt). */
        private fun lautMachen(context: Context) {
            try {
                val am = context.getSystemService(AudioManager::class.java)
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) {
                    if (alterFilter < 0) alterFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                if (alteLautstaerke < 0) alteLautstaerke = am.getStreamVolume(AudioManager.STREAM_ALARM)
                if (alterKlingelmodus < 0) alterKlingelmodus = am.ringerMode
                // lautlos/Vibration: kurz auf normal, damit nichts den Alarm daempft
                if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (e: Exception) {
                        Log.w(TAG, "Klingelmodus", e)
                    }
                }
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: Exception) {
                Log.w(TAG, "laut", e)
            }
        }

        private fun leiseMachen(context: Context) {
            try {
                val am = context.getSystemService(AudioManager::class.java)
                if (alteLautstaerke >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, alteLautstaerke, 0)
                if (alterKlingelmodus >= 0 && am.ringerMode != alterKlingelmodus) am.ringerMode = alterKlingelmodus
                if (alterFilter > 0) context.getSystemService(NotificationManager::class.java).setInterruptionFilter(alterFilter)
            } catch (e: Exception) {
                Log.w(TAG, "zurueck", e)
            } finally {
                alteLautstaerke = -1
                alterFilter = -1
                alterKlingelmodus = -1
            }
        }

        /** sanfter Zweiklang statt Sirene */
        fun entwarnungsTon(context: Context) {
            Thread {
                try {
                    val rate = 22050
                    val dauer = rate * 7 / 10
                    val daten = ShortArray(dauer * 2)
                    for (teil in 0..1) {
                        val f = if (teil == 0) 660.0 else 880.0
                        for (i in 0 until dauer) {
                            val huelle = sin(PI * i / dauer)
                            daten[teil * dauer + i] = (sin(2 * PI * f * i / rate) * huelle * 0.5 * Short.MAX_VALUE).toInt().toShort()
                        }
                    }
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(daten.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(daten, 0, daten.size)
                    track.play()
                    Thread.sleep(1600)
                    track.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Entwarnungston", e)
                }
            }.start()
        }
    }
}

/** AlarmManager-Wiederholung des Notfalls (laeuft auch, wenn die App geschlossen war) */
class RosenwegNotfallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RosenwegNotfall.AKTION_WIEDERHOLEN) return
        val fertig = goAsync()
        Thread {
            try {
                RosenwegNotfall.wiederhole(context)
            } finally {
                fertig.finish()
            }
        }.start()
    }
}
