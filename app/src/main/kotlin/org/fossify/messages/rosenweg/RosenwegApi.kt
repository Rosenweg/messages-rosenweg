package org.fossify.messages.rosenweg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** REST am message-store (chat.rosenweg4303.ch/api/...), blockierend. */
class RosenwegApi(private val konto: RosenwegKonto) {

    class HttpFehler(val code: Int, msg: String) : IOException(msg)

    data class MqttZugang(val token: String, val expiresInSek: Long, val angaben: JwtAngaben?)

    fun mqttToken(deviceId: String): MqttZugang {
        val antwort = anfrage("POST", "/api/token", buildJsonObject { put("deviceId", deviceId) }.toString())
        val o = parseJson(antwort) as? JsonObject ?: throw IOException("token: keine JSON-Antwort")
        val token = o.str("token") ?: throw IOException("token: fehlt")
        return MqttZugang(token, o.lng("expiresIn") ?: 3600L, leseJwt(token))
    }

    fun gespraeche(): List<RwGespraechDaten> = parseGespraeche(anfrage("GET", "/api/conversations"))

    fun verlauf(chatId: String, since: Long, limit: Int = 500): List<RwNachrichtDaten> {
        val q = if (since > 0) "?since=$since&limit=$limit" else "?limit=$limit"
        return parseVerlauf(anfrage("GET", "/api/history/${enc(chatId)}$q"))
    }

    fun verzeichnis(): Map<String, RwPerson> = parseVerzeichnis(anfrage("GET", "/api/directory"))

    fun mitglieder(chatId: String): List<String> = parseMitglieder(anfrage("GET", "/api/conversations/${enc(chatId)}/members"))

    /** Geraet + oeffentlichen Schluessel registrieren (idempotent, setzt last_seen). */
    fun registriereGeraet(deviceId: String, label: String, pubkey: String) {
        anfrage("POST", "/api/devices", buildJsonObject {
            put("deviceId", deviceId)
            put("label", label)
            put("pubkey", pubkey)
            put("alg", RwKrypto.ALG)
        }.toString())
    }

    fun schluessel(userId: String): List<RwGeraet> = parseSchluessel(anfrage("GET", "/api/keys/${enc(userId)}"))

    fun eigeneGeraete(): List<RwGeraet> = parseEigeneGeraete(anfrage("GET", "/api/link/devices"))

    /** Liest UND loescht (serverseitig) den fuer dieses Geraet hinterlegten Verlauf. */
    fun backfillHolen(deviceId: String): List<RwBackfillEintrag> =
        parseBackfill(anfrage("GET", "/api/link/backfill?deviceId=${enc(deviceId)}"))

    /** entriesJson = JSON-Liste [{convId, origMsgId, senderId, createdAt, ciphertext}] */
    fun backfillSenden(zielGeraet: String, entriesJson: String) {
        anfrage("POST", "/api/link/backfill", """{"targetDeviceId":${kotlinx.serialization.json.JsonPrimitive(zielGeraet)},"entries":$entriesJson}""")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ── Gruppen-Verwaltung (wie pwa/app.js) ─────────────────────────────

    data class Mitglied(val userId: String, val role: String)

    fun mitgliederMitRolle(chatId: String): List<Mitglied> {
        val o = parseJson(anfrage("GET", "/api/conversations/${enc(chatId)}/members")) as? JsonObject ?: return emptyList()
        return o.arr("members")?.mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            Mitglied(m.str("userId")?.let { normiereId(it) } ?: return@mapNotNull null, m.str("role") ?: "member")
        } ?: emptyList()
    }

    fun umbenennen(chatId: String, titel: String) {
        anfrage("PATCH", "/api/conversations/${enc(chatId)}", buildJsonObject { put("title", titel) }.toString())
    }

    /** dataUrl = null entfernt das eigene Logo */
    fun setzeLogo(chatId: String, dataUrl: String?) {
        anfrage("PATCH", "/api/conversations/${enc(chatId)}", buildJsonObject { put("avatar", dataUrl) }.toString())
    }

    fun mitgliederHinzufuegen(chatId: String, userIds: List<String>) {
        anfrage("POST", "/api/conversations/${enc(chatId)}/members", buildJsonObject {
            put("userIds", kotlinx.serialization.json.JsonArray(userIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString())
    }

    fun mitgliedEntfernen(chatId: String, userId: String) {
        anfrage("DELETE", "/api/conversations/${enc(chatId)}/members/${enc(userId)}")
    }

    fun rolleSetzen(chatId: String, userId: String, rolle: String) {
        anfrage("POST", "/api/conversations/${enc(chatId)}/members/${enc(userId)}/role", buildJsonObject { put("role", rolle) }.toString())
    }

    companion object {
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        private val JSON = "application/json".toMediaType()
    }

    // OkHttp statt HttpURLConnection: PATCH (Umbenennen/Logo) kann HttpURLConnection nicht
    private fun anfrage(methode: String, pfad: String, body: String? = null): String {
        val rumpf = body?.toRequestBody(JSON) ?: if (methode == "GET" || methode == "DELETE") null else ByteArray(0).toRequestBody(JSON)
        val anfrage = Request.Builder()
            .url(konto.server + pfad)
            .header("Authorization", "Bearer ${konto.token}")
            .header("Accept", "application/json")
            .method(methode, rumpf)
            .build()
        http.newCall(anfrage).execute().use { r ->
            if (!r.isSuccessful) throw HttpFehler(r.code, "$methode $pfad → HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
}
