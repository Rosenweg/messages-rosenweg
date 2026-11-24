package org.fossify.messages.rosenweg

import java.io.ByteArrayOutputStream

/**
 * Kleiner MQTT-3.1.1-Codec (nur was der Rosenweg Messenger braucht), ohne
 * Android-Abhaengigkeiten. Ersetzt Paho, dessen WSS-Transport am Rosenweg-Broker
 * mit "Already connected" scheitert.
 */
sealed class MqttPaket {
    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPaket()
    data class Publish(
        val topic: String,
        val payload: ByteArray,
        val qos: Int,
        val retain: Boolean,
        val dup: Boolean,
        val packetId: Int?,
    ) : MqttPaket() {
        override fun equals(other: Any?): Boolean =
            other is Publish && topic == other.topic && payload.contentEquals(other.payload) && qos == other.qos &&
                retain == other.retain && dup == other.dup && packetId == other.packetId

        override fun hashCode(): Int = topic.hashCode() * 31 + payload.contentHashCode()
    }

    data class PubAck(val packetId: Int) : MqttPaket()
    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPaket()
    data class UnsubAck(val packetId: Int) : MqttPaket()
    object PingResp : MqttPaket() {
        override fun toString() = "PingResp"
    }

    /** Vom Server unerwartete/unbekannte Pakete (z.B. PUBREC bei QoS 2) */
    data class Sonstiges(val typ: Int, val flags: Int, val laenge: Int) : MqttPaket()
}

class MqttProtokollFehler(msg: String) : Exception(msg)

object MqttCodec {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val UNSUBACK = 11
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    const val MAX_LAENGE = 268_435_455

    fun laengeKodieren(laenge: Int): ByteArray {
        require(laenge in 0..MAX_LAENGE) { "Laenge ausserhalb MQTT: $laenge" }
        val out = ByteArrayOutputStream(4)
        var x = laenge
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 0x80
            out.write(b)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.schreibeText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        schreibeBinaer(b)
    }

    private fun ByteArrayOutputStream.schreibeBinaer(b: ByteArray) {
        require(b.size <= 0xFFFF)
        write(b.size shr 8)
        write(b.size and 0xFF)
        write(b)
    }

    private fun ByteArrayOutputStream.schreibeId(id: Int) {
        write(id shr 8 and 0xFF)
        write(id and 0xFF)
    }

    private fun paket(kopf: Int, rumpf: ByteArray): ByteArray {
        val l = laengeKodieren(rumpf.size)
        val out = ByteArray(1 + l.size + rumpf.size)
        out[0] = kopf.toByte()
        System.arraycopy(l, 0, out, 1, l.size)
        System.arraycopy(rumpf, 0, out, 1 + l.size, rumpf.size)
        return out
    }

    data class Will(val topic: String, val payload: ByteArray, val qos: Int, val retain: Boolean)

    fun connect(
        clientId: String,
        username: String?,
        password: String?,
        keepAliveSek: Int,
        cleanSession: Boolean = true,
        will: Will? = null,
    ): ByteArray {
        val r = ByteArrayOutputStream()
        r.schreibeText("MQTT")
        r.write(4) // Protokoll-Level 3.1.1
        var flags = 0
        if (cleanSession) flags = flags or 0x02
        if (will != null) {
            flags = flags or 0x04 or (will.qos and 3 shl 3)
            if (will.retain) flags = flags or 0x20
        }
        if (password != null) flags = flags or 0x40
        if (username != null) flags = flags or 0x80
        r.write(flags)
        r.write(keepAliveSek shr 8 and 0xFF)
        r.write(keepAliveSek and 0xFF)
        r.schreibeText(clientId)
        if (will != null) {
            r.schreibeText(will.topic)
            r.schreibeBinaer(will.payload)
        }
        if (username != null) r.schreibeText(username)
        if (password != null) r.schreibeBinaer(password.toByteArray(Charsets.UTF_8))
        return paket(CONNECT shl 4, r.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, packetId: Int?, dup: Boolean = false): ByteArray {
        require(qos in 0..1) { "nur QoS 0/1" }
        require(qos == 0 || packetId != null)
        val r = ByteArrayOutputStream()
        r.schreibeText(topic)
        if (qos > 0) r.schreibeId(packetId!!)
        r.write(payload)
        var kopf = PUBLISH shl 4 or (qos shl 1)
        if (retain) kopf = kopf or 1
        if (dup) kopf = kopf or 0x08
        return paket(kopf, r.toByteArray())
    }

    fun pubAck(packetId: Int): ByteArray = paket(PUBACK shl 4, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()))

    fun subscribe(packetId: Int, filter: List<Pair<String, Int>>): ByteArray {
        require(filter.isNotEmpty())
        val r = ByteArrayOutputStream()
        r.schreibeId(packetId)
        for ((f, qos) in filter) {
            r.schreibeText(f)
            r.write(qos)
        }
        return paket(SUBSCRIBE shl 4 or 0x02, r.toByteArray())
    }

    fun pingReq(): ByteArray = byteArrayOf((PINGREQ shl 4).toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf((DISCONNECT shl 4).toByte(), 0)

    /** Ein vollstaendiges Paket (Kopf-Byte + Rumpf) in ein Objekt uebersetzen. */
    fun dekodiere(kopf: Int, rumpf: ByteArray): MqttPaket {
        val typ = kopf ushr 4 and 0x0F
        val flags = kopf and 0x0F
        fun id(at: Int): Int {
            if (rumpf.size < at + 2) throw MqttProtokollFehler("Paket $typ zu kurz")
            return (rumpf[at].toInt() and 0xFF shl 8) or (rumpf[at + 1].toInt() and 0xFF)
        }
        return when (typ) {
            CONNACK -> {
                if (rumpf.size < 2) throw MqttProtokollFehler("CONNACK zu kurz")
                MqttPaket.ConnAck(rumpf[0].toInt() and 1 == 1, rumpf[1].toInt() and 0xFF)
            }
            PUBLISH -> {
                val qos = flags shr 1 and 3
                val tl = id(0)
                if (rumpf.size < 2 + tl) throw MqttProtokollFehler("PUBLISH-Topic zu kurz")
                val topic = String(rumpf, 2, tl, Charsets.UTF_8)
                var pos = 2 + tl
                val pid = if (qos > 0) id(pos).also { pos += 2 } else null
                MqttPaket.Publish(topic, rumpf.copyOfRange(pos, rumpf.size), qos, flags and 1 == 1, flags and 8 == 8, pid)
            }
            PUBACK -> MqttPaket.PubAck(id(0))
            SUBACK -> MqttPaket.SubAck(id(0), (2 until rumpf.size).map { rumpf[it].toInt() and 0xFF })
            UNSUBACK -> MqttPaket.UnsubAck(id(0))
            PINGRESP -> MqttPaket.PingResp
            else -> MqttPaket.Sonstiges(typ, flags, rumpf.size)
        }
    }
}

/**
 * Strom-Dekoder: Ein WebSocket-Frame kann Teile eines Pakets oder mehrere Pakete
 * enthalten. Bytes werden gesammelt, bis Kopf + Restlaenge (varint) + Rumpf da sind.
 */
class MqttDekoder {
    private var puffer = ByteArray(0)
    private var laenge = 0

    fun fuettere(daten: ByteArray, offset: Int = 0, anzahl: Int = daten.size): List<MqttPaket> {
        if (laenge + anzahl > puffer.size) {
            puffer = puffer.copyOf(maxOf(puffer.size * 2, laenge + anzahl, 256))
        }
        System.arraycopy(daten, offset, puffer, laenge, anzahl)
        laenge += anzahl

        val ergebnis = ArrayList<MqttPaket>()
        var pos = 0
        while (true) {
            if (laenge - pos < 2) break
            val kopf = puffer[pos].toInt() and 0xFF
            var mult = 1
            var rest = 0
            var i = pos + 1
            var fertig = false
            var bytes = 0
            while (i < laenge) {
                val b = puffer[i].toInt() and 0xFF
                rest += (b and 0x7F) * mult
                bytes++
                i++
                if (b and 0x80 == 0) {
                    fertig = true
                    break
                }
                if (bytes == 4) throw MqttProtokollFehler("Restlaenge ungueltig")
                mult *= 128
            }
            if (!fertig) break
            if (laenge - i < rest) break
            ergebnis.add(MqttCodec.dekodiere(kopf, puffer.copyOfRange(i, i + rest)))
            pos = i + rest
        }
        if (pos > 0) {
            System.arraycopy(puffer, pos, puffer, 0, laenge - pos)
            laenge -= pos
        }
        return ergebnis
    }

    fun gepuffert() = laenge
}
