package org.fossify.messages.rosenweg

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Echte Verbindung zum Rosenweg-Broker mit Schein-Zugangsdaten: erwartet ein CONNACK
 * mit Ablehnung (5 = not authorized). Beweist, dass WSS + MQTT-Codec funktionieren.
 * Nur mit RW_BROKER_TEST=1 (Netzwerk), z.B.:
 *   RW_BROKER_TEST=1 ./gradlew testFossDebugUnitTest --tests '*BrokerProbeTest*'
 */
class BrokerProbeTest {
    @Test
    fun brokerLehntScheinZugangAb() {
        assumeTrue(System.getenv("RW_BROKER_TEST") == "1")
        val fertig = CountDownLatch(1)
        var code: Int? = null
        var fehler: Throwable? = null
        val c = RwMqttClient("wss://mqtt.rosenweg4303.ch/mqtt", object : RwMqttClient.Hoerer {
            override fun verbunden() { code = 0; fertig.countDown() }
            override fun abgelehnt(code: Int) = setze(code)
            private fun setze(c: Int) { code = c; fertig.countDown() }
            override fun nachricht(topic: String, payload: ByteArray, retained: Boolean) {}
            override fun getrennt(grund: Throwable?) { if (code == null) fehler = grund; fertig.countDown() }
        }, log = { m, t -> println("log: $m ${t ?: ""}") })
        c.verbinde(RwMqttClient.Optionen("and_probe_test", "dummy", "dummy", 60,
            MqttCodec.Will("presence/probe", "{}".toByteArray(), 1, true)))
        assertTrue("keine Antwort in 30 s", fertig.await(30, TimeUnit.SECONDS))
        c.abbrechen()
        println("BROKER-PROBE: CONNACK=$code fehler=$fehler")
        assertTrue("erwartet CONNACK 4/5, war $code ($fehler)", code == 5 || code == 4)
    }
}
