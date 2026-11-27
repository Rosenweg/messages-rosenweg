package org.fossify.messages.rosenweg

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.messages.R

/**
 * SMS/MMS nur auf geteilten Rosenweg-Telefonen (Stefan 26.11.2026).
 *
 * - Geteiltes Telefon = Rosenweg Geraet ist installiert (ContentProvider
 *   content://ch.rosenweg4303.telefon.anlage aufloesbar, dasselbe Muster wie
 *   RosenwegAnmeldung.geraetDa / RosenwegUpdater.geraetDa im Commons-Fork). Dort bleibt alles wie
 *   bisher: Standard-SMS-App, SMS/MMS empfangen und senden, Rosenweg-Chats daneben.
 * - Privates Telefon (kein Rosenweg Geraet): nur Rosenweg Messenger ueber MQTT. Keine
 *   SMS-Rolle anfragen, keine SMS-Berechtigungen anfragen, die SMS/MMS-Bausteine sind per
 *   PackageManager ausgeschaltet (damit taucht die App auch nicht mehr in der Auswahl
 *   "Standard-SMS-App" auf), keine SMS-Benachrichtigungen, nur Rosenweg-Chats in der Liste.
 *
 * Keinen Uebergang mehr (Stefan 26.11.2026): auf dem privaten Telefon gibt es nur noch den
 * MQTT-Messenger, auch wenn die App (aus einer frueheren Version) noch Standard-SMS-App ist - die
 * SMS-Bausteine sind dann trotzdem aus. Eine App kann die Rolle nicht selbst abgeben
 * (RoleManager.removeRoleHolder braucht MANAGE_ROLE_HOLDERS = nur System), deshalb zeigt
 * MainActivity in dem Fall einen Hinweis mit dem Weg zu "Standard-Apps", damit die Person eine
 * andere SMS-App waehlt (dazu eine Benachrichtigung, auch wenn die App nicht geoeffnet wird).
 * SMS werden nie geloescht (sie liegen im SMS-Speicher von Android).
 *
 * Abgleich wie bei Rosenweg Telefon (Betriebsart): beim Prozessstart (App), nach Update/Neustart,
 * beim Wechsel der Standard-SMS-App, bei Meldungen der Geraete-App (ANGEMELDET/ABGEMELDET) und
 * alle 30 Min. (Wecker [WECKER]). Wird Rosenweg Geraet nachtraeglich installiert oder entfernt,
 * stellt die App so spaetestens nach 30 Min. um.
 */
object RosenwegSmsModus {
    private const val TAG = "RosenwegSmsModus"
    private const val ANLAGE = "ch.rosenweg4303.telefon.anlage"
    private const val KANAL = "rosenweg_einrichtung"
    private const val NOTIF_ROLLE = 4311

    /** Aktion des 30-Minuten-Weckers (an RosenwegSmsModusReceiver). */
    const val WECKER = "ch.rosenweg4303.messages.SMS_MODUS_PRUEFEN"
    private const val WECKER_REQ = 4312

    const val ROLLE_TITEL = "Gewohnte SMS-App wieder einstellen"
    const val ROLLE_TEXT =
        "Auf privaten Telefonen ist der Rosenweg Messenger nur noch für Rosenweg-Chats da, " +
            "nicht mehr für SMS.\n\n" +
            "Im Moment ist er noch deine Standard-SMS-App, nimmt aber keine SMS mehr an. Damit " +
            "du weiter SMS bekommst, stelle jetzt um – das kann nur Android: Tippe auf " +
            "„Standard-Apps öffnen“, dann auf „SMS-App“, und wähle deine gewohnte SMS-App " +
            "(z. B. Google Messages).\n\n" +
            "Deine SMS bleiben erhalten – sie liegen im SMS-Speicher von Android und sind " +
            "danach in der gewählten App zu sehen."

    /**
     * SMS/MMS-Bausteine aus dem Manifest, die auf privaten Telefonen aus sind. Klassennamen im
     * Kotlin-Namensraum (org.fossify.messages), nicht in der applicationId.
     */
    internal val SMS_BAUSTEINE = listOf(
        // ohne diese vier ist die App fuer Android keine SMS-App mehr (Rolle nicht waehlbar)
        "org.fossify.messages.receivers.SmsReceiver",
        "com.android.mms.transaction.PushReceiver",
        "org.fossify.messages.services.HeadlessSmsSendService",
        "org.fossify.messages.activities.NewConversationActivity",
        // Rest der SMS/MMS-Technik (Senden, Status, geplante SMS, Loeschen aus der Benachrichtigung)
        "com.android.mms.transaction.TransactionService",
        "org.fossify.messages.receivers.MmsReceiver",
        "org.fossify.messages.receivers.MmsSentReceiver",
        "org.fossify.messages.receivers.SmsStatusSentReceiver",
        "org.fossify.messages.receivers.SmsStatusDeliveredReceiver",
        "org.fossify.messages.receivers.ScheduledMessageReceiver",
        "org.fossify.messages.receivers.RescheduleAlarmsReceiver",
        "org.fossify.messages.receivers.DeleteSmsReceiver",
    )

    enum class Modus {
        /** Rosenweg Geraet da: SMS + Rosenweg wie bisher */
        GETEILT,

        /** privat: nur Rosenweg Messenger (MQTT) */
        PRIVAT,
    }

    /** Rosenweg Geraet installiert (geteiltes Telefon)? */
    fun geraetDa(c: Context): Boolean = try {
        c.packageManager.resolveContentProvider(ANLAGE, 0) != null
    } catch (e: Exception) {
        false
    }

    /** Haelt diese App die Rolle "Standard-SMS-App"? */
    fun istStandardSmsApp(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(RoleManager::class.java)
            rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && rm.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(c) == c.packageName
        }
    } catch (e: Exception) {
        false
    }

    fun modus(c: Context): Modus = modusAus(geraetDa(c))

    /** reine Entscheidung (fuer Tests): privat immer nur Messenger, auch als Standard-SMS-App */
    internal fun modusAus(geraetDa: Boolean): Modus = if (geraetDa) Modus.GETEILT else Modus.PRIVAT

    /** Nur Rosenweg-Chats (MQTT), keine SMS? */
    fun nurMessenger(c: Context): Boolean = modus(c) == Modus.PRIVAT

    /** SMS-Bausteine eingeschaltet lassen? (nur geteilt) */
    internal fun smsBausteineAn(m: Modus) = m != Modus.PRIVAT

    /**
     * Bausteine an den Modus anpassen. Bei jedem Prozessstart (App.onCreate), nach Update/Neustart
     * und wenn Android die Standard-SMS-App wechselt. Geteilt: Manifest-Zustand (an), privat: aus.
     */
    fun abgleichen(c: Context): Modus {
        val m = modus(c)
        val soll = if (smsBausteineAn(m)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = c.packageManager
        var geaendert = 0
        for (klasse in SMS_BAUSTEINE) {
            val cn = ComponentName(c.packageName, klasse)
            try {
                if (pm.getComponentEnabledSetting(cn) != soll) {
                    pm.setComponentEnabledSetting(cn, soll, PackageManager.DONT_KILL_APP)
                    geaendert++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Baustein $klasse: ${e.javaClass.simpleName}")
            }
        }
        if (geaendert > 0) Log.i(TAG, "Modus $m: $geaendert SMS-Bausteine ${if (soll == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) "aus" else "an"}")
        rolleHinweis(c, rolleHinweisNoetig(m, istStandardSmsApp(c)))
        weckerStellen(c)
        return m
    }

    /** reine Entscheidung (fuer Tests): Hinweis nur privat und nur solange die Rolle noch bei uns ist */
    internal fun rolleHinweisNoetig(m: Modus, standardSmsApp: Boolean) = m == Modus.PRIVAT && standardSmsApp

    /** Einstellungen "Standard-Apps" (dort waehlt die Person wieder die SMS-App des Handys). */
    fun standardAppsEinstellung(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Benachrichtigung "Gewohnte SMS-App wieder einstellen" (Antippen -> Standard-Apps). Die Rolle
     * entzieht die App nie selbst; die Benachrichtigung verschwindet beim naechsten Abgleich, sobald
     * eine andere SMS-App gewaehlt ist (Android meldet den Wechsel an RosenwegSmsModusReceiver).
     */
    private fun rolleHinweis(c: Context, zeigen: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!zeigen) {
            nm.cancel(NOTIF_ROLLE)
            return
        }
        try {
            if (nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAL, "Einrichtung", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Hinweise, wenn auf dem Handy etwas umzustellen ist"
                    }
                )
            }
            val pi = PendingIntent.getActivity(
                c, NOTIF_ROLLE, standardAppsEinstellung(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(c, KANAL)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(ROLLE_TITEL)
                .setContentText("Rosenweg Messenger ist noch Standard-SMS-App. Antippen und ändern.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(ROLLE_TEXT))
                .setContentIntent(pi)
                .addAction(0, "Standard-Apps öffnen", pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIF_ROLLE, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Hinweis Standard-SMS-App: ${e.javaClass.simpleName}")
        }
    }

    /** Alle 30 Min. abgleichen (ungenau, weckt das Handy nicht). Einmal gestellt bleibt er bis zum Neustart. */
    private fun weckerStellen(c: Context) {
        val i = Intent(c, RosenwegSmsModusReceiver::class.java).setAction(WECKER)
        val flags = PendingIntent.FLAG_IMMUTABLE
        if (PendingIntent.getBroadcast(c, WECKER_REQ, i, flags or PendingIntent.FLAG_NO_CREATE) != null) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR,
                AlarmManager.INTERVAL_HALF_HOUR,
                PendingIntent.getBroadcast(c, WECKER_REQ, i, flags or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Wecker: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Bleibt immer an: Neustart, Update, Wechsel der Standard-SMS-App (Android schickt
 * ACTION_DEFAULT_SMS_PACKAGE_CHANGED gezielt an die alte und die neue SMS-App) und der
 * 30-Minuten-Wecker -> Bausteine abgleichen.
 */
class RosenwegSmsModusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RosenwegSmsModus.abgleichen(context.applicationContext)
    }
}
