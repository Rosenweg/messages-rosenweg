package org.fossify.messages.rosenweg

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fossify.messages.BuildConfig
import java.io.File

/**
 * Medien wie in der PWA: Gruppen/Kanaele laden Klartext hoch (payload.media), DMs
 * verschluesseln die Datei mit frischem AES-256-GCM-Schluessel; Schluessel/IV stehen
 * nur in der E2E-Nachricht (RWMEDIA1). Entschluesselte Dateien liegen lokal unter
 * files/rosenweg_medien/<mediaId> und werden per FileProvider angezeigt.
 */
object RosenwegMedien {
    const val MAX_BYTES = 25L * 1024 * 1024

    data class Beschreibung(
        val mediaId: String,
        val mime: String,
        val filename: String,
        val size: Long,
        val mtype: String,
        val key: String?,
        val iv: String?,
    )

    fun lies(json: String?): Beschreibung? {
        val o = json?.let { parseJson(it) } as? JsonObject ?: return null
        return Beschreibung(
            mediaId = o.str("mediaId") ?: return null,
            mime = o.str("mime") ?: "application/octet-stream",
            filename = o.str("filename") ?: "datei",
            size = o.lng("size") ?: 0L,
            mtype = o.str("mtype") ?: "file",
            key = o.str("key"),
            iv = o.str("iv"),
        )
    }

    fun json(b: Beschreibung): String = buildJsonObject {
        put("mediaId", b.mediaId)
        if (b.key != null) put("key", b.key)
        if (b.iv != null) put("iv", b.iv)
        put("mime", b.mime)
        put("filename", b.filename)
        put("size", b.size)
        put("mtype", b.mtype)
    }.toString()

    private fun ordner(context: Context) = File(context.filesDir, "rosenweg_medien").apply { mkdirs() }

    /** nur sichere Zeichen im Dateinamen (mediaId ist eine UUID) */
    fun datei(context: Context, mediaId: String) = File(ordner(context), mediaId.replace(Regex("[^A-Za-z0-9_-]"), ""))

    fun vorhanden(context: Context, mediaId: String) = datei(context, mediaId).let { it.exists() && it.length() > 0 }

    fun uri(context: Context, mediaId: String): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", datei(context, mediaId))

    /** Herunterladen (+ entschluesseln bei DMs) und lokal ablegen. */
    fun lade(context: Context, api: RosenwegApi, b: Beschreibung) {
        if (vorhanden(context, b.mediaId)) return
        val roh = api.medienLaden(b.mediaId)
        val klar = if (b.key != null && b.iv != null) {
            RwKrypto.aesGcm(false, RwKrypto.unb64(b.key), RwKrypto.unb64(b.iv), roh)
        } else {
            roh
        }
        val ziel = datei(context, b.mediaId)
        val tmp = File(ziel.parentFile, ziel.name + ".tmp")
        tmp.writeBytes(klar)
        tmp.renameTo(ziel)
    }

    data class Auswahl(val bytes: ByteArray, val mime: String, val filename: String)

    fun lese(context: Context, uri: Uri, mime: String, name: String?): Auswahl {
        var filename = name
        if (filename.isNullOrBlank()) {
            filename = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val puffer = ByteArray(64 * 1024)
            var gesamt = 0L
            while (true) {
                val n = s.read(puffer)
                if (n < 0) break
                gesamt += n
                if (gesamt > MAX_BYTES) throw IllegalArgumentException("Datei zu gross (max. 25 MB)")
                out.write(puffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Datei nicht lesbar")
        return Auswahl(bytes, mime.ifBlank { "application/octet-stream" }, filename ?: "datei")
    }

    /**
     * Hochladen wie sendMedia der PWA. Gruppe: Klartext; DM: verschluesselt, Schluessel
     * in der Beschreibung. Die Klartext-Kopie wird lokal abgelegt (sofort sichtbar).
     */
    fun hochladen(context: Context, api: RosenwegApi, chat: RwChat, a: Auswahl): Beschreibung {
        val istDm = chat.kind == "dm"
        val mtype = if (a.mime.startsWith("image/")) "image" else "file"
        val (daten, key, iv) = if (istDm) {
            val k = RwKrypto.zufallsBytes(32)
            val i = RwKrypto.zufallsBytes(12)
            Triple(RwKrypto.aesGcm(true, k, i, a.bytes), RwKrypto.b64(k), RwKrypto.b64(i))
        } else {
            Triple(a.bytes, null, null)
        }
        val mediaId = api.medienHochladen(chat.id, daten, a.filename, a.mime, istDm)
        datei(context, mediaId).writeBytes(a.bytes)
        return Beschreibung(mediaId, a.mime, a.filename, a.bytes.size.toLong(), mtype, key, iv)
    }

    /** RWMEDIA1-Klartext einer DM: {"t": Text, "m": Beschreibung} */
    fun dmKlartext(text: String, b: Beschreibung): String =
        RwKrypto.MEDIA_PREFIX + buildJsonObject {
            put("t", text)
            put("m", parseJson(json(b))!!)
        }.toString()
}
