package org.fossify.messages.rosenweg

import android.content.Context
import android.provider.Telephony
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message

/**
 * Rosenweg-Speicher (rosenweg.db) und Spiegel in die Fossify-Tabellen.
 *
 * Gespiegelt wird mit NEGATIVEN IDs: Thread = -(1e12 + chat.seq), Nachricht =
 * -(rwmessage.row_id). So bleiben die Fossify-Ansichten (Liste, Thread,
 * Benachrichtigung) unveraendert nutzbar, ohne die SMS-Seite zu beruehren.
 * Alle schreibenden Zugriffe laufen synchronisiert (Dienst + UI-Threads).
 */
class RosenwegSpeicher private constructor(context: Context) {
    private val app = context.applicationContext
    private val dao = RosenwegDatenbank.get(app).dao()
    private val sperre = Any()

    @Volatile
    private var namen: Map<String, String>? = null

    companion object {
        const val NAME_SYSTEM = "Rosenweg"
        const val NAME_DU = "Du"
        const val NAME_UNBEKANNT = "Unbekannt"

        @Volatile
        private var instanz: RosenwegSpeicher? = null

        fun get(context: Context): RosenwegSpeicher =
            instanz ?: synchronized(this) { instanz ?: RosenwegSpeicher(context).also { instanz = it } }
    }

    data class Eingang(val chat: RwChat, val nachricht: RwMessage, val neu: Boolean, val anzeige: String, val absenderName: String)

    val eigeneId: String?
        get() = RosenwegKonto.bekannt()?.userId ?: RosenwegKonto.eigeneIdGespeichert(app)

    // ── Namen ───────────────────────────────────────────────────────────

    private fun namen(): Map<String, String> =
        namen ?: dao.benutzer().associate { it.id to it.name }.also { namen = it }

    fun speichereVerzeichnis(eintraege: Map<String, String>): Unit = synchronized(sperre) {
        if (eintraege.isEmpty()) return
        dao.speichereBenutzer(eintraege.map { RwUser(it.key, it.value) })
        namen = null
    }

    /** Nie rohe IDs anzeigen: Verzeichnis, eigener Name, "Rosenweg" fuer System, sonst "Unbekannt". */
    fun name(userId: String): String = when {
        userId == RW_SYSTEM_ABSENDER -> NAME_SYSTEM
        userId == eigeneId -> RosenwegKonto.bekannt()?.name ?: RosenwegKonto.eigenerNameGespeichert(app) ?: NAME_DU
        else -> namen()[userId] ?: NAME_UNBEKANNT
    }

    fun titel(chat: RwChat): String {
        if (chat.kind == "dm") {
            val anderer = mitglieder(chat).firstOrNull { it != eigeneId }
            if (anderer != null) return namen()[anderer] ?: chat.title ?: NAME_UNBEKANNT
            return chat.title ?: "Direktnachricht"
        }
        return chat.title ?: chat.id
    }

    private fun mitglieder(chat: RwChat): List<String> {
        val text = chat.members ?: return emptyList()
        return (parseJson(text) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    private fun kontakt(userId: String): SimpleContact {
        val adresse = RW_ADRESSE + userId
        return SimpleContact(
            rawId = adresse.hashCode(),
            contactId = adresse.hashCode(),
            name = name(userId),
            photoUri = "",
            phoneNumbers = arrayListOf(PhoneNumber(adresse, 0, "", adresse)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
    }

    // ── Abfragen fuer die Fossify-Hooks ──────────────────────────────────

    fun chatZuThread(threadId: Long): RwChat? = dao.chatZuThread(threadId)

    fun chat(id: String): RwChat? = dao.chat(id)

    fun chats(): List<RwChat> = dao.chats()

    /** Teilnehmer eines Rosenweg-Threads (ohne sich selbst) fuer Kopfzeile/Details. */
    fun teilnehmer(threadId: Long): ArrayList<SimpleContact> {
        val chat = dao.chatZuThread(threadId) ?: return ArrayList()
        var ids = mitglieder(chat).filter { it != eigeneId }
        if (ids.isEmpty()) {
            ids = dao.nachrichten(chat.id).map { it.senderId }.distinct().filter { it != eigeneId }
        }
        if (ids.isEmpty()) {
            // Ankuendigungskanal ohne bekannte Mitglieder: Platzhalter, damit der Thread oeffnet
            return arrayListOf(kontakt(RW_SYSTEM_ABSENDER).copy(name = titel(chat)))
        }
        return ArrayList(ids.map { kontakt(it) })
    }

    fun conversationZuThread(threadId: Long): Conversation? {
        val chat = dao.chatZuThread(threadId) ?: return null
        return baueConversation(chat)
    }

    // ── Gespraeche abgleichen ───────────────────────────────────────────

    /**
     * Uebernimmt GET /api/conversations. pubTopics = pub_topics des MQTT-JWT
     * (null = unbekannt → aus Art/Rolle abgeleitet). Rueckgabe: aktuelle Chats.
     */
    fun gleicheGespraecheAb(
        liste: List<RwGespraechDaten>,
        pubTopics: List<String>?,
        mitgliederJeChat: Map<String, List<String>>,
    ): List<RwChat> = synchronized(sperre) {
        val ergebnis = ArrayList<RwChat>()
        for (g in liste) {
            val alt = dao.chat(g.id)
            val members = (mitgliederJeChat[g.id]?.takeIf { it.isNotEmpty() } ?: g.participants.takeIf { it.isNotEmpty() })
                ?.let { ids -> JsonArray(ids.map { JsonPrimitive(it) }).toString() }
                ?: alt?.members
            val kannSchreiben = pubTopics?.let { darfSchreiben(it, g.topicPrefix) }
                ?: (g.kind != "bcast" && g.role != "readonly")
            var chat = RwChat(
                seq = alt?.seq ?: 0,
                id = g.id,
                kind = g.kind,
                title = g.title,
                topicPrefix = g.topicPrefix,
                metadata = g.metadata,
                role = g.role,
                members = members,
                lastServerId = alt?.lastServerId ?: 0,
                canWrite = kannSchreiben,
                threadId = alt?.threadId ?: 0,
            )
            if (alt == null) {
                val seq = dao.neuerChat(chat)
                chat = chat.copy(seq = seq, threadId = RosenwegIds.threadId(seq))
                dao.aendereChat(chat)
            } else if (alt != chat) {
                dao.aendereChat(chat)
            }
            spiegleGespraech(chat)
            ergebnis.add(chat)
        }

        // Nicht mehr Mitglied: Chat samt Spiegel entfernen
        val aktuell = liste.map { it.id }.toSet()
        dao.chats().filter { it.id !in aktuell }.forEach { weg ->
            dao.nachrichten(weg.id).forEach { app.messagesDB.delete(it.mirroredMessageId) }
            dao.loescheChatNachrichten(weg.id)
            app.conversationsDB.deleteThreadId(weg.threadId)
            dao.loescheChat(weg.id)
        }
        ergebnis
    }

    // ── Nachrichten aufnehmen ───────────────────────────────────────────

    /**
     * Nimmt eine Nachricht auf (live oder aus dem Verlauf). Duplikate werden am
     * clientMsgId (sonst Server-ID) erkannt; das eigene Echo setzt "gesendet".
     * gelesen = fuer den Erstimport des Verlaufs (keine Ungelesen-Flut).
     */
    fun nimmAuf(chat: RwChat, d: RwNachrichtDaten, ausVerlauf: Boolean, gelesen: Boolean): Eingang = synchronized(sperre) {
        val eigen = d.senderId == eigeneId
        val alt = d.clientMsgId?.let { dao.nachricht(chat.id, it) }
            ?: d.serverId?.let { dao.nachrichtZuServerId(chat.id, it) }

        val nachricht: RwMessage
        val neu: Boolean
        if (alt != null) {
            neu = false
            nachricht = alt.copy(
                serverId = d.serverId ?: alt.serverId,
                status = if (alt.status == RwStatus.AUSSTEHEND || alt.status == RwStatus.FEHLER) RwStatus.GESENDET else alt.status,
                // Der Verlauf ist die Wahrheit fuer Bearbeiten/Loeschen/Reaktionen
                body = if (ausVerlauf && !d.deleted) d.body ?: alt.body else alt.body,
                ciphertext = if (ausVerlauf) d.ciphertext ?: alt.ciphertext else alt.ciphertext,
                editedAt = if (ausVerlauf) d.editedAt ?: alt.editedAt else alt.editedAt,
                deleted = alt.deleted || d.deleted,
                reactions = if (ausVerlauf) reaktionenZuJson(d.reactions) else alt.reactions,
            )
            if (nachricht != alt) dao.aendereNachricht(nachricht)
        } else {
            neu = true
            val roh = RwMessage(
                serverId = d.serverId,
                clientMsgId = d.clientMsgId,
                chatId = chat.id,
                senderId = d.senderId,
                body = if (d.deleted) null else d.body,
                ciphertext = if (d.deleted) null else d.ciphertext,
                ts = d.ts,
                editedAt = d.editedAt,
                deleted = d.deleted,
                reactions = if (d.reactions.isEmpty()) null else reaktionenZuJson(d.reactions),
                replyPreview = d.replyPreview,
                mediaName = d.mediaName,
                status = if (eigen) RwStatus.GESENDET else RwStatus.EMPFANGEN,
                read = eigen || gelesen,
            )
            val zeile = dao.neueNachricht(roh)
            nachricht = roh.copy(rowId = zeile, mirroredMessageId = RosenwegIds.nachrichtId(zeile))
            dao.aendereNachricht(nachricht)
        }
        d.serverId?.let { dao.setzeLastServerId(chat.id, it) }
        spiegleNachricht(chat, nachricht)
        spiegleGespraech(chat)
        Eingang(chat, nachricht, neu, anzeige(nachricht), name(nachricht.senderId))
    }

    /** control/<conv>: Reaktion, Loeschen, Bearbeiten. Rueckgabe: etwas geaendert. */
    fun wendeAn(chat: RwChat, s: RwSteuerung): Boolean = synchronized(sperre) {
        val id = when (s) {
            is RwSteuerung.Reaktion -> s.clientMsgId
            is RwSteuerung.Loeschen -> s.clientMsgId
            is RwSteuerung.Bearbeiten -> s.clientMsgId
            is RwSteuerung.Gruppe -> return false
        }
        val alt = dao.nachricht(chat.id, id) ?: return false
        val neu = when (s) {
            is RwSteuerung.Reaktion -> alt.copy(
                reactions = reaktionenZuJson(wendeReaktionAn(reaktionenAusJson(alt.reactions), s.userId, s.emoji, s.entfernen))
            )
            is RwSteuerung.Loeschen -> alt.copy(deleted = true, body = null, ciphertext = null, mediaName = null)
            is RwSteuerung.Bearbeiten -> alt.copy(body = s.body, editedAt = s.editedAt)
            is RwSteuerung.Gruppe -> alt
        }
        if (neu == alt) return false
        dao.aendereNachricht(neu)
        spiegleNachricht(chat, neu)
        spiegleGespraech(chat)
        true
    }

    /** Eigene Nachricht vormerken (OUTBOX), bis Broker-Bestaetigung oder Echo kommt. */
    fun neueAusgehende(chat: RwChat, text: String, jetzt: Long = System.currentTimeMillis()): RwMessage? = synchronized(sperre) {
        val ich = eigeneId ?: return null
        val roh = RwMessage(
            serverId = null,
            clientMsgId = RosenwegIds.neueClientMsgId(jetzt),
            chatId = chat.id,
            senderId = ich,
            body = text,
            ciphertext = null,
            ts = jetzt,
            editedAt = null,
            deleted = false,
            reactions = null,
            replyPreview = null,
            mediaName = null,
            status = RwStatus.AUSSTEHEND,
            read = true,
        )
        val zeile = dao.neueNachricht(roh)
        val m = roh.copy(rowId = zeile, mirroredMessageId = RosenwegIds.nachrichtId(zeile))
        dao.aendereNachricht(m)
        spiegleNachricht(chat, m)
        spiegleGespraech(chat)
        m
    }

    fun ausstehende(): List<RwMessage> = dao.ausstehende()

    fun setzeStatus(rowId: Long, status: Int): Unit = synchronized(sperre) {
        val alt = dao.nachrichtZuZeile(rowId) ?: return
        if (alt.status == status || alt.status == RwStatus.EMPFANGEN) return
        if (alt.status == RwStatus.GESENDET && status != RwStatus.GESENDET) return
        val neu = alt.copy(status = status)
        dao.aendereNachricht(neu)
        dao.chat(neu.chatId)?.let { spiegleNachricht(it, neu) }
    }

    // ── Lesestatus / Loeschen aus der Fossify-Oberflaeche ────────────────

    fun markiereThreadGelesen(threadId: Long): Unit = synchronized(sperre) {
        val chat = dao.chatZuThread(threadId) ?: return
        dao.markiereGelesen(chat.id)
    }

    fun markiereGelesen(mirrorId: Long): Unit = synchronized(sperre) {
        val m = dao.nachrichtZuZeile(RosenwegIds.zeile(mirrorId)) ?: return
        if (!m.read) dao.aendereNachricht(m.copy(read = true))
    }

    /** Gespraech in der App geloescht: lokaler Verlauf weg, Chat (und lastServerId) bleibt. */
    fun loescheVerlaufLokal(threadId: Long): Unit = synchronized(sperre) {
        val chat = dao.chatZuThread(threadId) ?: return
        dao.loescheChatNachrichten(chat.id)
    }

    /** Einzelne Nachricht endgueltig (nur lokal) geloescht. */
    fun loescheNachrichtLokal(mirrorId: Long): Unit = synchronized(sperre) {
        dao.loescheNachricht(RosenwegIds.zeile(mirrorId))
    }

    // ── Spiegel ─────────────────────────────────────────────────────────

    private fun anzeige(m: RwMessage) = anzeigeText(
        body = m.body,
        ciphertext = m.ciphertext,
        deleted = m.deleted,
        editedAt = m.editedAt,
        replyPreview = m.replyPreview,
        mediaName = m.mediaName,
        reactions = reaktionenAusJson(m.reactions),
    )

    private fun spiegleNachricht(chat: RwChat, m: RwMessage) {
        val eigen = m.senderId == eigeneId
        val typ = when {
            !eigen -> Telephony.Sms.MESSAGE_TYPE_INBOX
            m.status == RwStatus.AUSSTEHEND -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
            m.status == RwStatus.FEHLER -> Telephony.Sms.MESSAGE_TYPE_FAILED
            else -> Telephony.Sms.MESSAGE_TYPE_SENT
        }
        val absender = kontakt(m.senderId)
        app.messagesDB.insertOrUpdate(
            Message(
                id = m.mirroredMessageId,
                body = anzeige(m),
                type = typ,
                status = Telephony.Sms.STATUS_NONE,
                participants = arrayListOf(absender),
                date = (m.ts / 1000).toInt(),
                read = m.read,
                threadId = chat.threadId,
                isMMS = false,
                attachment = null,
                senderPhoneNumber = RW_ADRESSE + m.senderId,
                senderName = absender.name,
                senderPhotoUri = "",
                subscriptionId = -1,
            )
        )
    }

    private fun baueConversation(chat: RwChat): Conversation {
        val nachrichten = dao.nachrichten(chat.id)
        val letzte = nachrichten.lastOrNull()
        val alt = app.conversationsDB.getConversationWithThreadId(chat.threadId)
        val ungelesen = nachrichten.count { !it.read && it.status == RwStatus.EMPFANGEN }
        val datum = letzte?.let { (it.ts / 1000).toInt() } ?: alt?.date ?: (System.currentTimeMillis() / 1000).toInt()
        return Conversation(
            threadId = chat.threadId,
            snippet = letzte?.let { anzeige(it) } ?: "",
            date = datum,
            read = ungelesen == 0,
            title = if (alt?.usesCustomTitle == true) alt.title else titel(chat),
            photoUri = "",
            isGroupConversation = chat.kind != "dm",
            phoneNumber = RW_ADRESSE + chat.id,
            isScheduled = false,
            usesCustomTitle = alt?.usesCustomTitle ?: false,
            isArchived = alt?.isArchived ?: false,
            unreadCount = ungelesen,
        )
    }

    private fun spiegleGespraech(chat: RwChat) {
        app.conversationsDB.insertOrUpdate(baueConversation(chat))
    }

    /** Alle Spiegel neu schreiben (z.B. nach dem einmaligen Leeren der Fossify-DB). */
    fun spiegleAlles(): Unit = synchronized(sperre) {
        namen = null
        for (chat in dao.chats()) {
            dao.nachrichten(chat.id).forEach { spiegleNachricht(chat, it) }
            spiegleGespraech(chat)
        }
    }
}
