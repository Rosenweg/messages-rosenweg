package org.fossify.messages.rosenweg

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.refreshConversations
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * "Teilen -> Rosenweg-Chat" (Stefan 27.11.2026): Bilder, Dateien und Text aus anderen Apps in
 * einen Rosenweg-Chat senden, auf privaten und auf geteilten Telefonen. Unabhaengig von
 * NewConversationActivity (das ist der SMS-Weg und privat ausgeschaltet).
 *
 * Ablauf: Ziel waehlen (eigene Chats, in denen man schreiben darf, zuletzt benutzte zuerst, dann
 * Personen aus dem Rosenweg-Verzeichnis ohne bestehende Direktnachricht) -> senden -> der Chat
 * oeffnet sich. Bilder werden vorher verkleinert (laengste Seite [RosenwegBild.MAX_SEITE], JPEG),
 * GIFs und andere Dateien gehen unveraendert (max. 25 MB wie in der PWA).
 */
class RosenwegTeilenActivity : AppCompatActivity() {
    private data class Ziel(val titel: String, val chat: RwChat?, val person: RwUser?)

    internal data class Teil(val uri: Uri, val mime: String)

    private var warten: ProgressDialog? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val teile = TeilenInhalt.teile(this, intent)
        val text = TeilenInhalt.textAus(intent)
        if (teile.isEmpty() && text.isBlank()) {
            fertig("Nichts zum Teilen gefunden")
            return
        }
        ensureBackgroundThread {
            val angemeldet = try {
                RosenwegKonto.hole(this) != null
            } catch (e: Exception) {
                Log.w(TAG, "Konto: ${e.javaClass.simpleName}")
                false
            }
            val ziele = if (angemeldet) ziele() else emptyList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    !angemeldet -> zeigeNichtAngemeldet()
                    ziele.isEmpty() -> fertig(
                        "Noch keine Rosenweg-Chats – das Verzeichnis lädt noch. Bitte gleich nochmals versuchen."
                    )
                    else -> waehleZiel(ziele, teile, text)
                }
            }
        }
    }

    override fun onDestroy() {
        warten?.dismiss()
        super.onDestroy()
    }

    /** Eigene Chats (schreibbar, zuletzt benutzte zuerst), danach Personen ohne Direktnachricht. */
    private fun ziele(): List<Ziel> {
        val speicher = RosenwegSpeicher.get(this)
        val reihenfolge = try {
            conversationsDB.getNonArchived().filter { it.threadId.isRosenwegThread() }
                .sortedByDescending { it.date }.map { it.threadId }
        } catch (_: Exception) {
            emptyList()
        }
        val chats = speicher.chats().filter { it.canWrite }
            .sortedBy { reihenfolge.indexOf(it.threadId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        val mitDm = chats.mapNotNull { speicher.dmPartner(it) }.toSet()
        val personen = speicher.personenListe().filter { it.id !in mitDm }
        return chats.map { Ziel(speicher.titel(it), it, null) } +
            personen.map { Ziel(it.name, null, it) }
    }

    private fun waehleZiel(ziele: List<Ziel>, teile: List<Teil>, text: String) {
        val was = when {
            teile.size > 1 -> "${teile.size} Dateien"
            teile.size == 1 && teile[0].mime.startsWith("image/") -> "Bild"
            teile.size == 1 -> "Datei"
            else -> "Text"
        }
        val beschriftungen = ziele.map { if (it.person != null) "${it.titel} (neue Direktnachricht)" else it.titel }
        AlertDialog.Builder(this)
            .setTitle("$was senden an …")
            .setItems(beschriftungen.toTypedArray()) { _, i ->
                senden(ziele[i], teile, text)
            }
            .setNegativeButton("Abbrechen") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    @Suppress("DEPRECATION", "TooGenericExceptionCaught")
    private fun senden(ziel: Ziel, teile: List<Teil>, text: String) {
        warten = ProgressDialog.show(this, null, "Wird gesendet …", true, false)
        ensureBackgroundThread {
            val ergebnis = try {
                val chat = ziel.chat ?: neueDm(ziel.person!!)
                var ok = true
                if (teile.isEmpty()) {
                    ok = RosenwegHooks.sende(this, chat.threadId, text.trim())
                } else {
                    teile.forEachIndexed { i, t ->
                        // Text (z.B. Link zum Bild) als Bildunterschrift beim ersten Anhang
                        val unterschrift = if (i == 0) text.trim() else ""
                        ok = sendeTeil(chat, t, unterschrift) && ok
                    }
                }
                chat to ok
            } catch (e: Exception) {
                Log.w(TAG, "Teilen", e)
                null
            }
            runOnUiThread {
                warten?.dismiss()
                warten = null
                if (ergebnis == null) {
                    fertig("Nicht gesendet – bitte nochmals versuchen")
                    return@runOnUiThread
                }
                val (chat, ok) = ergebnis
                if (!ok) toast("Nicht alles gesendet")
                startActivity(Intent(this, ThreadActivity::class.java).apply {
                    putExtra(THREAD_ID, chat.threadId)
                    putExtra(THREAD_TITLE, ziel.titel)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                finish()
            }
        }
    }

    private fun sendeTeil(chat: RwChat, t: Teil, unterschrift: String): Boolean {
        val klein = if (RosenwegBild.verkleinern(t.mime)) RosenwegBild.verkleinere(this, t.uri) else null
        return try {
            if (klein != null) {
                val name = (TeilenInhalt.anzeigeName(this, t.uri)?.substringBeforeLast('.') ?: "bild") + ".jpg"
                RosenwegHooks.sendeMedien(
                    this, chat.threadId, Uri.fromFile(klein), "image/jpeg", name, unterschrift, null
                )
            } else {
                RosenwegHooks.sendeMedien(this, chat.threadId, t.uri, t.mime, null, unterschrift, null)
            }
        } finally {
            klein?.delete()
        }
    }

    private fun neueDm(p: RwUser): RwChat {
        val konto = checkNotNull(RosenwegKonto.hole(this)) { "nicht angemeldet" }
        val speicher = RosenwegSpeicher.get(this)
        val chat = speicher.nimmGespraechAuf(RosenwegApi(konto).neuesGespraech(p.id, null, emptyList()))
        MessengerVerbindung.neuVerbinden()
        refreshConversations()
        return chat
    }

    private fun zeigeNichtAngemeldet() {
        val privat = RosenwegPrivatAnmeldung.privat(this)
        val b = AlertDialog.Builder(this)
            .setTitle("Nicht angemeldet")
            .setOnCancelListener { finish() }
        if (privat) {
            b.setMessage(
                "Melde dich zuerst im Rosenweg Messenger an (Rosenweg-Karte oder Rosenweg-Konto), dann teile nochmals."
            )
                .setPositiveButton("Anmelden") { _, _ ->
                    RosenwegPrivatAnmeldung.starten(this)
                    finish()
                }
                .setNegativeButton("Abbrechen") { _, _ -> finish() }
        } else {
            b.setMessage("Melde dich zuerst an diesem Telefon an (Rosenweg Gerät), dann teile nochmals.")
                .setPositiveButton("OK") { _, _ -> finish() }
        }
        b.show()
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show()

    private fun fertig(text: String) {
        toast(text)
        finish()
    }

    companion object {
        private const val TAG = "RosenwegTeilen"
    }
}

/** Was eine andere App teilt: Dateien (EXTRA_STREAM), Text, Anzeigename. */
internal object TeilenInhalt {
    @Suppress("DEPRECATION")
    fun teile(c: android.content.Context, i: Intent): List<RosenwegTeilenActivity.Teil> {
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            )
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }.orEmpty()
            else -> emptyList()
        }
        return uris.map { u ->
            val gemeldet = try {
                c.contentResolver.getType(u)
            } catch (_: Exception) {
                null
            }
            val mime = gemeldet ?: i.type ?: "application/octet-stream"
            RosenwegTeilenActivity.Teil(u, if (mime.contains('*')) "application/octet-stream" else mime)
        }
    }

    fun textAus(i: Intent): String {
        val t = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val betreff = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        return if (betreff.isNotBlank() && !t.contains(betreff)) "$betreff\n$t".trim() else t
    }

    fun anzeigeName(c: android.content.Context, u: Uri): String? = try {
        val spalte = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        c.contentResolver.query(u, spalte, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** Bilder vor dem Senden verkleinern (wie die PWA: lange Seite begrenzen, JPEG). */
object RosenwegBild {
    const val MAX_SEITE = 2048
    private const val QUALITAET = 85
    private const val GRAD_90 = 90
    private const val GRAD_180 = 180
    private const val GRAD_270 = 270
    private const val TAG = "RosenwegBild"

    /** GIF (Animation) und SVG nicht anfassen. */
    fun verkleinern(mime: String): Boolean =
        mime.startsWith("image/") && mime != "image/gif" && !mime.startsWith("image/svg")

    /** Groesster Zweierpotenz-Teiler, mit dem beide Seiten noch >= max bleiben (BitmapFactory.inSampleSize). */
    internal fun teiler(breite: Int, hoehe: Int, max: Int = MAX_SEITE): Int {
        var n = 1
        while (maxOf(breite, hoehe) / (n * 2) >= max) n *= 2
        return n
    }

    /** Zielgroesse: laengste Seite hoechstens [max], Seitenverhaeltnis bleibt. */
    internal fun ziel(breite: Int, hoehe: Int, max: Int = MAX_SEITE): Pair<Int, Int> {
        val lang = maxOf(breite, hoehe)
        if (lang <= max) return breite to hoehe
        val f = max.toDouble() / lang
        return maxOf(1, (breite * f).roundToInt()) to maxOf(1, (hoehe * f).roundToInt())
    }

    /** Verkleinerte JPEG-Kopie im Cache oder null (dann das Original senden). */
    @Suppress("TooGenericExceptionCaught")
    fun verkleinere(c: android.content.Context, uri: Uri): File? = try {
        lade(c, uri)?.let { b -> speichere(c, gedreht(b, drehung(c, uri))) }
    } catch (e: Exception) {
        // fremde Anbieter werfen alles Moegliche: dann das Original senden
        Log.w(TAG, "Verkleinern: ${e.javaClass.simpleName}")
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Verkleinern: zu gross (${e.javaClass.simpleName})")
        null
    }

    /** Bild mit passendem inSampleSize laden (spart Speicher bei grossen Fotos). */
    private fun lade(c: android.content.Context, uri: Uri): Bitmap? {
        val r = c.contentResolver
        val masse = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, masse) }
        if (masse.outWidth <= 0 || masse.outHeight <= 0) return null
        val opt = BitmapFactory.Options().apply { inSampleSize = teiler(masse.outWidth, masse.outHeight) }
        return r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
    }

    /** Auf Zielgroesse bringen und nach EXIF drehen. */
    private fun gedreht(b: Bitmap, grad: Int): Bitmap {
        val (w, h) = ziel(b.width, b.height)
        val m = Matrix().apply {
            postScale(w.toFloat() / b.width, h.toFloat() / b.height)
            if (grad != 0) postRotate(grad.toFloat())
        }
        val neu = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (neu !== b) b.recycle()
        return neu
    }

    private fun speichere(c: android.content.Context, b: Bitmap): File {
        val ordner = File(c.cacheDir, "rosenweg_teilen").apply { mkdirs() }
        val f = File(ordner, "bild-${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, QUALITAET, it) }
        b.recycle()
        return f
    }

    private fun drehung(c: android.content.Context, uri: Uri): Int =
        c.contentResolver.openInputStream(uri)?.use { drehung(ExifInterface(it)) } ?: 0

    private fun drehung(e: ExifInterface): Int =
        when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> GRAD_90
            ExifInterface.ORIENTATION_ROTATE_180 -> GRAD_180
            ExifInterface.ORIENTATION_ROTATE_270 -> GRAD_270
            else -> 0
        }
}
