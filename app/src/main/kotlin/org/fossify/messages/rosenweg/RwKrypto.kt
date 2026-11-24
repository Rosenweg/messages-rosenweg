package org.fossify.messages.rosenweg

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ende-zu-Ende fuer Direktnachrichten, genau wie pwa/crypto.js ("ecdh-p256-aesgcm-v1"):
 * Einweg-Schluesselpaar P-256 je Nachricht; je Zielgeraet ECDH → HKDF-SHA256
 * (salt = IV, info = "rw-dm|<Geraete-ID>") → AES-256-GCM (Tag am Ende des ct).
 * Oeffentliche Schluessel roh/unkomprimiert (65 Byte) in Base64.
 * Reines JVM (Android-Keystore steckt hinter der Funktion [vereinbare]).
 */
object RwKrypto {
    const val ALG = "ecdh-p256-aesgcm-v1"
    const val MEDIA_PREFIX = "RWMEDIA1|"

    private val zufall = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)

    /** ECPublicKey → 0x04 || X(32) || Y(32) */
    fun roh(pub: ECPublicKey): ByteArray {
        fun fest(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return byteArrayOf(4) + fest(pub.w.affineX) + fest(pub.w.affineY)
    }

    fun oeffentlich(roh: ByteArray): ECPublicKey {
        require(roh.size == 65 && roh[0] == 4.toByte()) { "kein unkomprimierter P-256-Schluessel" }
        val x = BigInteger(1, roh.copyOfRange(1, 33))
        val y = BigInteger(1, roh.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, laenge: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < laenge) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(laenge)
    }

    fun aesGcm(verschluesseln: Boolean, schluessel: ByteArray, iv: ByteArray, daten: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(if (verschluesseln) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(schluessel, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(daten)
    }

    fun zufallsBytes(n: Int) = ByteArray(n).also { zufall.nextBytes(it) }

    /** Software-ECDH (Einweg-Schluessel beim Senden, Tests). */
    fun ecdh(privat: PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privat)
            doPhase(peer, true)
            generateSecret()
        }

    /**
     * Klartext fuer alle Zielgeraete verschluesseln. empfaenger: Geraete-ID → oeffentlicher
     * Schluessel (Base64, roh). Unbrauchbare Schluessel werden uebersprungen (wie die PWA).
     * Rueckgabe: das Buendel {v,alg,epk,perDevice} als JSON-Text.
     */
    fun verschluessele(klartext: String, empfaenger: Map<String, String>): String {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), zufall) }
        val einweg = kpg.generateKeyPair()
        val epk = b64(roh(einweg.public as ECPublicKey))
        val pt = klartext.toByteArray(Charsets.UTF_8)
        val geraete = LinkedHashMap<String, Pair<String, String>>()
        for ((id, pub) in empfaenger) {
            try {
                val iv = zufallsBytes(12)
                val geheim = ecdh(einweg.private, oeffentlich(unb64(pub)))
                val schluessel = hkdf(geheim, iv, "rw-dm|$id".toByteArray(Charsets.UTF_8))
                geraete[id] = b64(iv) to b64(aesGcm(true, schluessel, iv, pt))
            } catch (_: Exception) {
                // ein kaputter Geraeteschluessel darf die DM an die anderen nicht verhindern
            }
        }
        if (geraete.isEmpty()) throw IllegalStateException("keine Zielgeraete")
        return buildJsonObject {
            put("v", 1)
            put("alg", ALG)
            put("epk", epk)
            putJsonObject("perDevice") {
                for ((id, ivCt) in geraete) {
                    putJsonObject(id) {
                        put("iv", ivCt.first)
                        put("ct", ivCt.second)
                    }
                }
            }
        }.toString()
    }

    /**
     * Den Eintrag fuer dieses Geraet entschluesseln. vereinbare = ECDH mit dem
     * eigenen (Keystore-)Schluessel. null = nicht fuer dieses Geraet / nicht lesbar.
     */
    fun entschluessele(buendel: String, geraeteId: String, vereinbare: (ECPublicKey) -> ByteArray): String? {
        val o = parseJson(buendel) as? JsonObject ?: return null
        if (o.str("alg") != ALG) return null
        val epk = o.str("epk") ?: return null
        val eintrag = o.obj("perDevice")?.obj(geraeteId) ?: return null
        val iv = unb64(eintrag.str("iv") ?: return null)
        val ct = unb64(eintrag.str("ct") ?: return null)
        return try {
            val geheim = vereinbare(oeffentlich(unb64(epk)))
            val schluessel = hkdf(geheim, iv, "rw-dm|$geraeteId".toByteArray(Charsets.UTF_8))
            String(aesGcm(false, schluessel, iv, ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** "RWMEDIA1|{"t":Text,"m":{...}}" → (Text, Medien-JSON), sonst null */
    fun medienAusKlartext(klartext: String): Pair<String, String>? {
        if (!klartext.startsWith(MEDIA_PREFIX)) return null
        val o = parseJson(klartext.substring(MEDIA_PREFIX.length)) as? JsonObject ?: return null
        val m = o.obj("m") ?: return null
        return (o.str("t") ?: "") to m.toString()
    }
}
