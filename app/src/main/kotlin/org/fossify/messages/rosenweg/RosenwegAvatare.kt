package org.fossify.messages.rosenweg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import org.fossify.messages.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Bilder fuer Rosenweg-Gespraeche und -Personen wie in der PWA (app.js avatarMarkup):
 * eigenes Logo (data:-URL) vor Standardlogo; Ankuendigungen 🚨/📢; Personen aus
 * /api/directory. Alles wird als Datei im App-Speicher abgelegt (Glide laedt Pfade).
 * Dateinamen enthalten den Inhalts-Hash bzw. die App-Version, damit Glide-Caches
 * nie ein veraltetes Bild zeigen. Leerer Text = Fossify-Buchstaben-Avatar.
 */
class RosenwegAvatare(context: Context) {
    private val app = context.applicationContext
    private val ordner = File(app.filesDir, "rosenweg_avatare").apply { mkdirs() }

    companion object {
        private const val TAG = "RosenwegAvatare"
        private val GRUPPEN_LOGOS = setOf("technik", "ausschuss", "stweg3", "stweg6")
    }

    /** data:-URL → Datei (Pfad), http(s) → unveraendert, sonst "" */
    fun bild(quelle: String?): String {
        if (quelle.isNullOrBlank()) return ""
        if (quelle.startsWith("http://") || quelle.startsWith("https://")) return quelle
        if (!quelle.startsWith("data:")) return ""
        return try {
            val komma = quelle.indexOf(',')
            if (komma < 0) return ""
            val kopf = quelle.substring(0, komma)
            val inhalt = quelle.substring(komma + 1)
            val bytes = if (kopf.endsWith(";base64")) {
                Base64.decode(inhalt, Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(inhalt, "UTF-8").toByteArray()
            }
            val datei = File(ordner, "av_" + sha1(bytes))
            if (!datei.exists()) datei.writeBytes(bytes)
            datei.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Bild nicht lesbar", e)
            ""
        }
    }

    /** Bild eines Rosenweg-Gespraechs (Gruppe/Kanal); DMs nutzen das Personenbild. */
    fun gespraech(chatId: String, kind: String, eigenesLogo: String?): String {
        bild(eigenesLogo).takeIf { it.isNotEmpty() }?.let { return it }
        return if (kind == "bcast") {
            emoji(if (chatId == "notfall") "🚨" else "📢")
        } else {
            logo(if (chatId in GRUPPEN_LOGOS) chatId else "rosenweg")
        }
    }

    private fun logo(slug: String): String {
        val datei = File(ordner, "logo_${slug}_${BuildConfig.VERSION_CODE}.png")
        if (!datei.exists()) {
            try {
                app.assets.open("rosenweg/groups/$slug.png").use { e -> datei.outputStream().use { e.copyTo(it) } }
            } catch (e: Exception) {
                Log.w(TAG, "Logo $slug fehlt", e)
                return ""
            }
        }
        return datei.absolutePath
    }

    private fun emoji(zeichen: String): String {
        val name = zeichen.codePoints().toArray().joinToString("_") { Integer.toHexString(it) }
        val datei = File(ordner, "emoji_${name}_${BuildConfig.VERSION_CODE}.png")
        if (datei.exists()) return datei.absolutePath
        return try {
            val groesse = 192
            val bmp = Bitmap.createBitmap(groesse, groesse, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(0xEC, 0xEF, 0xF1))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = groesse * 0.55f
                textAlign = Paint.Align.CENTER
            }
            val y = groesse / 2f - (p.descent() + p.ascent()) / 2f
            c.drawText(zeichen, groesse / 2f, y, p)
            datei.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            datei.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Emoji-Bild", e)
            ""
        }
    }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
