package org.fossify.messages.rosenweg

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import ch.rosenweg4303.cloud.KartenSitzung
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Rosenweg Messenger auf dem eigenen Handy (Stefan 26.11.2026): ohne Rosenweg Geraet meldet sich
 * die App selbst mit dem Rosenweg-Konto an - jede App fuer sich, kein gemeinsamer Kontospeicher.
 *
 * 1. [starten]: der gemeinsame Anmeldeschirm aus commons-rosenweg (RosenwegAnmeldeSchirm, ab
 *    Cloud 0.9.0) - wie der Sperrbildschirm der geteilten Telefone: Rosenweg-Karte hinten ans
 *    Telefon halten (Karten-Sitzung) oder mit dem Rosenweg-Konto im Browser ([RosenwegAnmeldung],
 *    Authentik "rosenweg-apps", PKCE, Ruecksprung ch.rosenweg4303.messages:/anmeldung). Beide
 *    Wege liefern ueber RosenwegAnmeldung.zugangsToken ein Token fuer [mitKontoToken].
 * 2. [einrichtenWennAusstehend]/[mitKontoToken]: mit dem Access-Token EINMAL bei der Website einen
 *    festen Zugang fuer dieses Handy holen (POST /api/messenger/zugang -> {zugang, geheimnis}).
 *    Danach wird die Authentik-Anmeldung der App verworfen; gespeichert bleibt nur der Zugang,
 *    das Geheimnis verschluesselt mit einem Schluessel aus dem Android-Keystore.
 * 3. [messengerToken]: laufend mit {zugang, geheimnis} ein Messenger-Token (1 h) holen, das der
 *    message-store annimmt. 401 = Zugang widerrufen (App, Portal /smartphones, Technik) -> die App
 *    meldet sich ab und loescht ihre Messenger-Daten. Nach einem 401 wird nicht weiter gefragt.
 * 4. [abmelden]: Zugang an der Website loeschen und alle Messenger-Daten auf dem Handy loeschen.
 *
 * Auf geteilten Telefonen (Rosenweg Geraet) gibt es das alles nicht; dort kommt der Zugang aus
 * der Sitzung am Telefon ([RosenwegKonto]). Nie Token, Code oder Geheimnis loggen.
 */
object RosenwegPrivatAnmeldung {
    private const val TAG = "RosenwegPrivat"
    const val WEBSITE = "https://rosenweg4303.ch"
    private const val PREFS = "rosenweg_messenger_privat"
    private const val P_ZUGANG = "zugang"
    private const val P_GEHEIMNIS = "geheimnis"
    private const val P_NAME = "name"
    private const val P_ABGELEHNT = "abgelehnt"

    /** Keystore-Alias des Schluessels fuer das Geheimnis (RosenwegAbmeldung loescht ihn mit) */
    const val ALIAS = "rw_privat_zugang"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val JSON_TYP = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    class Fehler(meldung: String) : Exception(meldung)

    /** Zugang an der Website abgelehnt (geloescht/gesperrt) */
    class Widerrufen : IOException("Zugang widerrufen")

    /** 403: Anfrage abgewiesen (z.B. Adresse von CrowdSec gesperrt) - nicht wiederholen */
    class Abgelehnt : IOException("abgewiesen (403)")

    data class Token(val server: String, val token: String, val gueltigBisSek: Long)

    @Volatile
    private var richtetEin = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Privates Telefon (kein Rosenweg Geraet)? Nur dort meldet sich der Messenger selbst an. */
    fun privat(c: Context) = !RosenwegSmsModus.geraetDa(c)

    /** Ist ein eigener Zugang gespeichert (auch wenn er gerade abgelehnt wurde)? */
    fun hatZugang(c: Context) = prefs(c).getString(P_ZUGANG, null) != null && prefs(c).getString(P_GEHEIMNIS, null) != null

    /**
     * Angemeldet und nicht angehalten. Nach 401/403 (message-store, Broker zweimal, Website 403) ist
     * der Zugang "abgelehnt": keine Anfragen mehr, bis die Person sich neu anmeldet. Die Chats bleiben
     * (neu angemeldet mit demselben Konto geht es dort weiter).
     */
    fun angemeldet(c: Context) = hatZugang(c) && !abgelehnt(c)

    fun abgelehnt(c: Context) = prefs(c).getBoolean(P_ABGELEHNT, false)

    fun markiereAbgelehnt(c: Context) {
        if (!abgelehnt(c)) {
            prefs(c).edit().putBoolean(P_ABGELEHNT, true).commit()
            Log.i(TAG, "angehalten: Anmeldung abgelehnt")
        }
    }

    fun name(c: Context): String? = prefs(c).getString(P_NAME, null)?.takeIf { it.isNotBlank() }

    /** Laeuft gerade die Einrichtung nach der Anmeldung? (fuer die Anzeige) */
    fun richtetGeradeEin() = richtetEin

    /** Schritt 1: gemeinsamer Anmeldeschirm (Karte oder Rosenweg-Konto). */
    fun starten(a: Activity) {
        a.startActivity(android.content.Intent(a, ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm::class.java))
    }

    /**
     * Beim Oeffnen der App (eigenes Handy, Stefan 26.11.2026: immer): ohne gueltige Anmeldung gleich
     * den Schirm zeigen, wie die anderen Rosenweg-Apps - auch nach einer Ablehnung (401/403).
     * Nicht waehrend der Einrichtung (Anmeldung fertig, Messenger-Zugang wird eben geholt).
     */
    fun schirmNoetig(c: Context) = privat(c) && !angemeldet(c) && !ausstehend(c) && !richtetEin

    /** Zurueck aus dem Browser, aber noch kein Messenger-Zugang? */
    fun ausstehend(c: Context) = privat(c) && !angemeldet(c) && RosenwegAnmeldung.angemeldet(c)

    /**
     * Nach dem Ruecksprung (blockierend, nicht im UI-Thread): Access-Token der App-Anmeldung
     * holen und den Zugang einrichten. null = nichts zu tun; sonst Meldung fuer die Person.
     */
    fun einrichtenWennAusstehend(c: Context): String? {
        if (!ausstehend(c)) return null
        val app = c.applicationContext
        val token = try {
            RosenwegAnmeldung.zugangsToken(app)
        } catch (e: Exception) {
            Log.w(TAG, "Konto-Token: ${e.javaClass.simpleName}")
            return "Keine Verbindung zum Rosenweg – später nochmals öffnen"
        }
        if (token == null) {
            RosenwegAnmeldung.abmelden(app)
            return "Anmeldung abgelaufen, bitte nochmals anmelden"
        }
        return try {
            mitKontoToken(app, token)
            "Rosenweg Messenger angemeldet" + (name(app)?.let { " als $it" } ?: "")
        } catch (e: Fehler) {
            // z.B. schon zu viele Handys: Konto-Anmeldung verwerfen, Person entscheidet neu
            RosenwegAnmeldung.abmelden(app)
            e.message ?: "Anmeldung fehlgeschlagen"
        } catch (e: Exception) {
            Log.w(TAG, "Einrichten: ${e.javaClass.simpleName}")
            "Keine Verbindung zum Rosenweg – später nochmals öffnen"
        }
    }

    /**
     * Schritt 2 (blockierend): mit einem Access-Token des Rosenweg-Kontos (rosenweg-apps) den
     * Zugang fuer dieses Handy holen und speichern. Einstieg auch fuer den kuenftigen gemeinsamen
     * Anmeldeschirm. Wirft [Fehler] mit Meldung fuer die Person oder IOException bei Netzfehlern.
     */
    @Synchronized
    fun mitKontoToken(c: Context, accessToken: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "nicht auf dem UI-Thread" }
        val app = c.applicationContext
        richtetEin = true
        try {
            val geraet = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val (status, j) = post("/api/messenger/zugang", JSONObject().put("geraet", geraet).put("geraet_kennung", KartenSitzung.geraetKennung(app)), accessToken)
            if (status != 200) {
                throw Fehler(j.optString("error").ifEmpty { if (status == 401) "Anmeldung abgelaufen, bitte nochmals" else "Rosenweg nicht erreichbar ($status)" })
            }
            val zugang = j.optString("zugang")
            val geheimnis = j.optString("geheimnis")
            if (zugang.isEmpty() || geheimnis.isEmpty()) throw Fehler("Antwort ohne Zugang")
            val alterZugang = zugangsdaten(app)
            prefs(app).edit()
                .remove(P_ABGELEHNT)
                .putString(P_ZUGANG, zugang)
                .putString(P_GEHEIMNIS, verschluesseln(geheimnis))
                .putString(P_NAME, j.optString("name"))
                .commit()
            // Die Authentik-Anmeldung braucht der Messenger nicht mehr: der Zugang ist der Ausweis.
            RosenwegAnmeldung.abmelden(app)
            // abgelehnter alter Zugang: an der Website aufraeumen (einmal, Ergebnis egal)
            if (alterZugang != null && alterZugang.first != zugang) {
                try {
                    post("/api/messenger/zugang/abmelden", JSONObject().put("zugang", alterZugang.first).put("geheimnis", alterZugang.second), null)
                } catch (_: Exception) {
                }
            }
            Log.i(TAG, "Zugang eingerichtet")
        } finally {
            richtetEin = false
        }
    }

    /**
     * Schritt 3 (blockierend): frisches Messenger-Token. null = kein Zugang gespeichert.
     * Wirft [Widerrufen] (Zugang weg - der Aufrufer meldet ab) oder IOException (Netz).
     */
    fun messengerToken(c: Context): Token? {
        if (abgelehnt(c)) return null
        val (zugang, geheimnis) = zugangsdaten(c) ?: return null
        val (status, j) = post("/api/messenger/zugang/token",
            // geraet_kennung: aeltere Zugaenge bekommen sie so nachgetragen (eine Zeile je Handy im Portal)
            JSONObject().put("zugang", zugang).put("geheimnis", geheimnis).put("geraet_kennung", KartenSitzung.geraetKennung(c)), null)
        when (status) {
            200 -> {}
            401 -> throw Widerrufen()
            403 -> throw Abgelehnt()
            else -> throw RosenwegApi.HttpFehler(status, "Messenger-Token: $status")
        }
        val server = j.optString("server").trimEnd('/')
        val token = j.optString("token")
        if (server.isEmpty() || token.isEmpty()) throw IOException("Messenger-Token: unvollstaendig")
        j.optString("name").takeIf { it.isNotBlank() }?.let { prefs(c).edit().putString(P_NAME, it).apply() }
        return Token(server, token, j.optLong("gueltig_bis", 0L))
    }

    /**
     * Schritt 4 (blockierend): an der Website abmelden (auch ohne Netz: Zugang wird hier vergessen)
     * und alle Messenger-Daten auf dem Handy loeschen.
     */
    fun abmelden(c: Context) {
        val app = c.applicationContext
        zugangsdaten(app)?.let { (zugang, geheimnis) ->
            try {
                val (status, _) = post("/api/messenger/zugang/abmelden", JSONObject().put("zugang", zugang).put("geheimnis", geheimnis), null)
                Log.i(TAG, "an der Website abgemeldet ($status)")
            } catch (e: Exception) {
                Log.w(TAG, "Abmelden an der Website: ${e.javaClass.simpleName}")
            }
        }
        vomServerAbgemeldet(app)
    }

    /** Zugang gibt es nicht mehr (401) oder Abmelden: alles loeschen, wieder "Mit Rosenweg-Konto anmelden". */
    fun vomServerAbgemeldet(c: Context) {
        val app = c.applicationContext
        prefs(app).edit().clear().commit()
        RosenwegAnmeldung.abmelden(app)
        RosenwegAbmeldung.allesLoeschen(app)
        Log.i(TAG, "abgemeldet, Messenger-Daten geloescht")
    }

    private fun zugangsdaten(c: Context): Pair<String, String>? {
        val p = prefs(c)
        val zugang = p.getString(P_ZUGANG, null) ?: return null
        val roh = p.getString(P_GEHEIMNIS, null) ?: return null
        val geheimnis = entschluesseln(roh)
        if (geheimnis == null) {
            // z.B. aus einer Sicherung auf ein anderes Handy zurueckgespielt: Schluessel fehlt
            Log.w(TAG, "Geheimnis nicht lesbar - Zugang vergessen")
            p.edit().clear().commit()
            return null
        }
        return zugang to geheimnis
    }

    private fun post(pfad: String, body: JSONObject, bearer: String?): Pair<Int, JSONObject> {
        val rq = Request.Builder().url(WEBSITE + pfad)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_TYP))
        if (bearer != null) rq.header("Authorization", "Bearer $bearer")
        http.newCall(rq.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val j = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
            }
            return r.code to j
        }
    }

    // ─── Keystore ───────────────────────────────────────────────────────────

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun verschluesseln(s: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, schluessel())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun entschluesseln(s: String): String? = try {
        val (iv, daten) = s.split(":", limit = 2)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(daten, Base64.NO_WRAP)))
    } catch (e: Exception) {
        null
    }
}
