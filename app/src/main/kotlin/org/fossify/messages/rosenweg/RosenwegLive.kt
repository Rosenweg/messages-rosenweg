package org.fossify.messages.rosenweg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Fluechtige Live-Zustaende wie in der PWA: wer online ist (presence/<uid>, retained)
 * und wer gerade schreibt (typing/<conv>). Nur im Speicher, nie in der DB.
 */
object RosenwegLive {
    data class Praesenz(val online: Boolean, val ts: Long)

    private val praesenz = ConcurrentHashMap<String, Praesenz>()

    /** chatId → (userId → gueltig bis ms) */
    private val tippen = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** wird bei jeder Aenderung mit der chatId (oder null = alle) aufgerufen */
    @Volatile
    var beobachter: ((String?) -> Unit)? = null

    private const val TIPP_GUELTIG_MS = 6_000L

    fun nimmPraesenz(topic: String, payload: String) {
        val uid = topic.removePrefix("presence/").takeIf { it.isNotEmpty() && !it.contains('/') }?.let { normiereId(it) } ?: return
        val o = parseJson(payload) as? JsonObject ?: return
        praesenz[uid] = Praesenz(o.bool("online") == true, parseZeit(o["ts"]) ?: System.currentTimeMillis())
        beobachter?.invoke(null)
    }

    fun nimmTippen(topic: String, payload: String, eigeneId: String?) {
        val chatId = topic.removePrefix("typing/")
        val o = parseJson(payload) as? JsonObject ?: return
        val uid = o.str("userId")?.let { normiereId(it) } ?: return
        if (uid == eigeneId) return
        val m = tippen.getOrPut(chatId) { ConcurrentHashMap() }
        if (o.bool("typing") == true) m[uid] = System.currentTimeMillis() + TIPP_GUELTIG_MS else m.remove(uid)
        beobachter?.invoke(chatId)
    }

    /** Absender hat geschrieben → "schreibt" sofort beenden */
    fun nachrichtVon(chatId: String, uid: String) {
        if (tippen[chatId]?.remove(uid) != null) beobachter?.invoke(chatId)
    }

    fun schreibende(chatId: String): List<String> {
        val jetzt = System.currentTimeMillis()
        return tippen[chatId]?.filterValues { it > jetzt }?.keys?.toList() ?: emptyList()
    }

    fun praesenz(uid: String): Praesenz? = praesenz[uid]

    fun tippNutzlast(eigeneId: String, tippt: Boolean): String = buildJsonObject {
        put("userId", eigeneId)
        put("typing", tippt)
    }.toString()
}
