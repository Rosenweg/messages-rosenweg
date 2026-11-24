package org.fossify.messages.rosenweg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    private fun anfrage(methode: String, pfad: String, body: String? = null): String {
        val verbindung = URL(konto.server + pfad).openConnection() as HttpURLConnection
        try {
            verbindung.requestMethod = methode
            verbindung.connectTimeout = 15_000
            verbindung.readTimeout = 30_000
            verbindung.setRequestProperty("Authorization", "Bearer ${konto.token}")
            verbindung.setRequestProperty("Accept", "application/json")
            if (body != null) {
                verbindung.doOutput = true
                verbindung.setRequestProperty("Content-Type", "application/json")
                verbindung.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = verbindung.responseCode
            if (code !in 200..299) {
                throw HttpFehler(code, "$methode $pfad → HTTP $code")
            }
            return verbindung.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            verbindung.disconnect()
        }
    }
}
