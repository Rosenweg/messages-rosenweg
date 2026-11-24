package org.fossify.messages.rosenweg

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Eigener MQTT-3.1.1-Client ueber einen OkHttp-WebSocket (Subprotokoll "mqtt",
 * Binaer-Frames). Ohne Android-Abhaengigkeiten (auch vom JVM aus nutzbar).
 *
 * Ein Objekt = eine Verbindung. Nach getrennt() wird ein neues Objekt gebaut.
 * Rueckrufe kommen auf dem OkHttp-Lesethread (der Reihe nach).
 */
class RwMqttClient(
    private val url: String,
    private val hoerer: Hoerer,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val http: OkHttpClient = standardHttp,
) {
    interface Hoerer {
        /** CONNACK mit Code 0 */
        fun verbunden()

        /** CONNACK mit Code != 0 (4/5 = Anmeldung abgelehnt) */
        fun abgelehnt(code: Int)

        fun nachricht(topic: String, payload: ByteArray, retained: Boolean)

        /** Verbindung zu (auch nach abgelehnt). grund = null bei sauberem Ende. */
        fun getrennt(grund: Throwable?)
    }

    data class Optionen(
        val clientId: String,
        val username: String?,
        val password: String?,
        val keepAliveSek: Int = 60,
        val will: MqttCodec.Will? = null,
    )

    companion object {
        val standardHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // Lebenszeichen ueber MQTT-PINGREQ
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .build()
        }
    }

    private var ws: WebSocket? = null
    private val dekoder = MqttDekoder()
    private val beendet = AtomicBoolean(false)
    private var naechsteId = 0
    private val offenePublish = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    private val offeneSubscribe = ConcurrentHashMap<Int, (List<Int>) -> Unit>()

    @Volatile
    var istVerbunden = false
        private set

    @Volatile
    var letzterPingMs = 0L
        private set

    @Volatile
    var letzteAntwortMs = 0L
        private set

    fun verbinde(opt: Optionen) {
        val anfrage = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "mqtt")
            .build()
        ws = http.newWebSocket(anfrage, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ok = webSocket.send(
                    MqttCodec.connect(opt.clientId, opt.username, opt.password, opt.keepAliveSek, true, opt.will).toByteString()
                )
                if (!ok) beende(IllegalStateException("CONNECT nicht sendbar"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val pakete = try {
                    dekoder.fuettere(bytes.toByteArray())
                } catch (e: Exception) {
                    log("MQTT-Strom fehlerhaft", e)
                    webSocket.cancel()
                    beende(e)
                    return
                }
                for (p in pakete) verarbeite(webSocket, p)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                log("unerwarteter Text-Frame (${text.length} Zeichen)", null)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                beende(if (code == 1000) null else IllegalStateException("WebSocket zu: $code $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                beende(t)
            }
        })
    }

    private fun verarbeite(webSocket: WebSocket, p: MqttPaket) {
        when (p) {
            is MqttPaket.ConnAck -> {
                if (p.returnCode == 0) {
                    istVerbunden = true
                    letzteAntwortMs = System.currentTimeMillis()
                    hoerer.verbunden()
                } else {
                    hoerer.abgelehnt(p.returnCode)
                    webSocket.close(1000, null)
                    beende(MqttAbgelehnt(p.returnCode))
                }
            }
            is MqttPaket.Publish -> {
                if (p.qos == 1 && p.packetId != null) webSocket.send(MqttCodec.pubAck(p.packetId).toByteString())
                hoerer.nachricht(p.topic, p.payload, p.retain)
            }
            is MqttPaket.PubAck -> offenePublish.remove(p.packetId)?.invoke(true)
            is MqttPaket.SubAck -> offeneSubscribe.remove(p.packetId)?.invoke(p.returnCodes)
            is MqttPaket.PingResp -> letzteAntwortMs = System.currentTimeMillis()
            is MqttPaket.UnsubAck -> Unit
            is MqttPaket.Sonstiges -> log("unbekanntes MQTT-Paket ${p.typ}", null)
        }
    }

    class MqttAbgelehnt(val code: Int) : Exception("CONNACK $code")

    private fun beende(grund: Throwable?) {
        if (!beendet.compareAndSet(false, true)) return
        istVerbunden = false
        offenePublish.values.forEach { it(false) }
        offenePublish.clear()
        offeneSubscribe.clear()
        hoerer.getrennt(grund)
    }

    @Synchronized
    private fun neueId(): Int {
        naechsteId = naechsteId % 0xFFFF + 1
        return naechsteId
    }

    /** QoS 0 oder 1; fertig(true) bei PUBACK (QoS 1) bzw. nach dem Absenden (QoS 0). */
    fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, fertig: ((Boolean) -> Unit)? = null): Boolean {
        val w = ws ?: return false
        if (qos == 0) {
            val ok = w.send(MqttCodec.publish(topic, payload, 0, retain, null).toByteString())
            fertig?.invoke(ok)
            return ok
        }
        val id = neueId()
        offenePublish[id] = fertig ?: {}
        val ok = w.send(MqttCodec.publish(topic, payload, 1, retain, id).toByteString())
        if (!ok) offenePublish.remove(id)?.invoke(false)
        return ok
    }

    fun subscribe(filter: List<String>, qos: Int = 1, fertig: ((List<Int>) -> Unit)? = null): Boolean {
        val w = ws ?: return false
        if (filter.isEmpty()) return true
        // in Paketen zu hoechstens 50 Filtern
        var alle = true
        for (teil in filter.chunked(50)) {
            val id = neueId()
            if (fertig != null) offeneSubscribe[id] = fertig
            alle = w.send(MqttCodec.subscribe(id, teil.map { it to qos }).toByteString()) && alle
        }
        return alle
    }

    /** Sendet PINGREQ (nicht oefter als alle 15 s). */
    fun ping(): Boolean {
        val w = ws ?: return false
        val jetzt = System.currentTimeMillis()
        if (jetzt - letzterPingMs < 15_000 && pingAusstehend()) return true
        letzterPingMs = jetzt
        return w.send(MqttCodec.pingReq().toByteString())
    }

    fun pingAusstehend() = letzterPingMs > letzteAntwortMs

    /** Sauber trennen (DISCONNECT), ohne getrennt()-Rueckruf. */
    fun trenne() {
        beendet.set(true)
        istVerbunden = false
        ws?.let {
            it.send(MqttCodec.disconnect().toByteString())
            it.close(1000, null)
        }
    }

    /** Sofort abbrechen, ohne getrennt()-Rueckruf. */
    fun abbrechen() {
        beendet.set(true)
        istVerbunden = false
        ws?.cancel()
    }
}
