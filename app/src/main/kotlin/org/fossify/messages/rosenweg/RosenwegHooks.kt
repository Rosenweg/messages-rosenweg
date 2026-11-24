package org.fossify.messages.rosenweg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message

/**
 * Einstiegspunkte fuer die Fossify-Stellen, die Rosenweg-Threads (negative IDs)
 * anders behandeln muessen. Jede Funktion ist fuer SMS-Threads ohne Wirkung.
 */
object RosenwegHooks {
    const val TEXT_DM_FOLGT = "Verschlüsselte Direktnachrichten folgen"
    const val TEXT_NUR_LESEN = "In diesem Kanal kannst du nur lesen"

    /** Context.getMessages fuer Rosenweg-Threads: aus der Fossify-DB (Spiegel). */
    fun nachrichten(context: Context, threadId: Long, dateFrom: Int, limit: Int): ArrayList<Message> {
        val alle = try {
            context.messagesDB.getNonRecycledThreadMessages(threadId)
        } catch (_: Exception) {
            emptyList()
        }
        return ArrayList(
            alle.asSequence()
                .filter { dateFrom == -1 || it.date < dateFrom }
                .sortedWith(compareBy<Message> { it.date }.thenBy { -it.id })
                .toList()
                .takeLast(limit)
        )
    }

    fun conversation(context: Context, threadId: Long): Conversation? =
        RosenwegSpeicher.get(context).conversationZuThread(threadId)

    fun teilnehmer(context: Context, threadId: Long): ArrayList<SimpleContact> =
        RosenwegSpeicher.get(context).teilnehmer(threadId)

    /** Grund, warum im Thread nicht geschrieben werden kann (null = Schreiben erlaubt). */
    fun schreibSperre(context: Context, threadId: Long): String? {
        val chat = RosenwegSpeicher.get(context).chatZuThread(threadId) ?: return null
        return when {
            chat.kind == "dm" -> TEXT_DM_FOLGT
            !chat.canWrite -> TEXT_NUR_LESEN
            else -> null
        }
    }

    /** Senden in einem Rosenweg-Thread (blockierend, im Hintergrund aufrufen). */
    fun sende(context: Context, threadId: Long, text: String): Boolean {
        val speicher = RosenwegSpeicher.get(context)
        val chat = speicher.chatZuThread(threadId) ?: return false
        schreibSperre(context, threadId)?.let { grund ->
            context.toast(grund)
            return false
        }
        val m = speicher.neueAusgehende(chat, text) ?: run {
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
