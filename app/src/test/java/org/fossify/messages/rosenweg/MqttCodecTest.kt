package org.fossify.messages.rosenweg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCodecTest {

    /** Ein kodiertes Paket wieder in (Kopf, Rumpf) zerlegen */
    private fun zerlege(b: ByteArray): MqttPaket = MqttDekoder().fuettere(b).single()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun restlaengeGrenzen() {
        assertArrayEquals(bytes(0x00), MqttCodec.laengeKodieren(0))
        assertArrayEquals(bytes(0x7F), MqttCodec.laengeKodieren(127))
        assertArrayEquals(bytes(0x80, 0x01), MqttCodec.laengeKodieren(128))
        assertArrayEquals(bytes(0xFF, 0x7F), MqttCodec.laengeKodieren(16383))
        assertArrayEquals(bytes(0x80, 0x80, 0x01), MqttCodec.laengeKodieren(16384))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), MqttCodec.laengeKodieren(MqttCodec.MAX_LAENGE))
    }

    @Test
    fun publishRundreiseUeberRestlaengenGrenzen() {
        for (n in listOf(0, 1, 100, 127, 128, 16383, 16384, 70000)) {
            // Rumpf = 2 + Topiclaenge + 2 (id) + payload → Payload so waehlen, dass Rumpf genau n trifft (wo moeglich)
            val topic = "t"
            val payloadLaenge = maxOf(0, n - 2 - topic.length - 2)
            val payload = ByteArray(payloadLaenge) { (it % 251).toByte() }
            val kodiert = MqttCodec.publish(topic, payload, 1, retain = true, packetId = 513)
            val p = zerlege(kodiert) as MqttPaket.Publish
            assertEquals(topic, p.topic)
            assertArrayEquals(payload, p.payload)
            assertEquals(1, p.qos)
            assertTrue(p.retain)
            assertEquals(513, p.packetId)
        }
        val q0 = zerlege(MqttCodec.publish("group/g1/msg/5", "hi".toByteArray(), 0, false, null)) as MqttPaket.Publish
        assertEquals(0, q0.qos)
        assertEquals(null, q0.packetId)
        assertEquals("hi", String(q0.payload))
    }

    @Test
    fun connectAufbau() {
        val will = MqttCodec.Will("presence/7", """{"online":false}""".toByteArray(), 1, true)
        val c = MqttCodec.connect("and_dev_1", "jwt", "jwt", 60, true, will)
        assertEquals(0x10, c[0].toInt() and 0xFF)
        // Restlaenge (1 Byte, da < 128) + "MQTT"
        val r = 2
        assertEquals(0, c[r].toInt()); assertEquals(4, c[r + 1].toInt())
        assertEquals("MQTT", String(c, r + 2, 4))
        assertEquals(4, c[r + 6].toInt()) // Level 3.1.1
        // Flags: user, pass, will retain, will qos1, will, clean
        assertEquals(0x80 or 0x40 or 0x20 or 0x08 or 0x04 or 0x02, c[r + 7].toInt() and 0xFF)
        assertEquals(60, (c[r + 8].toInt() shl 8) or c[r + 9].toInt())
        assertEquals(c.size - 2, c[1].toInt())
        // ohne Will/Benutzer
        val c2 = MqttCodec.connect("x", null, null, 30)
        assertEquals(0x02, c2[2 + 7].toInt())
    }

    @Test
    fun steuerpakete() {
        assertArrayEquals(bytes(0x40, 2, 0x12, 0x34), MqttCodec.pubAck(0x1234))
        assertArrayEquals(bytes(0xC0, 0), MqttCodec.pingReq())
        assertArrayEquals(bytes(0xE0, 0), MqttCodec.disconnect())
        val sub = MqttCodec.subscribe(10, listOf("a/#" to 1, "sys/crisis" to 1))
        assertEquals(0x82, sub[0].toInt() and 0xFF)
        assertEquals(2 + (2 + 3 + 1) + (2 + 10 + 1), sub[1].toInt())
        assertEquals(10, sub[3].toInt())
    }

    @Test
    fun serverpaketeDekodieren() {
        assertEquals(MqttPaket.ConnAck(false, 0), zerlege(bytes(0x20, 2, 0, 0)))
        assertEquals(MqttPaket.ConnAck(true, 5), zerlege(bytes(0x20, 2, 1, 5)))
        assertEquals(MqttPaket.PubAck(258), zerlege(bytes(0x40, 2, 1, 2)))
        assertEquals(MqttPaket.SubAck(7, listOf(1, 0x80)), zerlege(bytes(0x90, 4, 0, 7, 1, 0x80)))
        assertEquals(MqttPaket.PingResp, zerlege(bytes(0xD0, 0)))
    }

    @Test
    fun geteilteUndZusammengelegteFrames() {
        val a = MqttCodec.publish("group/g1/msg/1", ByteArray(300) { 1 }, 1, false, 1)
        val b = bytes(0xD0, 0)
        val c = MqttCodec.publish("control/g1", "{}".toByteArray(), 0, false, null)
        val alles = a + b + c

        // alles in einem Frame
        assertEquals(3, MqttDekoder().fuettere(alles).size)

        // Byte fuer Byte (auch mitten in der Restlaenge geteilt)
        val d = MqttDekoder()
        val pakete = ArrayList<MqttPaket>()
        for (x in alles) pakete += d.fuettere(byteArrayOf(x))
        assertEquals(3, pakete.size)
        assertTrue(pakete[0] is MqttPaket.Publish)
        assertEquals(MqttPaket.PingResp, pakete[1])
        assertEquals("control/g1", (pakete[2] as MqttPaket.Publish).topic)
        assertEquals(0, d.gepuffert())

        // beliebige Schnittstellen
        for (schnitt in listOf(1, 2, 3, 150, a.size, a.size + 1, alles.size - 1)) {
            val e = MqttDekoder()
            val l = e.fuettere(alles.copyOfRange(0, schnitt)) + e.fuettere(alles.copyOfRange(schnitt, alles.size))
            assertEquals("Schnitt $schnitt", 3, l.size)
        }
    }

    @Test(expected = MqttProtokollFehler::class)
    fun zuLangeRestlaengeIstFehler() {
        MqttDekoder().fuettere(bytes(0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0x01))
    }
}
