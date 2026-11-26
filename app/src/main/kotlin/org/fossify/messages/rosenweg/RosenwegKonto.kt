package org.fossify.messages.rosenweg

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Messenger-Zugang.
 * - Geteiltes Rosenweg-Telefon: kommt von der Geraete-App (AnlageProvider.call("messenger_konto")).
 *   Leeres Bundle / Fehler = niemand angemeldet → Rosenweg-Teil bleibt inaktiv.
 * - Privates Handy (kein Rosenweg Geraet): eigener Zugang dieses Handys an der Website
 *   ([RosenwegPrivatAnmeldung], "Mit Rosenweg-Konto anmelden"). Ohne Anmeldung kein Zugang.
 */
data class RosenwegKonto(
    val server: String,
    val token: String,
    /** Ablauf des Anlage-Tokens, epoch ms */
    val gueltigBisMs: Long,
    val userId: String,
    /** Anzeigename aus dem Anlage-Token (Claim "name") */
    val name: String? = null,
    /** so lange vor Ablauf neu holen (privat: kurze Tokens, darum weniger Vorlauf) */
    val erneuernVorMs: Long = ERNEUERN_VOR_MS,
) {
    /** wie requireAuth im message-store: technik / präsident verwalten alle Gespraeche */
    val istAdmin: Boolean
        get() = leseJwt(token)?.groups?.any { it.lowercase() in setOf("technik", "präsident", "praesident") } == true

    fun laeuftBaldAb(jetzt: Long = System.currentTimeMillis()) = gueltigBisMs - jetzt < erneuernVorMs

    companion object {
        private const val TAG = "RosenwegKonto"
        private val ANLAGE_URI: Uri = Uri.parse("content://ch.rosenweg4303.telefon.anlage")
        private const val METHODE = "messenger_konto"
        const val ERNEUERN_VOR_MS = 30 * 60 * 1000L
        const val ERNEUERN_VOR_PRIVAT_MS = 10 * 60 * 1000L

        private const val PREFS = "rosenweg_messenger"
        private const val PREF_GERAET = "device_id"

        @Volatile
        private var zwischenspeicher: RosenwegKonto? = null

        /** Art des letzten Fehlers beim Token-Holen (fuer das Zurueckhalten im Dienst) */
        @Volatile
        var letzterFehler = RwZurueckhalten.Art.NETZ
            private set

        /** Holt den Zugang (blockierend, nie auf dem UI-Thread aufrufen). */
        fun hole(context: Context, erzwingen: Boolean = false): RosenwegKonto? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "RosenwegKonto.hole() nicht auf dem UI-Thread" }
            val alt = zwischenspeicher
            if (!erzwingen && alt != null && !alt.laeuftBaldAb()) return alt
            val neu = if (RosenwegPrivatAnmeldung.privat(context)) holePrivat(context, alt) else holeVonGeraet(context)
            zwischenspeicher = neu
            return neu
        }

        /** Privates Handy: Messenger-Token mit dem eigenen Zugang an der Website. */
        private fun holePrivat(context: Context, alt: RosenwegKonto?): RosenwegKonto? = try {
            // nach Serverfehlern auch hier die Wartezeit einhalten (Aufrufe aus der Oberflaeche)
            val zurueck = RwZurueckhalten.fuer(context)
            if (zurueck.wartetNachServerfehler()) throw RosenwegApi.HttpFehler(0, "Wartezeit nach Serverfehler")
            RosenwegPrivatAnmeldung.messengerToken(context)?.let { t ->
                val jwt = leseJwt(t.token)
                val sub = jwt?.sub
                if (sub.isNullOrEmpty()) {
                    null
                } else {
                    RosenwegKonto(t.server, t.token, (if (t.gueltigBisSek > 0) t.gueltigBisSek else (jwt.exp ?: 0L)) * 1000, sub, jwt.name, ERNEUERN_VOR_PRIVAT_MS)
                }
            }
        } catch (e: RosenwegPrivatAnmeldung.Abgelehnt) {
            // 403 (z.B. Adresse gesperrt): sofort aufhoeren, Daten behalten, Anmeldeknopf zeigen
            Log.w(TAG, "Website lehnt ab (403) - angehalten")
            RosenwegPrivatAnmeldung.markiereAbgelehnt(context)
            letzterFehler = RwZurueckhalten.Art.SERVER
            null
        } catch (e: RosenwegPrivatAnmeldung.Widerrufen) {
            // Im Portal / von der Technik abgemeldet: abmelden und Daten loeschen - in einem eigenen
            // Thread, weil hole() oft im Arbeits-Thread des Dienstes laeuft, den das Loeschen beendet.
            Log.i(TAG, "eigener Zugang widerrufen - melde ab")
            Thread { RosenwegPrivatAnmeldung.vomServerAbgemeldet(context.applicationContext) }.start()
            null
        } catch (e: Exception) {
            // Website nicht erreichbar: mit dem bisherigen Token weiter, solange es gilt
            Log.i(TAG, "kein Messenger-Token (Netz?)", e)
            letzterFehler = if (e is RosenwegApi.HttpFehler) RwZurueckhalten.Art.SERVER else RwZurueckhalten.Art.NETZ
            alt?.takeIf { it.gueltigBisMs > System.currentTimeMillis() + 60_000 }
        }

        private fun holeVonGeraet(context: Context): RosenwegKonto? {
            return try {
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
