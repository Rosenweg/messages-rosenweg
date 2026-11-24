package org.fossify.messages.rosenweg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.random.Random

/**
 * Rosenweg Messenger: reine Protokoll-Logik (ohne Android-Abhaengigkeiten, daher
 * per JVM-Unit-Test pruefbar). Massgeblich ist rosenweg-messenger/docs/protokoll.md.
 */

/** Adress-Praefix fuer Rosenweg-Teilnehmer/-Gespraeche in den Fossify-Tabellen. */
const val RW_ADRESSE = "rw:"

/** Absender einer Nachricht, deren Topic keine Absender-Ebene hat (Dienste/Sensoren). */
const val RW_SYSTEM_ABSENDER = "system"

/** Basis der negativen Thread-IDs: Thread = -(BASIS + Chat-Laufnummer). */
const val RW_THREAD_BASIS = 1_000_000_000_000L

/** Rosenweg-Threads haben negative IDs (SMS/MMS-Threads des Systems sind immer positiv). */
fun Long.isRosenwegThread() = this < 0

fun String.isRosenwegAdresse() = startsWith(RW_ADRESSE)

object RosenwegIds {
    fun threadId(chatSeq: Long): Long = -(RW_THREAD_BASIS + chatSeq)

    fun chatSeq(threadId: Long): Long = -threadId - RW_THREAD_BASIS

    /** Gespiegelte Fossify-Nachricht: ID = -(Zeilennummer im Rosenweg-Speicher). */
    fun nachrichtId(rowId: Long): Long = -rowId

    fun zeile(nachrichtId: Long): Long = -nachrichtId

    private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Wie die PWA: "c_<ms>_<6 Zeichen base36>". */
    fun neueClientMsgId(nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
        val suffix = (1..6).map { BASE36[random.nextInt(BASE36.length)] }.joinToString("")
        return "c_${nowMs}_$suffix"
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseJson(text: String): JsonElement? = try {
    json.parseToJsonElement(text)
} catch (_: Exception) {
    null
}

// ── kleine Hilfen fuer lose typisierte JSON-Felder ──────────────────────

internal fun JsonObject.str(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return (e as? JsonPrimitive)?.contentOrNull
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.lng(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** Zeitstempel: Zahl (ms oder s) oder ISO-Text (Postgres timestamptz). */
fun parseZeit(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    p.longOrNull?.let { return if (it < 100_000_000_000L) it * 1000 else it }
    val text = p.contentOrNull ?: return null
    text.toDoubleOrNull()?.let { val v = it.toLong(); return if (v < 100_000_000_000L) v * 1000 else v }
    return try {
        Instant.parse(text).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                // Postgres-Textform "2026-11-24 10:00:00.123+00"
                OffsetDateTime.parse(text.replace(' ', 'T').replace(Regex("([+-]\\d\\d)$"), "$1:00"))
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Benutzer-IDs immer als Text vergleichen; "42.0" aus einer JSON-Zahl → "42". */
fun normiereId(id: String): String {
    val t = id.trim()
    val d = t.toDoubleOrNull()
    return if (d != null && !t.contains('e', true) && d == Math.floor(d) && t.contains('.')) d.toLong().toString() else t
}

// ── JWT ─────────────────────────────────────────────────────────────────

data class JwtAngaben(
    /** immer als Text (das JWT kann die ID als Zahl tragen) */
    val sub: String?,
    val name: String?,
    val exp: Long?,
    val pubTopics: List<String>,
    val subTopics: List<String>,
)

/** Liest die Nutzlast eines JWT (ohne Pruefung der Signatur - das macht der Server). */
fun leseJwt(token: String): JwtAngaben? {
    val teile = token.split('.')
    if (teile.size < 2) return null
    val nutzlast = try {
        String(Base64.getUrlDecoder().decode(teile[1].trimEnd('=')), Charsets.UTF_8)
    } catch (_: Exception) {
        return null
    }
    val o = parseJson(nutzlast) as? JsonObject ?: return null
    fun liste(key: String) = o.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    return JwtAngaben(
        sub = o.str("sub")?.let { normiereId(it) },
        name = o.str("name")?.takeIf { it.isNotBlank() },
        exp = o.lng("exp"),
        pubTopics = liste("pub_topics"),
        subTopics = liste("sub_topics"),
    )
}

/**
 * Darf mit diesen pub_topics in das Gespraech geschrieben werden? Das JWT traegt
 * Praefixe (z.B. "group/grp-1"); geschrieben wird nach "<prefix>/msg/<uid>".
 */
fun darfSchreiben(pubTopics: List<String>, topicPrefix: String): Boolean =
    pubTopics.any { it == topicPrefix || topicPrefix.startsWith("$it/") || it.startsWith("$topicPrefix/msg") }

// ── Topics ──────────────────────────────────────────────────────────────

object RosenwegTopics {
    fun sendeTopic(topicPrefix: String, eigeneId: String) = "$topicPrefix/msg/$eigeneId"
    fun empfangsFilter(topicPrefix: String) = "$topicPrefix/msg/#"
    fun control(chatId: String) = "control/$chatId"
    fun presence(eigeneId: String) = "presence/$eigeneId"
    const val KRISE = "sys/crisis"

    /** Absender aus "<kind>/<conv>/msg/<senderId>"; ohne Absender-Ebene = System. */
    fun absenderAusTopic(topic: String): String {
        val teile = topic.split('/')
        return teile.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { normiereId(it) } ?: RW_SYSTEM_ABSENDER
    }

    /** "<kind>/<conv>/msg[/...]" → Paar (topicPrefix, convId), sonst null. */
    fun nachrichtTopic(topic: String): Pair<String, String>? {
        val teile = topic.split('/')
        if (teile.size < 3 || teile[2] != "msg") return null
        if (teile[0] !in setOf("group", "dm", "bcast")) return null
        return "${teile[0]}/${teile[1]}" to teile[1]
    }
}

// ── Nachrichten ─────────────────────────────────────────────────────────

/** Eine Nachricht, wie sie live (MQTT) oder aus /api/history ankommt. */
data class RwNachrichtDaten(
    val serverId: Long?,
    val clientMsgId: String?,
    val senderId: String,
    val body: String?,
    val ciphertext: String?,
    val ts: Long,
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val replyPreview: String? = null,
    val mediaName: String? = null,
    val priority: String? = null,
    val clear: Boolean = false,
    /** emoji → Benutzer-IDs */
    val reactions: Map<String, List<String>> = emptyMap(),
)

/** Live-Nachricht; Absender kommt aus dem Topic, nie aus payload.senderId. */
fun parseLiveNachricht(topic: String, payload: String, jetztMs: Long = System.currentTimeMillis()): RwNachrichtDaten? {
    val o = parseJson(payload) as? JsonObject ?: return null
    return RwNachrichtDaten(
        serverId = null,
        clientMsgId = o.str("clientMsgId") ?: o.str("id"),
        senderId = RosenwegTopics.absenderAusTopic(topic),
        body = o.str("body"),
        ciphertext = (o["ciphertext"] ?: o["bundle"])?.takeIf { it !is JsonNull }?.toString(),
        ts = parseZeit(o["ts"]) ?: jetztMs,
        replyPreview = o.obj("replyTo")?.str("preview"),
        mediaName = o.obj("media")?.let { it.str("filename") ?: it.str("mtype") ?: "Datei" },
        priority = o.str("priority"),
        clear = o.bool("clear") == true,
    )
}

fun parseVerlauf(text: String): List<RwNachrichtDaten> {
    val o = parseJson(text) as? JsonObject ?: return emptyList()
    return o.arr("messages")?.mapNotNull { e ->
        val m = e as? JsonObject ?: return@mapNotNull null
        val reactions = LinkedHashMap<String, List<String>>()
        m.arr("reactions")?.forEach { r ->
            val ro = r as? JsonObject ?: return@forEach
            val emoji = ro.str("emoji") ?: return@forEach
            reactions[emoji] = ro.arr("users")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { u -> normiereId(u) } } ?: emptyList()
        }
        val replyTo = m["reply_to"]
        val replyObj = when (replyTo) {
            is JsonObject -> replyTo
            is JsonPrimitive -> replyTo.contentOrNull?.let { parseJson(it) as? JsonObject }
            else -> null
        }
        val media = when (val me = m["media"]) {
            is JsonObject -> me
            is JsonPrimitive -> me.contentOrNull?.let { parseJson(it) as? JsonObject }
            else -> null
        }
        val ct = m["ciphertext"]
        RwNachrichtDaten(
            serverId = m.lng("id"),
            clientMsgId = m.str("client_msg_id"),
            senderId = m.str("sender_id")?.let { normiereId(it) } ?: RW_SYSTEM_ABSENDER,
            body = m.str("body"),
            ciphertext = ct?.takeIf { it !is JsonNull }?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() },
            ts = parseZeit(m["created_at"]) ?: 0L,
            editedAt = parseZeit(m["edited_at"]),
            deleted = m.bool("deleted") == true || (m["deleted_at"]?.let { it !is JsonNull } ?: false),
            replyPreview = replyObj?.str("preview"),
            mediaName = media?.let { it.str("filename") ?: it.str("mtype") ?: "Datei" },
            reactions = reactions,
        )
    } ?: emptyList()
}

/** Nutzlast einer ausgehenden Text-Nachricht (Gruppe/Ankuendigung). */
fun baueAusgehend(senderId: String, clientMsgId: String, ts: Long, body: String): String =
    buildJsonObject {
        put("senderId", senderId)
        put("clientMsgId", clientMsgId)
        put("ts", ts)
        put("body", body)
    }.toString()

fun bauePresence(online: Boolean, ts: Long = System.currentTimeMillis()): String =
    buildJsonObject {
        put("online", online)
        put("ts", ts)
    }.toString()

// ── Gespraeche / Verzeichnis ────────────────────────────────────────────

data class RwGespraechDaten(
    val id: String,
    val kind: String,
    val title: String?,
    val topicPrefix: String,
    val metadata: String?,
    val role: String?,
    val participants: List<String>,
    val updatedAt: Long?,
)

fun parseGespraeche(text: String): List<RwGespraechDaten> {
    val o = parseJson(text) as? JsonObject ?: return emptyList()
    return o.arr("conversations")?.mapNotNull { e ->
        val c = e as? JsonObject ?: return@mapNotNull null
        val id = c.str("id") ?: return@mapNotNull null
        val kind = c.str("kind") ?: "group"
        val meta = when (val me = c["metadata"]) {
            is JsonObject -> me
            is JsonPrimitive -> me.contentOrNull?.let { parseJson(it) as? JsonObject }
            else -> null
        }
        RwGespraechDaten(
            id = id,
            kind = kind,
            title = c.str("title")?.takeIf { it.isNotBlank() },
            topicPrefix = c.str("topic_prefix") ?: "$kind/$id",
            metadata = meta?.let {
                // Avatar (data:-URL, bis 400 kB) nicht mitspeichern
                JsonObject(it.filterKeys { k -> k != "avatar" }).toString()
            },
            role = c.str("role"),
            participants = meta?.arr("participants")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { p -> normiereId(p) } } ?: emptyList(),
            updatedAt = parseZeit(c["updated_at"]),
        )
    } ?: emptyList()
}

fun parseVerzeichnis(text: String): Map<String, String> {
    val o = parseJson(text) as? JsonObject ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    o.arr("users")?.forEach { e ->
        val u = e as? JsonObject ?: return@forEach
        val id = u.str("id")?.let { normiereId(it) } ?: return@forEach
        result[id] = u.str("name") ?: u.str("email") ?: id
    }
    return result
}

fun parseMitglieder(text: String): List<String> {
    val o = parseJson(text) as? JsonObject ?: return emptyList()
    return o.arr("members")?.mapNotNull { (it as? JsonObject)?.str("userId")?.let { u -> normiereId(u) } } ?: emptyList()
}

// ── control/<conv> ──────────────────────────────────────────────────────

sealed class RwSteuerung {
    data class Reaktion(val clientMsgId: String, val userId: String, val emoji: String, val entfernen: Boolean) : RwSteuerung()
    data class Loeschen(val clientMsgId: String) : RwSteuerung()
    data class Bearbeiten(val clientMsgId: String, val body: String, val editedAt: Long) : RwSteuerung()
    data class Gruppe(val action: String?, val title: String?) : RwSteuerung()
}

fun parseSteuerung(payload: String, jetztMs: Long = System.currentTimeMillis()): RwSteuerung? {
    val o = parseJson(payload) as? JsonObject ?: return null
    return when (o.str("type")) {
        "reaction" -> {
            val id = o.str("clientMsgId") ?: return null
            val user = o.str("userId")?.let { normiereId(it) } ?: return null
            val emoji = o.str("emoji") ?: return null
            RwSteuerung.Reaktion(id, user, emoji, o.str("op") == "remove")
        }
        "delete" -> RwSteuerung.Loeschen(o.str("clientMsgId") ?: return null)
        "edit" -> RwSteuerung.Bearbeiten(
            o.str("clientMsgId") ?: return null,
            o.str("body") ?: return null,
            parseZeit(o["editedAt"]) ?: jetztMs
        )
        "group" -> RwSteuerung.Gruppe(o.str("action"), o.str("title"))
        else -> null
    }
}

// ── Reaktionen als JSON im Speicher ─────────────────────────────────────

fun reaktionenZuJson(r: Map<String, List<String>>): String =
    JsonObject(r.mapValues { (_, users) -> JsonArray(users.map { JsonPrimitive(it) }) }).toString()

fun reaktionenAusJson(text: String?): Map<String, List<String>> {
    if (text.isNullOrBlank()) return emptyMap()
    val o = parseJson(text) as? JsonObject ?: return emptyMap()
    return o.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList() }
        .filterValues { it.isNotEmpty() }
}

fun wendeReaktionAn(r: Map<String, List<String>>, userId: String, emoji: String, entfernen: Boolean): Map<String, List<String>> {
    val neu = LinkedHashMap(r)
    val users = (neu[emoji] ?: emptyList()).filter { it != userId }.toMutableList()
    if (!entfernen) users.add(userId)
    if (users.isEmpty()) neu.remove(emoji) else neu[emoji] = users
    return neu
}

// ── Anzeige-Text der gespiegelten Fossify-Nachricht ─────────────────────

const val RW_TEXT_VERSCHLUESSELT = "🔒 Verschlüsselte Nachricht"
const val RW_TEXT_GELOESCHT = "🚫 Nachricht gelöscht"

fun anzeigeText(
    body: String?,
    ciphertext: String?,
    deleted: Boolean,
    editedAt: Long?,
    replyPreview: String?,
    mediaName: String?,
    reactions: Map<String, List<String>>,
): String {
    if (deleted) return RW_TEXT_GELOESCHT
    val sb = StringBuilder()
    if (!replyPreview.isNullOrBlank()) sb.append("↪ ").append(replyPreview.take(80)).append('\n')
    when {
        body != null -> sb.append(body)
        ciphertext != null -> sb.append(RW_TEXT_VERSCHLUESSELT)
    }
    if (mediaName != null) {
        if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
        sb.append("📎 ").append(mediaName)
    }
    if (editedAt != null) sb.append(" (bearbeitet)")
    if (reactions.isNotEmpty()) {
        sb.append(" · ")
        sb.append(reactions.entries.joinToString(" ") { (emoji, users) -> "$emoji${users.size}" })
    }
    return sb.toString()
}
