package org.fossify.messages.rosenweg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

class RwKryptoTest {
    // Testvektor aus WebCrypto (node, gleiche Ableitung wie pwa/crypto.js)
    private val pkcs8 = "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg39JgAhvjen3eUpHivJ5AufROfA949ZY/gMiVscgGFF6hRANCAAR5eq4TOc5paFIZaDn9L7PBIhgB4vph549G4d8wi4NkNaxs38Ld62sdJJ/ykC4FUdVFfzjGwKwQ8Wtv7gb+Dl5k"
    private val pubRaw = "BHl6rhM5zmloUhloOf0vs8EiGAHi+mHnj0bh3zCLg2Q1rGzfwt3rax0kn/KQLgVR1UV/OMbArBDxa2/uBv4OXmQ="
    private val geraet = "dev_test-1"
    private val klartext = "Grüezi 👋 aus der PWA"
    private val buendel = """{"v":1,"alg":"ecdh-p256-aesgcm-v1","epk":"BFREJVxP+M5Q0dZEEUDZxTfJQ/b3b5G2vpW7my5EXgoFt5JQQHxdsHZNKm+L+T46DvnoDSVtyEG1JV9ChtTdlrk=","perDevice":{"dev_test-1":{"iv":"z7AT94VRHlCQJwFT","ct":"XBnlGFVokOqexIDRqH3beQG0HJetHzatZlhx0piz/S8QINbh9GaEpg=="}}}"""

    private fun privat() = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(RwKrypto.unb64(pkcs8)))

    @Test
    fun pwaBuendelEntschluesseln() {
        val p = privat()
        assertEquals(klartext, RwKrypto.entschluessele(buendel, geraet) { RwKrypto.ecdh(p, it) })
        assertNull(RwKrypto.entschluessele(buendel, "dev_anderes") { RwKrypto.ecdh(p, it) })
    }

    @Test
    fun rohSchluesselRundreise() {
        val k = RwKrypto.oeffentlich(RwKrypto.unb64(pubRaw))
        assertEquals(pubRaw, RwKrypto.b64(RwKrypto.roh(k)))
    }

    @Test
    fun eigenesBuendelFuerMehrereGeraete() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val a = kpg.generateKeyPair()
        val b = kpg.generateKeyPair()
        val text = "RWMEDIA1|{\"t\":\"Bild\",\"m\":{\"mediaId\":\"x\",\"filename\":\"a.jpg\"}}"
        val bu = RwKrypto.verschluessele(
            text,
            mapOf(
                "dev_a" to RwKrypto.b64(RwKrypto.roh(a.public as ECPublicKey)),
                "dev_b" to RwKrypto.b64(RwKrypto.roh(b.public as ECPublicKey)),
                "dev_kaputt" to "AAAA",
            )
        )
        assertEquals(text, RwKrypto.entschluessele(bu, "dev_a") { RwKrypto.ecdh(a.private, it) })
        assertEquals(text, RwKrypto.entschluessele(bu, "dev_b") { RwKrypto.ecdh(b.private, it) })
        assertNull(RwKrypto.entschluessele(bu, "dev_kaputt") { RwKrypto.ecdh(a.private, it) })
        assertEquals("Bild" to "{\"mediaId\":\"x\",\"filename\":\"a.jpg\"}", RwKrypto.medienAusKlartext(text))
        // fuer den Gegentest in WebCrypto (node vektor.mjs open)
        println("JVM-BUENDEL " + RwKrypto.verschluessele(klartext, mapOf(geraet to pubRaw)))
    }

    @Test
    fun hkdfRfc5869Fall1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = RwKrypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) }
        )
    }
}

class GeraeteIdTest {
    @org.junit.Test
    fun stabilJeTelefonUndBenutzer() {
        val a = RwGeraeteSchluessel.stabileIdAus("a1b2c3", "42")
        org.junit.Assert.assertEquals(a, RwGeraeteSchluessel.stabileIdAus("a1b2c3", "42"))
        org.junit.Assert.assertNotEquals(a, RwGeraeteSchluessel.stabileIdAus("a1b2c3", "43"))
        org.junit.Assert.assertTrue(a, Regex("^dev_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(a))
    }
}

class MedienTest {
    @org.junit.Test
    fun beschreibungUndDmKlartext() {
        val b = RosenwegMedien.Beschreibung("m-1", "image/jpeg", "foto.jpg", 1234, "image", "a2V5", "aXY=")
        org.junit.Assert.assertEquals(b, RosenwegMedien.lies(RosenwegMedien.json(b)))
        val k = RosenwegMedien.dmKlartext("Schau mal", b)
        val (t, m) = RwKrypto.medienAusKlartext(k)!!
        org.junit.Assert.assertEquals("Schau mal", t)
        org.junit.Assert.assertEquals(b, RosenwegMedien.lies(m))
        // Gruppe: ohne Schluessel
        val g = b.copy(key = null, iv = null)
        org.junit.Assert.assertFalse(RosenwegMedien.json(g).contains("key"))
        val payload = baueAusgehend("1", "c_1", 1, "", null, RosenwegMedien.json(g))
        org.junit.Assert.assertEquals("foto.jpg", parseLiveNachricht("group/g/msg/1", payload)!!.mediaName)
    }
}
