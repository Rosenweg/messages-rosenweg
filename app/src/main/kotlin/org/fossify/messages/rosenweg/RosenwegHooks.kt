package org.fossify.messages.rosenweg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message

/**
 * Einstiegspunkte fuer die Fossify-Stellen, die Rosenweg-Threads (negative IDs)
 * anders behandeln muessen. Jede Funktion ist fuer SMS-Threads ohne Wirkung.
 */
object RosenwegHooks {
    const val TEXT_NUR_LESEN = "In diesem Kanal kannst du nur lesen"

    /** Context.getMessages fuer Rosenweg-Threads: aus der Fossify-DB (Spiegel). */
    fun nachrichten(context: Context, threadId: Long, dateFrom: Int, limit: Int): ArrayList<Message> =
        try {
            RosenwegSpeicher.get(context).fossifyNachrichten(threadId, dateFrom, limit)
        } catch (e: Exception) {
            Log.w("RosenwegHooks", "Nachrichten fuer Thread $threadId", e)
            ArrayList()
        }

    fun conversation(context: Context, threadId: Long): Conversation? =
        RosenwegSpeicher.get(context).conversationZuThread(threadId)

    fun teilnehmer(context: Context, threadId: Long): ArrayList<SimpleContact> =
        RosenwegSpeicher.get(context).teilnehmer(threadId)

    /** Grund, warum im Thread nicht geschrieben werden kann (null = Schreiben erlaubt). */
    fun schreibSperre(context: Context, threadId: Long): String? {
        val chat = RosenwegSpeicher.get(context).chatZuThread(threadId) ?: return null
        return when {
            !chat.canWrite -> TEXT_NUR_LESEN
            else -> null
        }
    }

    /** Senden in einem Rosenweg-Thread (blockierend, im Hintergrund aufrufen). */
    fun sende(context: Context, threadId: Long, text: String, antwortAuf: Long? = null): Boolean {
        val speicher = RosenwegSpeicher.get(context)
        val chat = speicher.chatZuThread(threadId) ?: return false
        schreibSperre(context, threadId)?.let { grund ->
            context.toast(grund)
            return false
        }
        val m = speicher.neueAusgehende(chat, text, antwortAuf = antwortAuf?.let { speicher.nachrichtZuSpiegel(it) }) ?: run {
            context.toast("Rosenweg Messenger ist nicht angemeldet")
            return false
        }
        if (RosenwegKonto.hole(context) == null) {
            speicher.setzeStatus(m.rowId, RwStatus.FEHLER)
        } else {
            MessengerVerbindung.sendeAusstehende(context)
        }
        refreshMessages()
        refreshConversations()
        return true
    }

    /** Thread ist sichtbar: "gelesen"-Quittungen fuer fremde Nachrichten (nie fuer eigene). */
    fun angezeigt(context: Context, threadId: Long) {
        if (!threadId.isRosenwegThread()) return
        val app = context.applicationContext
        ensureBackgroundThread {
            if (RosenwegSpeicher.get(app).quittiereGelesen(threadId) && MessengerVerbindung.istAktiv()) {
                MessengerVerbindung.sendeAusstehende(app)
            }
        }
    }

    // ── Antworten / Reagieren / Bearbeiten / Loeschen (Aufrufe im Hintergrund) ──

    val EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** "Name: Vorschau" fuer die Antwort-Leiste */
    fun antwortVorschau(context: Context, mirrorId: Long): String? {
        val s = RosenwegSpeicher.get(context)
        val m = s.nachrichtZuSpiegel(mirrorId) ?: return null
        return "${s.name(m.senderId)}: ${s.vorschau(m).take(80)}"
    }

    fun rohText(context: Context, mirrorId: Long): String? = RosenwegSpeicher.get(context).nachrichtZuSpiegel(mirrorId)?.body

    fun meineReaktionen(context: Context, mirrorId: Long): Set<String> {
        val s = RosenwegSpeicher.get(context)
        val ich = s.eigeneId ?: return emptySet()
        val m = s.nachrichtZuSpiegel(mirrorId) ?: return emptySet()
        return reaktionenAusJson(m.reactions).filterValues { ich in it }.keys
    }

    /** Ruft die API fuer eine Nachricht auf; Ergebnis per Toast. Der Server verteilt die Aenderung (control/<conv>). */
    fun aktion(context: Context, mirrorId: Long, fehlerText: String, block: (RosenwegApi, RwChat, String) -> Unit) {
        val app = context.applicationContext
        ensureBackgroundThread {
            try {
                val s = RosenwegSpeicher.get(app)
                val m = s.nachrichtZuSpiegel(mirrorId) ?: throw IllegalStateException("Nachricht fehlt")
                val id = m.clientMsgId ?: throw IllegalStateException("Nachricht noch nicht gesendet")
                val chat = s.chat(m.chatId) ?: throw IllegalStateException("Chat fehlt")
                val konto = RosenwegKonto.hole(app) ?: throw IllegalStateException("nicht angemeldet")
                block(RosenwegApi(konto), chat, id)
            } catch (e: Exception) {
                Log.w("RosenwegHooks", fehlerText, e)
                app.toast(fehlerText)
            }
        }
    }

    /**
     * Anhang senden (blockierend, im Hintergrund): lesen, hochladen (DM verschluesselt),
     * dann wie eine Textnachricht ueber MQTT. text = Bildunterschrift.
     */
    fun sendeMedien(context: Context, threadId: Long, uri: android.net.Uri, mime: String, name: String?, text: String, antwortAuf: Long?): Boolean {
        val speicher = RosenwegSpeicher.get(context)
        val chat = speicher.chatZuThread(threadId) ?: return false
        schreibSperre(context, threadId)?.let { grund ->
            context.toast(grund)
            return false
        }
        return try {
            val konto = RosenwegKonto.hole(context) ?: throw IllegalStateException("nicht angemeldet")
            val auswahl = RosenwegMedien.lese(context, uri, mime, name)
            val b = RosenwegMedien.hochladen(context, RosenwegApi(konto), chat, auswahl)
            speicher.neueAusgehende(chat, text, antwortAuf = antwortAuf?.let { speicher.nachrichtZuSpiegel(it) }, medien = b)
                ?: throw IllegalStateException("nicht angemeldet")
            MessengerVerbindung.sendeAusstehende(context)
            refreshMessages()
            refreshConversations()
            true
        } catch (e: Exception) {
            Log.w("RosenwegHooks", "Anhang senden", e)
            context.toast("Anhang nicht gesendet: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun markiereThreadGelesen(context: Context, threadId: Long) =
        RosenwegSpeicher.get(context).markiereThreadGelesen(threadId)

    fun markiereGelesen(context: Context, messageId: Long) =
        RosenwegSpeicher.get(context).markiereGelesen(messageId)

    fun gespraechGeloescht(context: Context, threadId: Long) =
        RosenwegSpeicher.get(context).loescheVerlaufLokal(threadId)

    fun nachrichtGeloescht(context: Context, messageId: Long) =
        RosenwegSpeicher.get(context).loescheNachrichtLokal(messageId)
}

/** Startet die MessengerVerbindung nach dem Neustart / einem App-Update. */
class RosenwegStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val ende = goAsync()
                val app = context.applicationContext
                ensureBackgroundThread {
                    try {
                        if (RosenwegKonto.hole(app) != null) MessengerVerbindung.starteWennKonto(app)
                    } finally {
                        ende.finish()
                    }
                }
            }
        }
    }
}
