package org.fossify.messages.rosenweg

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ActivityRosenwegGruppeBinding
import org.fossify.messages.databinding.ItemRosenwegMitgliedBinding
import org.fossify.messages.helpers.THREAD_ID
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Gruppen-Info wie in der PWA (Titel antippen): Logo (mit Kamera-Knopf zum Aendern),
 * Titel mit Stift, Mitglieder mit Rolle und Entfernen, Hinzufuegen, geteilte Medien.
 * Fuer DMs: das Gegenueber. Verwalten nur fuer Gruppen-Admins und technik/präsident.
 */
class RosenwegGruppeActivity : SimpleActivity() {

    companion object {
        private const val TAG = "RosenwegGruppe"
        private val ROLLEN = listOf("admin" to "Admin", "member" to "Mitglied", "readonly" to "Nur-Lese")
        private const val LOGO_MAX_ZEICHEN = 400 * 1024

        fun starte(context: Context, threadId: Long) {
            context.startActivity(Intent(context, RosenwegGruppeActivity::class.java).putExtra(THREAD_ID, threadId))
        }
    }

    private val binding by viewBinding(ActivityRosenwegGruppeBinding::inflate)
    private lateinit var speicher: RosenwegSpeicher
    private var chat: RwChat? = null
    private var mitglieder: List<RosenwegApi.Mitglied> = emptyList()
    private var istAdmin = false
    private var titel = ""

    private val bildWahl = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) setzeLogo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.gruppeScroll))
        setupMaterialScrollListener(binding.gruppeScroll, binding.gruppeAppbar)
        speicher = RosenwegSpeicher.get(this)
        val threadId = intent.getLongExtra(THREAD_ID, 0L)

        binding.gruppeSchliessen.setOnClickListener { finish() }
        binding.gruppeTitelHolder.setOnClickListener { if (istAdmin) umbenennen() }
        binding.gruppeLogoAendern.setOnClickListener { bildWahl.launch("image/*") }
        binding.gruppeLogoEntfernen.setOnClickListener { entferneLogo() }
        binding.gruppeHinzufuegen.setOnClickListener { hinzufuegen() }

        ensureBackgroundThread {
            val c = speicher.chatZuThread(threadId)
            runOnUiThread {
                if (c == null) {
                    toast("Gespräch nicht gefunden")
                    finish()
                } else {
                    chat = c
                    zeigeKopf()
                    zeigeMedien()
                    if (c.kind != "dm") ladeMitglieder()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.gruppeAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.gruppeHolder)
        val primaer = getProperPrimaryColor()
        binding.gruppeHinzufuegen.setTextColor(primaer)
        binding.gruppeLogoEntfernen.setTextColor(primaer)
        binding.gruppeTitelAendern.applyColorFilter(getProperTextColor())
        binding.gruppeLogoAendern.applyColorFilter(Color.WHITE)
        binding.gruppeFehler.setTextColor(0xFFD32F2F.toInt())
    }

    private fun fehler(text: String?) {
        binding.gruppeFehler.text = text ?: ""
        binding.gruppeFehler.beVisibleIf(!text.isNullOrEmpty())
    }

    // ── Kopf: Logo, Titel ───────────────────────────────────────────────

    private fun zeigeKopf() {
        val c = chat ?: return
        ensureBackgroundThread {
            val name = speicher.titel(c)
            val bild = if (c.kind == "dm") speicher.dmPartner(c)?.let { speicher.personenBildFuer(it) } ?: "" else speicher.gespraechsBild(c)
            runOnUiThread {
                titel = name
                binding.gruppeToolbar.title = if (c.kind == "dm") "Kontakt" else "Gruppen-Info"
                binding.gruppeTitel.text = name
                binding.gruppeUntertitel.text = when (c.kind) {
                    "dm" -> "Direktnachricht · Ende-zu-Ende verschlüsselt"
                    "bcast" -> "Ankündigungskanal"
                    else -> "Gruppe"
                }
                SimpleContactsHelper(this).loadContactImage(bild, binding.gruppeLogo, name)
                zeigeAdminKnoepfe()
            }
        }
    }

    private fun zeigeAdminKnoepfe() {
        val c = chat ?: return
        val gruppe = c.kind != "dm"
        binding.gruppeLogoAendern.beVisibleIf(gruppe && istAdmin)
        binding.gruppeLogoEntfernen.beVisibleIf(gruppe && istAdmin && c.avatar != null)
        binding.gruppeTitelAendern.beVisibleIf(gruppe && istAdmin)
        binding.gruppeHinzufuegen.beVisibleIf(gruppe && istAdmin)
        binding.gruppeMitgliederKopf.beVisibleIf(gruppe)
        binding.gruppeMitglieder.beVisibleIf(gruppe)
    }

    // ── Mitglieder ──────────────────────────────────────────────────────

    private fun api(): RosenwegApi? = RosenwegKonto.hole(this)?.let { RosenwegApi(it) }

    private fun ladeMitglieder() {
        val c = chat ?: return
        if (mitglieder.isEmpty()) {
            binding.gruppeMitglieder.removeAllViews()
            binding.gruppeMitglieder.addView(hinweis("Lade Mitglieder…"))
        }
        ensureBackgroundThread {
            try {
                val konto = RosenwegKonto.hole(this) ?: throw IllegalStateException("nicht angemeldet")
                val liste = RosenwegApi(konto).mitgliederMitRolle(c.id)
                val ich = konto.userId
                val admin = konto.istAdmin || c.role == "admin" || liste.any { it.userId == ich && it.role == "admin" }
                // Namen/Bilder vorher (Hintergrund) aufloesen
                val zeilen = liste.sortedWith(compareBy({ it.role != "admin" }, { speicher.name(it.userId).lowercase() }))
                    .map { Triple(it, speicher.name(it.userId), speicher.personenBildFuer(it.userId)) }
                runOnUiThread {
                    mitglieder = liste
                    istAdmin = admin
                    fehler(null)
                    zeigeAdminKnoepfe()
                    binding.gruppeMitgliederLabel.text = "Mitglieder (${liste.size})"
                    zeigeMitglieder(zeilen, ich)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mitglieder laden", e)
                runOnUiThread {
                    fehler("Mitglieder konnten nicht geladen werden.")
                    binding.gruppeMitglieder.removeAllViews()
                }
            }
        }
    }

    private fun hinweis(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.7f
        setPadding(resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin), 8, 8, 8)
        setTextColor(getProperTextColor())
    }

    private fun zeigeMitglieder(zeilen: List<Triple<RosenwegApi.Mitglied, String, String>>, ich: String) {
        val holder = binding.gruppeMitglieder
        holder.removeAllViews()
        if (zeilen.isEmpty()) {
            holder.addView(hinweis("Keine Mitglieder."))
            return
        }
        val textFarbe = getProperTextColor()
        for ((m, name, bild) in zeilen) {
            val z = ItemRosenwegMitgliedBinding.inflate(layoutInflater, holder, false)
            val selbst = m.userId == ich
            z.mitgliedName.text = if (selbst) "$name (du)" else name
            z.mitgliedName.setTextColor(textFarbe)
            SimpleContactsHelper(this).loadContactImage(bild, z.mitgliedBild, name)
            if (istAdmin && !selbst) {
                z.mitgliedRolle.beVisible()
                z.mitgliedRolleText.beGone()
                z.mitgliedEntfernen.beVisible()
                z.mitgliedEntfernen.setColorFilter(0xFFD32F2F.toInt())
                z.mitgliedRolle.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ROLLEN.map { it.second })
                val index = ROLLEN.indexOfFirst { it.first == m.role }.coerceAtLeast(0)
                z.mitgliedRolle.setSelection(index, false)
                z.mitgliedRolle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val neu = ROLLEN[position].first
                        if (neu != m.role) rolle(m.userId, neu)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                z.mitgliedEntfernen.setOnClickListener { entfernen(m.userId, name) }
            } else {
                z.mitgliedRolle.beGone()
                z.mitgliedEntfernen.beGone()
                z.mitgliedRolleText.beVisible()
                z.mitgliedRolleText.setTextColor(textFarbe)
                z.mitgliedRolleText.text = ROLLEN.firstOrNull { it.first == m.role }?.second ?: m.role
            }
            holder.addView(z.root)
        }
    }

    /** Hintergrundaktion gegen die API, danach neu laden; Fehlertext sonst. */
    private fun aktion(fehlerText: String, nachher: () -> Unit = { ladeMitglieder() }, block: (RosenwegApi, RwChat) -> Unit) {
        val c = chat ?: return
        fehler(null)
        ensureBackgroundThread {
            try {
                val a = api() ?: throw IllegalStateException("nicht angemeldet")
                block(a, c)
                runOnUiThread { nachher() }
            } catch (e: Exception) {
                Log.w(TAG, fehlerText, e)
                runOnUiThread {
                    fehler(fehlerText)
                    ladeMitglieder()
                }
            }
        }
    }

    private fun rolle(userId: String, rolle: String) =
        aktion("Rolle konnte nicht geändert werden.") { a, c -> a.rolleSetzen(c.id, userId, rolle) }

    private fun entfernen(userId: String, name: String) {
        AlertDialog.Builder(this)
            .setMessage("$name aus der Gruppe entfernen?")
            .setPositiveButton("Entfernen") { _, _ ->
                aktion("Mitglied konnte nicht entfernt werden.") { a, c -> a.mitgliedEntfernen(c.id, userId) }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun hinzufuegen() {
        ensureBackgroundThread {
            val schon = mitglieder.map { it.userId }.toSet()
            val kandidaten = speicher.personenListe().filter { it.id !in schon }
            runOnUiThread {
                if (kandidaten.isEmpty()) {
                    toast("Alle Personen aus dem Verzeichnis sind schon Mitglied")
                    return@runOnUiThread
                }
                val gewaehlt = BooleanArray(kandidaten.size)
                AlertDialog.Builder(this)
                    .setTitle("Mitglieder hinzufügen")
                    .setMultiChoiceItems(kandidaten.map { it.name }.toTypedArray(), gewaehlt) { _, i, an -> gewaehlt[i] = an }
                    .setPositiveButton("Hinzufügen") { _, _ ->
                        val ids = kandidaten.filterIndexed { i, _ -> gewaehlt[i] }.map { it.id }
                        if (ids.isNotEmpty()) {
                            aktion("Mitglieder konnten nicht hinzugefügt werden.") { a, c -> a.mitgliederHinzufuegen(c.id, ids) }
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    // ── Titel / Logo ────────────────────────────────────────────────────

    private fun umbenennen() {
        val feld = EditText(this).apply {
            setText(titel)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Gruppenname ändern")
            .setView(feld)
            .setPositiveButton("Speichern") { _, _ ->
                val neu = feld.text.toString().trim()
                if (neu.isNotEmpty() && neu != titel) {
                    aktion("Name konnte nicht geändert werden.", nachher = {
                        titel = neu
                        binding.gruppeTitel.text = neu
                    }) { a, c -> a.umbenennen(c.id, neu) }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun setzeLogo(uri: Uri) {
        aktion("Bild konnte nicht gesetzt werden.", nachher = { toast("Logo geändert – wird gleich übernommen") }) { a, c ->
            a.setzeLogo(c.id, logoAusBild(uri))
        }
    }

    private fun entferneLogo() {
        aktion("Logo konnte nicht entfernt werden.", nachher = { toast("Logo entfernt – wird gleich übernommen") }) { a, c ->
            a.setzeLogo(c.id, null)
        }
    }

    /** Wie fileToGroupAvatar in der PWA: quadratisch zuschneiden, 256 px JPEG, < 400 kB als data:-URL */
    private fun logoAusBild(uri: Uri): String {
        val roh = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Bild nicht lesbar")
        val seite = min(roh.width, roh.height)
        val quadrat = Bitmap.createBitmap(roh, (roh.width - seite) / 2, (roh.height - seite) / 2, seite, seite)
        var groesse = 256
        repeat(8) {
            val klein = Bitmap.createScaledBitmap(quadrat, groesse, groesse, true)
            var q = 85
            while (q >= 40) {
                val out = ByteArrayOutputStream()
                klein.compress(Bitmap.CompressFormat.JPEG, q, out)
                val url = "data:image/jpeg;base64," + RwKrypto.b64(out.toByteArray())
                if (url.length <= LOGO_MAX_ZEICHEN) return url
                q -= 10
            }
            groesse = (groesse * 0.8).toInt()
        }
        throw IllegalArgumentException("Bild zu gross")
    }

    // ── Geteilte Medien ─────────────────────────────────────────────────

    private fun zeigeMedien() {
        val c = chat ?: return
        ensureBackgroundThread {
            val liste = speicher.medien(c.id).map { m ->
                "📎 ${m.mediaName} · ${speicher.name(m.senderId)} · " +
                    m.ts.formatDateOrTime(this, hideTimeOnOtherDays = true, showCurrentYear = false)
            }
            runOnUiThread {
                val holder = binding.gruppeMedien
                holder.removeAllViews()
                if (liste.isEmpty()) {
                    holder.addView(hinweis("Noch keine geteilten Medien.").apply { setPadding(0, 8, 0, 8) })
                } else {
                    liste.forEach { zeile ->
                        holder.addView(TextView(this).apply {
                            text = zeile
                            setTextColor(getProperTextColor())
                            setPadding(0, 10, 0, 10)
                        })
                    }
                }
            }
        }
    }
}
