package org.fossify.messages.rosenweg

import android.content.Context
import kotlin.random.Random

/**
 * Zurueckhalten nach Fehlern (Stefan 26.11.2026, A37: Anfragen im Sekundentakt mit 403 -> CrowdSec
 * sperrte die Mobilfunk-IP fuer alle Rosenweg-Apps). Regeln:
 *
 * - Nach einem Fehler frühestens nach [wartezeitMs] wieder: 30 s, 60 s, 2 min … hoechstens 30 min,
 *   jeweils ±20 % Zufall, damit viele Handys nicht im Gleichtakt kommen.
 * - Der Stand liegt in den Einstellungen: Dienst-Neustart, App oeffnen oder Netzwechsel umgehen ihn
 *   nicht. Nur reine Netzfehler (keine Antwort vom Server - zaehlt fuer CrowdSec nicht) duerfen
 *   bei "Netz wieder da" sofort neu versuchen.
 * - 401/403 sind keine Fehler zum Wiederholen: der Aufrufer hoert ganz auf ([istAbgelehnt]).
 */
class RwZurueckhalten(
    private val laden: () -> Stand,
    private val speichern: (Stand) -> Unit,
    private val uhr: () -> Long = System::currentTimeMillis,
    private val zufall: Random = Random.Default,
) {
    enum class Art { NETZ, SERVER }

    data class Stand(val fehler: Int = 0, val nichtVorMs: Long = 0L, val art: Art = Art.NETZ)

    /** Darf jetzt angefragt werden? */
    fun darfJetzt(): Boolean = uhr() >= laden().nichtVorMs

    /** Gesperrt, weil der Server zuletzt mit einem Fehler geantwortet hat? */
    fun wartetNachServerfehler(): Boolean = laden().let { it.art == Art.SERVER && uhr() < it.nichtVorMs }

    /** Millisekunden bis zum naechsten erlaubten Versuch (0 = jetzt). */
    fun restMs(): Long = (laden().nichtVorMs - uhr()).coerceAtLeast(0L)

    /** Fehler merken; gibt die Wartezeit zurueck. */
    fun fehler(art: Art): Long {
        val alt = laden()
        val warte = wartezeitMs(alt.fehler, zufall)
        speichern(Stand(alt.fehler + 1, uhr() + warte, art))
        return warte
    }

    /** Erfolg: alles zuruecksetzen. */
    fun erfolg() {
        if (laden() != Stand()) speichern(Stand())
    }

    /** Netz wieder da: nur nach reinen Netzfehlern sofort wieder erlauben. */
    fun netzWiederDa() {
        val s = laden()
        if (s.art == Art.NETZ && s.nichtVorMs > uhr()) speichern(s.copy(nichtVorMs = uhr()))
    }

    companion object {
        const val ERSTE_MS = 30_000L
        const val HOECHSTENS_MS = 30 * 60_000L

        /** 30 s · 2^n, hoechstens 30 min, ±20 % Zufall (nie unter 24 s). */
        fun wartezeitMs(bisherigeFehler: Int, zufall: Random = Random.Default): Long {
            val basis = if (bisherigeFehler >= 16) HOECHSTENS_MS else (ERSTE_MS shl bisherigeFehler.coerceAtLeast(0)).coerceAtMost(HOECHSTENS_MS)
            val faktor = 0.8 + 0.4 * zufall.nextDouble()
            return (basis * faktor).toLong()
        }

        /** Anmeldung abgelehnt (oder IP gesperrt): nicht wiederholen. */
        fun istAbgelehnt(code: Int) = code == 401 || code == 403

        private const val PREFS = "rosenweg_messenger_zurueck"

        /** Dauerhafter Stand fuer die Messenger-Verbindung (ein Zaehler fuer alle Anfragen des Dienstes). */
        fun fuer(context: Context): RwZurueckhalten {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return RwZurueckhalten(
                laden = {
                    Stand(
                        p.getInt("fehler", 0),
                        p.getLong("nicht_vor", 0L),
                        if (p.getString("art", null) == Art.SERVER.name) Art.SERVER else Art.NETZ,
                    )
                },
                speichern = { s -> p.edit().putInt("fehler", s.fehler).putLong("nicht_vor", s.nichtVorMs).putString("art", s.art.name).apply() },
            )
        }
    }
}
