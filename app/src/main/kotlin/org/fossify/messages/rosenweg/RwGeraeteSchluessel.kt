package org.fossify.messages.rosenweg

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Geraeteschluessel (ECDH P-256) fuer E2E-DMs, je Benutzer eigene Geraete-ID.
 * Geraete-ID und Schluessel gehoeren fest zusammen (wie pwa/crypto.js): fehlt der
 * Schluessel zur gespeicherten ID, gibt es eine NEUE ID mit neuem Schluessel.
 *
 * Android 12+: privater Schluessel im AndroidKeyStore (PURPOSE_AGREE_KEY, nicht
 * exportierbar). Davor: Software-Schluessel, verschluesselt mit einem AES-Schluessel
 * aus dem AndroidKeyStore.
 */
class RwGeraeteSchluessel private constructor(
    val geraeteId: String,
    private val privat: PrivateKey,
    private val keystoreEcdh: Boolean,
    val oeffentlichRoh: String,
) {
    fun vereinbare(peer: ECPublicKey): ByteArray =
        if (keystoreEcdh) {
            KeyAgreement.getInstance("ECDH", KEYSTORE).run {
                init(privat)
                doPhase(peer, true)
                generateSecret()
            }
        } else {
            RwKrypto.ecdh(privat, peer)
        }

    fun entschluessele(buendel: String): String? = RwKrypto.entschluessele(buendel, geraeteId) { vereinbare(it) }

    companion object {
        private const val TAG = "RwGeraeteSchluessel"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val PREFS = "rosenweg_messenger"
        private const val WRAP_ALIAS = "rw_dm_wrap"

        @Volatile
        private var cache: RwGeraeteSchluessel? = null
        private var cacheUser: String? = null

        private fun alias(geraeteId: String) = "rw_dm_$geraeteId"

        /** Schluessel + Geraete-ID des Benutzers laden oder neu anlegen (blockierend). */
        @Synchronized
        fun fuer(context: Context, userId: String): RwGeraeteSchluessel {
            cache?.takeIf { cacheUser == userId }?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "device_id_$userId"
            var id = prefs.getString(key, null)
            // Uebernahme aus v29-v33: eine Geraete-ID ohne Benutzerbindung
            if (id == null && prefs.getString("user_id", null) == userId) id = prefs.getString("device_id", null)

            val geladen = id?.let { lade(context, it) }
            val s = geladen ?: run {
                val neu = "dev_" + UUID.randomUUID().toString()
                erzeuge(context, neu)
            }
            prefs.edit().putString(key, s.geraeteId).apply()
            cache = s
            cacheUser = userId
            return s
        }

        private fun keystore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        private fun lade(context: Context, id: String): RwGeraeteSchluessel? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && keystore().containsAlias(alias(id))) {
                val ks = keystore()
                val priv = ks.getKey(alias(id), null) as PrivateKey
                val pub = ks.getCertificate(alias(id)).publicKey as ECPublicKey
                RwGeraeteSchluessel(id, priv, true, RwKrypto.b64(RwKrypto.roh(pub)))
            } else {
                ladeSoftware(context, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geraeteschluessel $id nicht ladbar", e)
            null
        }

        private fun erzeuge(context: Context, id: String): RwGeraeteSchluessel {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
                    kpg.initialize(
                        KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_AGREE_KEY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .build()
                    )
                    val kp = kpg.generateKeyPair()
                    return RwGeraeteSchluessel(id, kp.private, true, RwKrypto.b64(RwKrypto.roh(kp.public as ECPublicKey)))
                } catch (e: Exception) {
                    Log.w(TAG, "Keystore-ECDH nicht verfuegbar, Software-Schluessel", e)
                }
            }
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val datei = softwareDatei(context, id)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapSchluessel())
            val ct = c.doFinal(kp.private.encoded)
            datei.writeText(RwKrypto.b64(c.iv) + "\n" + RwKrypto.b64(ct) + "\n" + RwKrypto.b64(kp.public.encoded))
            return RwGeraeteSchluessel(id, kp.private, false, RwKrypto.b64(RwKrypto.roh(kp.public as ECPublicKey)))
        }

        private fun softwareDatei(context: Context, id: String) =
            File(context.noBackupFilesDir, "rw_dm_$id.key")

        private fun ladeSoftware(context: Context, id: String): RwGeraeteSchluessel? {
            val datei = softwareDatei(context, id)
            if (!datei.exists()) return null
            val (iv, ct, pub) = datei.readText().trim().split("\n")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapSchluessel(), GCMParameterSpec(128, RwKrypto.unb64(iv)))
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(c.doFinal(RwKrypto.unb64(ct))))
            val oeff = kf.generatePublic(X509EncodedKeySpec(RwKrypto.unb64(pub))) as ECPublicKey
            return RwGeraeteSchluessel(id, priv, false, RwKrypto.b64(RwKrypto.roh(oeff)))
        }

        private fun wrapSchluessel(): SecretKey {
            val ks = keystore()
            (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return kg.generateKey()
        }

        /** Schluessel eines Benutzers vergessen (Abmelden auf geteilten Telefonen, spaeter). */
        @Synchronized
        fun vergiss() {
            cache = null
            cacheUser = null
        }
    }
}
