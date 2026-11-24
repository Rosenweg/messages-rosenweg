package org.fossify.messages.rosenweg

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Messenger-Zugang auf geteilten Rosenweg-Telefonen: kommt von der Geraete-App
 * (AnlageProvider.call("messenger_konto")). Leeres Bundle / Fehler = kein Zugang
 * (privates Handy oder niemand angemeldet) → Rosenweg-Teil bleibt inaktiv.
 */
data class RosenwegKonto(
    val server: String,
    val token: String,
    /** Ablauf des Anlage-Tokens, epoch ms */
    val gueltigBisMs: Long,
    val userId: String,
    /** Anzeigename aus dem Anlage-Token (Claim "name") */
    val name: String? = null,
) {
    /** wie requireAuth im message-store: technik / präsident verwalten alle Gespraeche */
    val istAdmin: Boolean
        get() = leseJwt(token)?.groups?.any { it.lowercase() in setOf("technik", "präsident", "praesident") } == true

    fun laeuftBaldAb(jetzt: Long = System.currentTimeMillis()) = gueltigBisMs - jetzt < ERNEUERN_VOR_MS

    companion object {
        private const val TAG = "RosenwegKonto"
        private val ANLAGE_URI: Uri = Uri.parse("content://ch.rosenweg4303.telefon.anlage")
        private const val METHODE = "messenger_konto"
        const val ERNEUERN_VOR_MS = 30 * 60 * 1000L

        private const val PREFS = "rosenweg_messenger"
        private const val PREF_GERAET = "device_id"

        @Volatile
        private var zwischenspeicher: RosenwegKonto? = null

        /** Holt den Zugang (blockierend, nie auf dem UI-Thread aufrufen). */
        fun hole(context: Context, erzwingen: Boolean = false): RosenwegKonto? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "RosenwegKonto.hole() nicht auf dem UI-Thread" }
            val alt = zwischenspeicher
            if (!erzwingen && alt != null && !alt.laeuftBaldAb()) return alt
            val neu = try {
                val b = context.contentResolver.call(ANLAGE_URI, METHODE, context.packageName, null)
                val server = b?.getString("server")?.trimEnd('/')
                val token = b?.getString("token")
                if (b == null || server.isNullOrEmpty() || token.isNullOrEmpty()) {
                    null
                } else {
                    @Suppress("DEPRECATION")
                    val bis = b.get("gueltig_bis")?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
                    val jwt = leseJwt(token)
                    val sub = jwt?.sub
                    val bisSek = if (bis > 0) bis else (jwt?.exp ?: 0L)
                    if (sub.isNullOrEmpty()) null else RosenwegKonto(server, token, bisSek * 1000, sub, jwt.name)
                }
            } catch (e: Exception) {
                Log.i(TAG, "kein Messenger-Zugang", e)
                null
            }
            zwischenspeicher = neu
            return neu
        }

        fun vergiss() {
            zwischenspeicher = null
        }

        /** Letzter bekannter Zugang ohne Abfrage (z.B. fuer die eigene ID im UI). */
        fun bekannt(): RosenwegKonto? = zwischenspeicher

        /** Geraete-ID "dev_<uuid>", einmal erzeugt (Schritt 3 bindet sie an den Keystore-Schluessel). */
        fun geraeteId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(PREF_GERAET, null)?.let { return it }
            val id = "dev_" + UUID.randomUUID().toString()
            prefs.edit().putString(PREF_GERAET, id).apply()
            return id
        }

        fun eigeneIdGespeichert(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user_id", null)

        fun eigenerNameGespeichert(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user_name", null)

        fun merkeEigeneId(context: Context, id: String, name: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("user_id", id)
                .putString("user_name", name)
                .apply()
        }
    }
}
