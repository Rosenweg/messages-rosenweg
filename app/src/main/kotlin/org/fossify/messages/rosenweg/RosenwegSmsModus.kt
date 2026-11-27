package org.fossify.messages.rosenweg

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log

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
 * andere SMS-App waehlt. SMS werden nie geloescht (sie liegen im SMS-Speicher von Android).
 */
object RosenwegSmsModus {
    private const val TAG = "RosenwegSmsModus"
    private const val ANLAGE = "ch.rosenweg4303.telefon.anlage"

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
        return m
    }
}

/**
 * Bleibt immer an: Neustart, Update und Wechsel der Standard-SMS-App (Android schickt
 * ACTION_DEFAULT_SMS_PACKAGE_CHANGED gezielt an die alte und die neue SMS-App) -> Bausteine abgleichen.
 */
class RosenwegSmsModusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RosenwegSmsModus.abgleichen(context.applicationContext)
    }
}
