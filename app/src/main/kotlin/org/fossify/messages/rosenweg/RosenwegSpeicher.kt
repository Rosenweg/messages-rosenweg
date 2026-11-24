package org.fossify.messages.rosenweg

import android.content.Context
import android.provider.Telephony
import android.util.Log
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

    private val avatare = RosenwegAvatare(app)

    @Volatile
    private var personen: Map<String, RwUser>? = null

    /** Quittungen fuer den Erstimport nur fuer juengere Nachrichten senden (keine Flut) */
    private val QUITTUNG_ERSTIMPORT_MS = 7L * 24 * 60 * 60 * 1000

    companion object {
        private const val TAG = "RosenwegSpeicher"
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

    private fun personen(): Map<String, RwUser> =
        personen ?: dao.benutzer().associateBy { it.id }.also { personen = it }

    private fun namen(): Map<String, String> = personen().mapValues { it.value.name }

    /**
     * Uebernimmt /api/directory (+ eigenes Bild aus dem Anlage-Token). Hat sich ein
     * Name oder Bild geaendert, werden alle Spiegel neu geschrieben. Rueckgabe: geaendert.
     */
    fun speichereVerzeichnis(eintraege: Map<String, RwPerson>, eigenesBild: String? = null): Boolean = synchronized(sperre) {
        val neu = eintraege.map { RwUser(it.key, it.value.name, it.value.avatar) }.toMutableList()
        val ich = eigeneId
        if (ich != null && (eigenesBild != null || personen()[ich] != null)) {
            neu.add(RwUser(ich, RosenwegKonto.bekannt()?.name ?: name(ich), eigenesBild ?: personen()[ich]?.avatar))
        }
        if (neu.isEmpty()) return false
        val alt = personen()
        val geaendert = neu.any { alt[it.id] != it }
        if (!geaendert) return false
        dao.speichereBenutzer(neu)
        personen = null
        spiegleAllesIntern()
        true
    }

    private fun personenBild(userId: String): String = avatare.bild(personen()[userId]?.avatar)

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

    /** Gegenueber einer DM (null fuer Gruppen) */
    fun dmPartner(chat: RwChat): String? = if (chat.kind == "dm") mitglieder(chat).firstOrNull { it != eigeneId } else null

    fun mitgliederIds(chat: RwChat): List<String> = mitglieder(chat)

    /** Verzeichnis ohne sich selbst, nach Namen sortiert (fuer "Hinzufügen") */
    fun personenListe(): List<RwUser> = personen().values.filter { it.id != eigeneId }.sortedBy { it.name.lowercase() }

    /** Geteilte Medien eines Chats (neueste zuerst) */
    fun medien(chatId: String): List<RwMessage> =
        dao.nachrichten(chatId).filter { it.mediaName != null && !it.deleted }.reversed()

    // ── E2E: Verlauf von anderen Geraeten, nachtraegliches Entschluesseln ──

    fun speichereBackfill(eintraege: List<RwBackfillEintrag>): Unit = synchronized(sperre) {
        if (eintraege.isEmpty()) return
        dao.speichereBackfill(eintraege.map { RwBackfill(it.convId, it.origMsgId, it.ciphertext) })
    }

    /**
     * Hinterlegten Verlauf anwenden: passende (unlesbare) DM per Server-ID suchen und
     * mit dem neu verschluesselten Buendel lesbar machen. Rueckgabe: Anzahl gelesen.
     */
    fun wendeBackfillAn(entschluessele: (String) -> String?): Int = synchronized(sperre) {
        var n = 0
        for (e in dao.backfill()) {
            val chat = dao.chat(e.convId)
            val m = chat?.let { dao.nachrichtZuServerId(it.id, e.origMsgId) } ?: continue
            if (m.body == null && !m.deleted) {
                val klar = entschluessele(e.ciphertext)
                if (klar != null) {
                    val neu = mitKlartext(m, klar)
                    dao.aendereNachricht(neu)
                    spiegleNachricht(chat, neu)
                    n++
                }
            }
            dao.loescheBackfill(e.convId, e.origMsgId)
        }
        if (n > 0) dao.chats().forEach { spiegleGespraech(it) }
        n
    }

    private fun mitKlartext(m: RwMessage, klar: String): RwMessage {
        val medien = RwKrypto.medienAusKlartext(klar)
        return m.copy(
            body = medien?.first ?: klar,
            plain = klar,
            media = medien?.second ?: m.media,
            mediaName = medien?.second?.let { medienNameAusJson(it) } ?: m.mediaName,
        )
    }

    /** Lesbare DMs eines Chats (Server-ID, Absender, Zeit, Klartext) fuer die Weitergabe an neue Geraete */
    fun lesbareDms(chatId: String): List<RwMessage> =
        dao.nachrichten(chatId).filter { it.serverId != null && it.plain != null && !it.deleted }

    /** Anderer Benutzer auf dem geteilten Telefon: alles Rosenweg-Lokale loeschen. */
    fun allesLoeschen(): Unit = synchronized(sperre) {
        dao.chats().forEach {
            app.messagesDB.deleteRosenwegMirrorThread(it.threadId)
            app.conversationsDB.deleteThreadId(it.threadId)
        }
        try {
            app.conversationsDB.getRosenwegThreadIds().forEach { app.conversationsDB.deleteThreadId(it) }
            app.messagesDB.getRosenwegMirrorThreadIds().forEach { app.messagesDB.deleteRosenwegMirrorThread(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Spiegel loeschen", e)
        }
        dao.loescheAlleNachrichten()
        dao.loescheAlleChats()
        dao.loescheAlleBenutzer()
        dao.loescheAllesBackfill()
        personen = null
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
            photoUri = personenBild(userId),
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
                avatar = g.avatar,
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
                // Der Verlauf ist die Wahrheit fuer Bearbeiten/Loeschen/Reaktionen;
                // eine bisher unlesbare DM wird lesbar, sobald ein Klartext kommt
                body = when {
                    d.deleted -> alt.body
                    ausVerlauf -> d.body ?: alt.body
                    else -> alt.body ?: d.body
                },
                plain = alt.plain ?: d.plain,
                media = alt.media ?: d.mediaJson,
                mediaName = alt.mediaName ?: d.mediaName,
                ciphertext = if (ausVerlauf) d.ciphertext ?: alt.ciphertext else alt.ciphertext,
                editedAt = if (ausVerlauf) d.editedAt ?: alt.editedAt else alt.editedAt,
                deleted = alt.deleted || d.deleted,
                reactions = if (ausVerlauf) reaktionenZuJson(d.reactions) else alt.reactions,
                rcptNZugestellt = d.quittung?.zugestellt ?: alt.rcptNZugestellt,
                rcptNGelesen = d.quittung?.gelesen ?: alt.rcptNGelesen,
                rcptVon = d.quittung?.von ?: alt.rcptVon,
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
                // fremde Nachricht: einmal "zugestellt" melden (Erstimport nur die letzten 7 Tage)
                rcptSoll = when {
                    eigen || d.clientMsgId == null -> 0
                    gelesen && d.ts < System.currentTimeMillis() - QUITTUNG_ERSTIMPORT_MS -> 2
                    else -> RwQuittung.ZUGESTELLT
                },
                rcptGesendet = if (!eigen && gelesen && d.ts < System.currentTimeMillis() - QUITTUNG_ERSTIMPORT_MS) 2 else 0,
                rcptNZugestellt = d.quittung?.zugestellt ?: 0,
                rcptNGelesen = d.quittung?.gelesen ?: 0,
                rcptVon = d.quittung?.von,
                plain = d.plain,
                media = d.mediaJson,
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
    /** Rosenweg-Nachricht zu einer gespiegelten Fossify-ID (negativ) */
    fun nachrichtZuSpiegel(id: Long): RwMessage? = if (id < 0) dao.nachrichtZuZeile(RosenwegIds.zeile(id)) else null

    /** Kurzer Text fuer Antwort-Vorschau (ohne Reaktionen/Zitat) */
    fun vorschau(m: RwMessage): String = when {
        m.deleted -> RW_TEXT_GELOESCHT
        m.body != null -> m.body
        m.mediaName != null -> "📎 ${m.mediaName}"
        else -> RW_TEXT_VERSCHLUESSELT
    }

    fun neueAusgehende(
        chat: RwChat,
        text: String,
        jetzt: Long = System.currentTimeMillis(),
        antwortAuf: RwMessage? = null,
    ): RwMessage? = synchronized(sperre) {
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
            mediaName = null,
            status = RwStatus.AUSSTEHEND,
            read = true,
            plain = if (chat.kind == "dm") text else null,
            replyPreview = antwortAuf?.let { "${name(it.senderId)}: ${vorschau(it).take(80)}" },
            replyTo = antwortAuf?.clientMsgId?.let { baueReplyTo(it, antwortAuf.senderId, vorschau(antwortAuf), chat.kind == "dm") },
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

    private fun baueMessage(chat: RwChat, m: RwMessage): Message {
        val eigen = m.senderId == eigeneId
        val typ = when {
            !eigen -> Telephony.Sms.MESSAGE_TYPE_INBOX
            m.status == RwStatus.AUSSTEHEND -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
            m.status == RwStatus.FEHLER -> Telephony.Sms.MESSAGE_TYPE_FAILED
            else -> Telephony.Sms.MESSAGE_TYPE_SENT
        }
        val absender = kontakt(m.senderId)
        val status = if (eigen && m.status == RwStatus.GESENDET) haken(chat, m) else Telephony.Sms.STATUS_NONE
        return Message(
            id = m.mirroredMessageId,
            body = anzeige(m),
            type = typ,
            status = status,
            participants = arrayListOf(absender),
            date = (m.ts / 1000).toInt(),
            read = m.read,
            threadId = chat.threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = RW_ADRESSE + m.senderId,
            senderName = absender.name,
            senderPhotoUri = absender.photoUri,
            subscriptionId = -1,
        )
    }

    private fun spiegleNachricht(chat: RwChat, m: RwMessage) {
        app.messagesDB.insertOrUpdate(baueMessage(chat, m))
    }

    /**
     * Nachrichten eines Rosenweg-Threads direkt aus dem Speicher (nicht aus dem
     * Fossify-Spiegel), damit ein beschaedigter/fehlender Spiegel den Thread nie
     * leer erscheinen laesst. Fehlt der Spiegel, wird er nachgefuehrt.
     */
    fun fossifyNachrichten(threadId: Long, dateFrom: Int, limit: Int): ArrayList<Message> = synchronized(sperre) {
        val chat = dao.chatZuThread(threadId)
        if (chat == null) {
            Log.w(TAG, "kein Rosenweg-Chat fuer Thread $threadId")
            return ArrayList()
        }
        val papierkorb = try {
            app.messagesDB.getRecycledRosenwegIds().toHashSet()
        } catch (e: Exception) {
            Log.w(TAG, "Papierkorb nicht lesbar", e)
            HashSet()
        }
        val alle = dao.nachrichten(chat.id)
        try {
            val gespiegelt = app.messagesDB.getThreadMessageIds(threadId).toHashSet()
            val fehlend = alle.filter { it.mirroredMessageId !in gespiegelt }
            if (fehlend.isNotEmpty()) {
                Log.w(TAG, "Spiegel unvollstaendig: chat=${chat.id} thread=$threadId speicher=${alle.size} spiegel=${gespiegelt.size} - ergaenze ${fehlend.size}")
                fehlend.forEach { spiegleNachricht(chat, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spiegel-Pruefung ${chat.id}", e)
        }
        ArrayList(
            alle.asSequence()
                .filter { it.mirroredMessageId !in papierkorb }
                .filter { dateFrom == -1 || (it.ts / 1000) < dateFrom }
                .map { baueMessage(chat, it) }
                .toList()
                .takeLast(limit)
        )
    }

    /**
     * Pruefung beim Dienststart: Spiegel aller Chats vollstaendig neu schreiben,
     * verwaiste Rosenweg-Gespraeche/-Nachrichten (Thread ohne Chat) entfernen, Zahlen loggen.
     */
    fun pruefeSpiegel(): Unit = synchronized(sperre) {
        personen = null
        val chats = dao.chats()
        val threads = chats.map { it.threadId }.toSet()
        try {
            Log.i(TAG, "Spiegel-Pruefung: ${app.messagesDB.countRosenwegMirror()} Rosenweg-Zeilen im Fossify-Cache vor dem Neuschreiben")
        } catch (e: Exception) {
            Log.w(TAG, "Spiegel zaehlen", e)
        }
        for (chat in chats) {
            val alle = dao.nachrichten(chat.id)
            val vorher = try {
                app.messagesDB.getThreadMessageIds(chat.threadId).size
            } catch (e: Exception) {
                -1
            }
            alle.forEach { spiegleNachricht(chat, it) }
            spiegleGespraech(chat)
            Log.i(TAG, "Spiegel: chat=${chat.id} thread=${chat.threadId} speicher=${alle.size} spiegel_vorher=$vorher")
        }
        try {
            app.conversationsDB.getRosenwegThreadIds().filter { it !in threads }.forEach {
                Log.w(TAG, "verwaistes Rosenweg-Gespraech $it entfernt")
                app.conversationsDB.deleteThreadId(it)
            }
            app.messagesDB.getRosenwegMirrorThreadIds().filter { it !in threads }.forEach {
                Log.w(TAG, "verwaiste Rosenweg-Nachrichten in Thread $it entfernt")
                app.messagesDB.deleteRosenwegMirrorThread(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pruefung verwaister Spiegel", e)
        }
    }

    private fun baueConversation(chat: RwChat): Conversation {
        val nachrichten = dao.nachrichten(chat.id)
        val letzte = nachrichten.lastOrNull()
        val alt = app.conversationsDB.getConversationWithThreadId(chat.threadId)
        val ungelesen = nachrichten.count { !it.read && it.status == RwStatus.EMPFANGEN }
        // ohne Nachrichten: Datum 0 (unten in der Liste), leerer Ausschnitt
        val datum = letzte?.let { (it.ts / 1000).toInt() } ?: 0
        return Conversation(
            threadId = chat.threadId,
            snippet = letzte?.let { anzeige(it) } ?: "",
            date = datum,
            read = ungelesen == 0,
            title = if (alt?.usesCustomTitle == true) alt.title else titel(chat),
            photoUri = gespraechsBild(chat),
            isGroupConversation = chat.kind != "dm",
            phoneNumber = RW_ADRESSE + chat.id,
            isScheduled = false,
            usesCustomTitle = alt?.usesCustomTitle ?: false,
            isArchived = alt?.isArchived ?: false,
            unreadCount = ungelesen,
        )
    }

    /** Bild wie in der PWA: DM = Person, sonst eigenes Logo / Standardlogo / 🚨📢 */
    fun gespraechsBild(chat: RwChat): String =
        if (chat.kind == "dm") {
            mitglieder(chat).firstOrNull { it != eigeneId }?.let { personenBild(it) } ?: ""
        } else {
            avatare.gespraech(chat.id, chat.kind, chat.avatar)
        }

    fun personenBildFuer(userId: String): String = personenBild(userId)

    // ── Quittungen / Haken ──────────────────────────────────────────────

    private fun liste(text: String?): Set<String> =
        (text?.let { parseJson(it) } as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()

    private fun listeJson(ids: Set<String>) = JsonArray(ids.map { JsonPrimitive(it) }).toString()

    private fun haken(chat: RwChat, m: RwMessage): Int {
        val von = m.rcptVon ?: if (chat.kind == "dm") 1 else mitglieder(chat).count { it != eigeneId }
        val gelesen = liste(m.rcptGelesen)
        val zugestellt = liste(m.rcptZugestellt) + gelesen
        return RwQuittung.haken(maxOf(m.rcptNZugestellt, zugestellt.size), maxOf(m.rcptNGelesen, gelesen.size), von)
    }

    /** Eingehende Quittung fuer eine eigene Nachricht. Rueckgabe: Haken geaendert. */
    fun nimmQuittung(q: RwQuittung.Eingang): Boolean = synchronized(sperre) {
        if (q.userId == eigeneId) return false
        val chat = dao.chat(q.chatId) ?: return false
        val m = dao.nachricht(chat.id, q.clientMsgId) ?: return false
        if (m.senderId != eigeneId) return false
        val zugestellt = liste(m.rcptZugestellt) + q.userId
        val gelesen = if (q.stufe >= RwQuittung.GELESEN) liste(m.rcptGelesen) + q.userId else liste(m.rcptGelesen)
        val neu = m.copy(rcptZugestellt = listeJson(zugestellt), rcptGelesen = listeJson(gelesen))
        if (neu == m) return false
        val vorher = haken(chat, m)
        dao.aendereNachricht(neu)
        val nachher = haken(chat, neu)
        if (vorher != nachher) spiegleNachricht(chat, neu)
        vorher != nachher
    }

    /** Thread wird angezeigt: fuer alle fremden Nachrichten "gelesen" vormerken. */
    fun quittiereGelesen(threadId: Long): Boolean = synchronized(sperre) {
        val chat = dao.chatZuThread(threadId) ?: return false
        val ich = eigeneId ?: return false
        dao.quittungGelesenSoll(chat.id, ich) > 0
    }

    fun offeneQuittungen(): List<RwMessage> = dao.offeneQuittungen()

    fun quittungGesendet(rowId: Long, stufe: Int) = dao.quittungGesendet(rowId, stufe)

    private fun spiegleGespraech(chat: RwChat) {
        app.conversationsDB.insertOrUpdate(baueConversation(chat))
    }

    /** Alle Spiegel neu schreiben (z.B. nach dem einmaligen Leeren der Fossify-DB). */
    fun spiegleAlles(): Unit = synchronized(sperre) {
        personen = null
        spiegleAllesIntern()
    }

    private fun spiegleAllesIntern() {
        for (chat in dao.chats()) {
            dao.nachrichten(chat.id).forEach { spiegleNachricht(chat, it) }
            spiegleGespraech(chat)
        }
    }
}
