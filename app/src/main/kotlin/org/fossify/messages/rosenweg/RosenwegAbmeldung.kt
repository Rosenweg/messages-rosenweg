package org.fossify.messages.rosenweg

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.fossify.messages.databases.MessagesDatabase
import org.fossify.messages.helpers.MessagingCache
import java.io.File
import java.security.KeyStore

/**
 * Abmelden (auch auf dem eigenen Handy: RosenwegPrivatAnmeldung.abmelden/Widerruf ruft allesLoeschen).
 * Auf dem geteilten Telefon: der Messenger loescht sich SELBST (statt
 * clearApplicationUserData durch Rosenweg Gerät). So bleiben die per adb erteilten
 * Notfall-Rechte erhalten (Full-Screen-Intent, exakte Wecker, "Nicht stören",
 * Akku-Ausnahme), und nichts der vorherigen Person bleibt zurueck.
 *
 * Vertrag mit Rosenweg Gerät (Katalog.selbstLoeschenLassen):
 *  - Broadcast "ch.rosenweg4303.ABGEMELDET" an dieses Paket (Empfaenger geschuetzt
 *    mit ch.rosenweg4303.permission.ANLAGE)
 *  - Bestaetigung: call(content://ch.rosenweg4303.messages.rosenweg.loeschen, "gewischt")
 *    → Bundle {ok: Boolean, zeit: Long} (ok nur nach einem fertigen Loeschen in diesem Prozess)
 */
object RosenwegAbmeldung {
    private const val TAG = "RosenwegAbmeldung"
    const val AKTION = "ch.rosenweg4303.ABGEMELDET"

    /** Zeitpunkt des letzten fertigen Loeschens in diesem Prozess (nur im Speicher) */
    @Volatile
    var gewischtZeit = 0L
        private set

    /** Alles loeschen (blockierend, idempotent, auch ohne je angemeldet gewesen zu sein). */
    @Synchronized
    fun allesLoeschen(context: Context) {
        val app = context.applicationContext
        val start = System.currentTimeMillis()

        // 1. Dienst und Wecker stoppen, Sirene aus
        try {
            MessengerVerbindung.sofortBeenden(app)
        } catch (e: Exception) {
            Log.w(TAG, "Dienst stoppen", e)
        }
        try {
            RosenwegNotfall.allesAus(app)
        } catch (e: Exception) {
            Log.w(TAG, "Notfall aus", e)
        }
        try {
            val am = app.getSystemService(AlarmManager::class.java)
            PendingIntent.getBroadcast(
                app, 0,
                Intent(app, RosenwegPingReceiver::class.java).setAction("ch.rosenweg4303.messages.MQTT_PING"),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { am?.cancel(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Ping-Wecker", e)
        }

        // 2. Datenbanken schliessen und loeschen
        try {
            RosenwegDatenbank.schliesse()
        } catch (e: Exception) {
            Log.w(TAG, "rosenweg.db schliessen", e)
        }
        try {
            MessagesDatabase.schliesse()
        } catch (e: Exception) {
            Log.w(TAG, "conversations.db schliessen", e)
        }
        RosenwegSpeicher.vergiss()
        app.databaseList().forEach { name ->
            try {
                app.deleteDatabase(name)
            } catch (e: Exception) {
                Log.w(TAG, "DB $name", e)
            }
        }

        // 3. alle SharedPreferences (Fossify "Prefs", Rosenweg-Konto, Geraete-ID, Wasserzeichen …)
        val prefsOrdner = File(app.applicationInfo.dataDir, "shared_prefs")
        prefsOrdner.listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            try {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                app.deleteSharedPreferences(name)
            } catch (e: Exception) {
                Log.w(TAG, "Prefs $name", e)
            }
        }

        // 4. Dateien (Medien, Bilder, Schluesseldateien, Caches)
        val ordner = mutableListOf(app.filesDir, app.cacheDir, app.noBackupFilesDir, app.codeCacheDir)
        app.getExternalFilesDirs(null)?.filterNotNull()?.let { ordner.addAll(it) }
        app.externalCacheDirs?.filterNotNull()?.let { ordner.addAll(it) }
        ordner.forEach { leere(it) }

        // 5. Schluessel im AndroidKeyStore (Geraeteschluessel, Verpackungsschluessel)
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.aliases().toList().filter { it.startsWith("rw_dm_") || it == RosenwegPrivatAnmeldung.ALIAS }.forEach { ks.deleteEntry(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Keystore", e)
        }

        // 6. Zwischenspeicher im Prozess, Benachrichtigungen
        RosenwegKonto.vergiss()
        RwGeraeteSchluessel.vergiss()
        MessagingCache.namePhoto.evictAll()
        MessagingCache.participantsCache.evictAll()
        try {
            app.getSystemService(NotificationManager::class.java)?.cancelAll()
        } catch (e: Exception) {
            Log.w(TAG, "Benachrichtigungen", e)
        }

        gewischtZeit = System.currentTimeMillis()
        Log.i(TAG, "abgemeldet: alle Messenger-Daten geloescht (${gewischtZeit - start} ms)")
    }

    private fun leere(dir: File?) {
        dir ?: return
        dir.listFiles()?.forEach { f ->
            try {
                f.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "loeschen ${f.name}", e)
            }
        }
    }
}

/** ch.rosenweg4303.ABGEMELDET (nur Rosenweg Gerät, Permission ANLAGE) */
class RosenwegAbmeldungReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RosenwegAbmeldung.AKTION) return
        val ende = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                RosenwegSmsModus.abgleichen(app)
                RosenwegAbmeldung.allesLoeschen(app)
            } catch (e: Exception) {
                Log.w("RosenwegAbmeldung", "Abmelden", e)
            } finally {
                ende.finish()
            }
        }.start()
    }
}

/** Bestaetigung fuer Rosenweg Gerät: call("gewischt") → {ok, zeit} */
class RosenwegLoeschProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "gewischt") return null
        // call() prueft android:permission nicht selbst
        val ctx = context ?: return null
        if (ctx.checkCallingPermission("ch.rosenweg4303.permission.ANLAGE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("nur Rosenweg Gerät")
        }
        val zeit = RosenwegAbmeldung.gewischtZeit
        return Bundle().apply {
            putBoolean("ok", zeit > 0)
            if (zeit > 0) putLong("zeit", zeit)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
