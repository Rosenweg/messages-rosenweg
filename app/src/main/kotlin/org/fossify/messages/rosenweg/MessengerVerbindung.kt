package org.fossify.messages.rosenweg

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Vordergrund-Dienst "MessengerVerbindung": haelt die MQTT-Verbindung (WSS) zum
 * Rosenweg-Broker offen, holt Verlauf nach, nimmt Nachrichten auf, zeigt die
 * normale Nachrichten-Benachrichtigung und verschickt ausstehende eigene Nachrichten.
 *
 * Alles laeuft auf EINEM Arbeits-Thread (arbeiter); Rueckrufe des eigenen
 * MQTT-Clients (RwMqttClient, OkHttp-WebSocket) werden dorthin weitergereicht.
 * Das MQTT-Lebenszeichen (PINGREQ) plant der AlarmManager, damit Doze die
 * Verbindung nicht einschlafen laesst. Bei jeder (Wieder-)Verbindung wird ein frisches MQTT-JWT geholt,
 * weil es die Topic-Rechte traegt.
 */
class MessengerVerbindung : Service() {

    companion object {
        private const val TAG = "MessengerVerbindung"
        private const val BROKER = "wss://mqtt.rosenweg4303.ch/mqtt"
        private const val KEEPALIVE_S = 60
        private const val PING_ALLE_MS = 45_000L
        private const val PING_ANTWORT_MS = 10_000L
        private const val CONNACK_WARTEN_S = 30L
        private const val AKTION_PING = "ch.rosenweg4303.messages.MQTT_PING"
        private const val KANAL = "rosenweg_verbindung"
        private const val NOTIF_ID = 4303
        private const val AKTION_SENDEN = "ch.rosenweg4303.messages.SENDEN"
        private const val VERZEICHNIS_ALTER_MS = 60 * 60 * 1000L
        private const val MAX_WARTEN_S = 300L

        @Volatile
        private var laeuft = false

        @Volatile
        private var instanz: MessengerVerbindung? = null

        /** Startet den Dienst, falls ein Messenger-Zugang vorhanden ist (prueft im Hintergrund). */
        fun starteWennKonto(context: Context) {
            val app = context.applicationContext
            ensureBackgroundThread {
                if (RosenwegKonto.hole(app) != null) {
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, MessengerVerbindung::class.java))
                    } catch (e: Exception) {
                        Log.w(TAG, "Dienst nicht startbar", e)
                    }
                }
            }
        }

        /** Ausstehende eigene Nachrichten jetzt senden (startet den Dienst bei Bedarf). */
        fun sendeAusstehende(context: Context) {
            val app = context.applicationContext
            instanz?.let { dienst ->
                try {
                    dienst.arbeiter.execute { dienst.sendeAusstehendeJetzt() }
                    return
                } catch (_: Exception) {
                }
            }
            try {
                ContextCompat.startForegroundService(app, Intent(app, MessengerVerbindung::class.java).setAction(AKTION_SENDEN))
            } catch (e: Exception) {
                Log.w(TAG, "Senden: Dienst nicht startbar", e)
            }
        }

        fun istAktiv() = laeuft

        /** "schreibt …" an die anderen (qos 0, typing/<conv>) */
        fun tippe(chatId: String, tippt: Boolean) {
            instanz?.let { d ->
                try {
                    d.arbeiter.execute {
                        val c = d.client ?: return@execute
                        val ich = d.konto?.userId ?: return@execute
                        val chat = d.chatsNachId[chatId] ?: return@execute
                        if (!d.verbunden || !chat.canWrite) return@execute
                        c.publish("typing/$chatId", RosenwegLive.tippNutzlast(ich, tippt).toByteArray(), 0, false)
                    }
                } catch (_: Exception) {
                }
            }
        }

        /** Nach neuem Gespraech/Mitgliedschaft: frisches JWT (Topic-Rechte) und neu abonnieren. */
        fun neuVerbinden() {
            instanz?.let { d ->
                try {
                    d.arbeiter.execute { d.verbinde() }
                } catch (_: Exception) {
                }
            }
        }

        /** Vom Ping-Alarm (RosenwegPingReceiver), mit gehaltenem WakeLock. */
        internal fun pingVomAlarm(context: Context, wachhalten: PowerManager.WakeLock) {
            val dienst = instanz
            if (dienst == null) {
                if (wachhalten.isHeld) wachhalten.release()
                return
            }
            try {
                dienst.arbeiter.execute { dienst.pinge(wachhalten) }
            } catch (_: Exception) {
                if (wachhalten.isHeld) wachhalten.release()
            }
        }
    }

    private val arbeiter = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "rosenweg-mqtt") }.apply {
        // geplante Neuversuche nach dem Beenden des Dienstes verwerfen
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private lateinit var speicher: RosenwegSpeicher

    /** Medien-Downloads getrennt vom MQTT-Arbeits-Thread */
    private val lader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "rosenweg-medien") }
    private val ladeAuftraege = java.util.Collections.synchronizedSet(HashSet<String>())

    private var client: RwMqttClient? = null
    private var pingBackup: ScheduledFuture<*>? = null
    @Volatile
    private var kontoNeuHolen = false
    private var generation = 0
    private var verbunden = false
    private var verbindetGerade = false
    private var wartenS = 2L
    private var geplant: ScheduledFuture<*>? = null
    private var erneuerung: ScheduledFuture<*>? = null
    private var konto: RosenwegKonto? = null
    private var schluessel: RwGeraeteSchluessel? = null
    private val schluesselCache = HashMap<String, Pair<Long, List<RwGeraet>>>()
    private var pubTopics: List<String>? = null
    private var verzeichnisGeholt = 0L
    private var chatsNachPrefix: Map<String, RwChat> = emptyMap()
    private var chatsNachId: Map<String, RwChat> = emptyMap()
    private var spiegelGeprueft = false
    private var netzRueckruf: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        speicher = RosenwegSpeicher.get(this)
        starteVordergrund()
        laeuft = true
        instanz = this
        beobachteNetz()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starteVordergrund()
        val senden = intent?.action == AKTION_SENDEN
        arbeiter.execute {
            if (verbunden) {
                if (senden) sendeAusstehendeJetzt()
            } else if (!verbindetGerade) {
                wartenS = 2L
                verbinde()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        laeuft = false
        instanz = null
        netzRueckruf?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        stoppePingAlarm()
        arbeiter.execute { trenne(sauber = true) }
        arbeiter.shutdown()
        lader.shutdownNow()
        super.onDestroy()
    }

    // ── Vordergrund-Benachrichtigung ────────────────────────────────────

    private fun starteVordergrund() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(KANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL, "Rosenweg Messenger Verbindung", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle("Rosenweg Messenger verbunden")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(oeffnen)
            .build()
        // Android 14+: remoteMessaging (kein 6-h-Limit wie dataSync ab Android 15,
        // und aus BOOT_COMPLETED startbar); davor dataSync.
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, typ)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fehlgeschlagen", e)
            stopSelf()
        }
    }

    private fun beobachteNetz() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val rueckruf = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                arbeiter.execute {
                    if (!verbunden && !verbindetGerade) {
                        wartenS = 2L
                        geplant?.cancel(false)
                        verbinde()
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(rueckruf)
            netzRueckruf = rueckruf
        } catch (e: Exception) {
            Log.w(TAG, "Netz-Rueckruf", e)
        }
    }

    // ── Verbindungsaufbau (Arbeits-Thread) ──────────────────────────────

    private fun verbinde() {
        if (arbeiter.isShutdown) return
        trenne(sauber = false)
        verbindetGerade = true
        val k = RosenwegKonto.hole(this, erzwingen = kontoNeuHolen)
        kontoNeuHolen = false
        if (k == null) {
            Log.i(TAG, "kein Messenger-Zugang mehr - Dienst endet")
            verbindetGerade = false
            stopSelf()
            return
        }
        konto = k
        // Geteiltes Telefon: anderer Benutzer als zuletzt → nichts vom Vorgaenger zeigen
        val vorher = RosenwegKonto.eigeneIdGespeichert(this)
        if (vorher != null && vorher != k.userId) {
            Log.i(TAG, "anderer Benutzer angemeldet - lokaler Messenger-Verlauf geloescht")
            speicher.allesLoeschen()
            RwGeraeteSchluessel.vergiss()
            refreshConversations()
        }
        RosenwegKonto.merkeEigeneId(this, k.userId, k.name)
        try {
            val api = RosenwegApi(k)
            val s = RwGeraeteSchluessel.fuer(this, k.userId)
            schluessel = s
            val deviceId = s.geraeteId
            try {
                api.registriereGeraet(deviceId, "Rosenweg Messenger (${Build.MODEL})", s.oeffentlichRoh)
            } catch (e: Exception) {
                Log.w(TAG, "Geraet nicht registriert", e)
            }
            val zugang = api.mqttToken(deviceId)
            pubTopics = zugang.angaben?.pubTopics?.takeIf { zugang.angaben.sub != null }

            if (System.currentTimeMillis() - verzeichnisGeholt > VERZEICHNIS_ALTER_MS) {
                try {
                    if (speicher.speichereVerzeichnis(api.verzeichnis(), leseJwt(k.token)?.avatar)) {
                        refreshMessages()
                        refreshConversations()
                    }
                    verzeichnisGeholt = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.w(TAG, "Verzeichnis", e)
                }
            }

            val gespraeche = api.gespraeche()
            val mitglieder = HashMap<String, List<String>>()
            for (g in gespraeche) {
                if (g.kind == "group") {
                    try {
                        mitglieder[g.id] = api.mitglieder(g.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Mitglieder ${g.id}", e)
                    }
                }
            }
            // Frische Installation / Daten geloescht: Fossifys einmalige Cache-Leerung jetzt
            // erledigen, bevor der Spiegel geschrieben wird (sonst loescht MainActivity ihn)
            if (!config.wasDbCleared) {
                messagesDB.deleteAllExceptRosenweg()
                config.wasDbCleared = true
            }
            val chats = speicher.gleicheGespraecheAb(gespraeche, pubTopics, mitglieder, k.istAdmin)
            if (!spiegelGeprueft) {
                // Spiegel pruefen/neu schreiben, verwaiste Rosenweg-Threads entfernen (einmal je Prozess)
                speicher.pruefeSpiegel()
                spiegelGeprueft = true
            }
            chatsNachPrefix = chats.associateBy { it.topicPrefix }
            chatsNachId = chats.associateBy { it.id }
            refreshConversations()

            verbindeMqtt(k, deviceId, zugang)
        } catch (e: Exception) {
            Log.w(TAG, "Verbindung fehlgeschlagen", e)
            verbindetGerade = false
            if (e is RosenwegApi.HttpFehler && e.code == 401) {
                RosenwegKonto.vergiss()
            }
            planeNeuversuch()
        }
    }

    private fun verbindeMqtt(k: RosenwegKonto, deviceId: String, zugang: RosenwegApi.MqttZugang) {
        val gen = ++generation
        val c = RwMqttClient(BROKER, object : RwMqttClient.Hoerer {
            override fun verbunden() {
                arbeiter.execute { if (gen == generation) nachVerbindung(k, zugang) }
            }

            override fun abgelehnt(code: Int) {
                Log.w(TAG, "Broker lehnt ab: CONNACK $code")
                // 4/5 = Anmeldung abgelehnt → beim naechsten Versuch Zugang + Token neu holen
                if (code == 4 || code == 5) kontoNeuHolen = true
            }

            override fun nachricht(topic: String, payload: ByteArray, retained: Boolean) {
                val text = String(payload, Charsets.UTF_8)
                arbeiter.execute { if (gen == generation) eingang(topic, text) }
            }

            override fun getrennt(grund: Throwable?) {
                arbeiter.execute {
                    if (gen != generation) return@execute
                    Log.w(TAG, "MQTT getrennt", grund)
                    verbunden = false
                    verbindetGerade = false
                    stoppePingAlarm()
                    planeNeuversuch()
                }
            }
        }, log = { msg, t -> Log.w(TAG, msg, t) })
        client = c
        c.verbinde(
            RwMqttClient.Optionen(
                clientId = "and_$deviceId",
                username = zugang.token,
                password = zugang.token,
                keepAliveSek = KEEPALIVE_S,
                will = MqttCodec.Will(RosenwegTopics.presence(k.userId), bauePresence(false).toByteArray(), 1, true),
            )
        )
        // ohne CONNACK in 30 s: abbrechen und neu versuchen
        arbeiter.schedule({
            if (gen == generation && !verbunden) {
                Log.w(TAG, "kein CONNACK in ${CONNACK_WARTEN_S}s")
                c.abbrechen()
                verbindetGerade = false
                planeNeuversuch()
            }
        }, CONNACK_WARTEN_S, TimeUnit.SECONDS)
    }

    private fun nachVerbindung(k: RosenwegKonto, zugang: RosenwegApi.MqttZugang) {
        val c = client ?: return
        verbunden = true
        verbindetGerade = false
        wartenS = 2L
        c.publish(RosenwegTopics.presence(k.userId), bauePresence(true).toByteArray(), 1, true)
        val filter = ArrayList<String>()
        filter.add(RosenwegTopics.KRISE)
        filter.add("presence/#")
        filter.add("inbox/${k.userId}")
        for (chat in chatsNachId.values) {
            filter.add(RosenwegTopics.empfangsFilter(chat.topicPrefix))
            filter.add(RosenwegTopics.control(chat.id))
            filter.add(RwQuittung.filter(chat.id))
            filter.add("typing/${chat.id}")
        }
        c.subscribe(filter, 1) { codes ->
            if (codes.any { it == 0x80 }) Log.w(TAG, "Abonnement teilweise abgelehnt: $codes")
        }
        planePingAlarm()
        planePingBackup()

        // Neues JWT kurz vor Ablauf (bzw. vor Ablauf des Anlage-Tokens) → neu verbinden
        val bisToken = (zugang.expiresInSek - 120).coerceAtLeast(60)
        val bisKonto = ((k.gueltigBisMs - System.currentTimeMillis() - RosenwegKonto.ERNEUERN_VOR_MS) / 1000)
            .let { if (k.gueltigBisMs <= 0) Long.MAX_VALUE else it.coerceAtLeast(60) }
        erneuerung?.cancel(false)
        erneuerung = arbeiter.schedule({ verbinde() }, minOf(bisToken, bisKonto), TimeUnit.SECONDS)

        holeVerlauf()
        holeBackfill()
        schiebeBackfill()
        speicher.fehlendeMedien().forEach { ladeMedien(it) }
        sendeAusstehendeJetzt()
    }

    private fun holeVerlauf() {
        val k = konto ?: return
        val api = RosenwegApi(k)
        var geaendert = false
        for (chat0 in chatsNachId.values) {
            var chat = speicher.chat(chat0.id) ?: continue
            val erstimport = chat.lastServerId == 0L
            try {
                var seiten = 0
                while (seiten++ < 20) {
                    val liste = api.verlauf(chat.id, chat.lastServerId)
                    for (d in liste) {
                        speicher.nimmAuf(chat, klartext(chat, d), ausVerlauf = true, gelesen = erstimport)
                        geaendert = true
                    }
                    chat = speicher.chat(chat.id) ?: break
                    if (liste.size < 500) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Verlauf ${chat.id}", e)
            }
        }
        if (geaendert) {
            refreshMessages()
            refreshConversations()
        }
    }

    /** DM entschluesseln (Eintrag fuer dieses Geraet); sonst unveraendert ("🔒 Nicht entschlüsselbar"). */
    private fun klartext(chat: RwChat, d: RwNachrichtDaten): RwNachrichtDaten {
        if (chat.kind != "dm" || d.ciphertext == null || d.deleted) return d
        val klar = try {
            schluessel?.entschluessele(d.ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "DM nicht entschluesselbar", e)
            null
        } ?: return d
        val medien = RwKrypto.medienAusKlartext(klar)
        return d.copy(
            body = medien?.first ?: klar,
            plain = klar,
            mediaJson = medien?.second ?: d.mediaJson,
            mediaName = medien?.second?.let { medienNameAusJson(it) } ?: d.mediaName,
        )
    }

    /** Verlauf, den andere Geraete fuer dieses neu verschluesselt haben, abholen und anwenden. */
    private fun holeBackfill() {
        val k = konto ?: return
        val s = schluessel ?: return
        try {
            speicher.speichereBackfill(RosenwegApi(k).backfillHolen(s.geraeteId))
        } catch (e: Exception) {
            Log.w(TAG, "Verlauf fuer neues Geraet nicht abholbar", e)
        }
        val n = speicher.wendeBackfillAn { s.entschluessele(it) }
        if (n > 0) {
            Log.i(TAG, "$n Direktnachrichten aus dem Verlauf anderer Geraete lesbar")
            refreshMessages()
            refreshConversations()
        }
    }

    /** Mediendatei im Hintergrund holen (+ DM entschluesseln), danach Thread neu zeichnen */
    private fun ladeMedien(m: RwMessage) {
        val b = RosenwegMedien.lies(m.media) ?: return
        if (m.deleted || RosenwegMedien.vorhanden(this, b.mediaId)) return
        if (b.size > RosenwegMedien.MAX_BYTES) return
        if (!ladeAuftraege.add(b.mediaId)) return
        val k = konto ?: return
        lader.execute {
            try {
                RosenwegMedien.lade(this, RosenwegApi(k), b)
                speicher.spiegleNeu(m.rowId)
                refreshMessages()
            } catch (e: Exception) {
                Log.w(TAG, "Medien ${b.mediaId} nicht ladbar", e)
            } finally {
                ladeAuftraege.remove(b.mediaId)
            }
        }
    }

    private var inboxGeplant: ScheduledFuture<*>? = null

    private var backfillGeschoben = 0L

    /**
     * Wie autoBackfillDevices der PWA: lesbare DMs fuer eigene andere Geraete und die
     * Geraete des Gegenuebers (zuletzt gesehen < 45 Tage) neu verschluesseln und unter
     * /api/link/backfill ablegen. Wasserzeichen je (Chat, Geraet); hoechstens stuendlich.
     */
    private fun schiebeBackfill() {
        val jetzt = System.currentTimeMillis()
        if (jetzt - backfillGeschoben < 60 * 60_000) return
        backfillGeschoben = jetzt
        val k = konto ?: return
        val s = schluessel ?: return
        val api = RosenwegApi(k)
        val grenze = jetzt - 45L * 24 * 60 * 60_000
        val prefs = getSharedPreferences("rosenweg_backfill_${k.userId}", MODE_PRIVATE)
        val eigene = try {
            api.eigeneGeraete().filter { it.deviceId != s.geraeteId && (it.lastSeen == null || it.lastSeen >= grenze) }
        } catch (e: Exception) {
            Log.w(TAG, "eigene Geraete nicht abrufbar", e)
            emptyList()
        }
        for (chat in chatsNachId.values.filter { it.kind == "dm" }) {
            val partner = speicher.dmPartner(chat)
            val fremde = try {
                partner?.let { geraeteVon(it) }?.filter { it.lastSeen == null || it.lastSeen >= grenze } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Geraete des Gegenuebers nicht abrufbar", e)
                emptyList()
            }
            // je Geraet gilt der neueste Schluessel (Server liefert aufsteigend nach added_at)
            val ziele = (eigene + fremde).reversed().distinctBy { it.deviceId }.filter { it.deviceId != s.geraeteId }
            if (ziele.isEmpty()) continue
            val lesbar = speicher.lesbareDms(chat.id)
            val maxId = lesbar.maxOfOrNull { it.serverId ?: 0L } ?: continue
            for (ziel in ziele) {
                val wmKey = "${chat.id}|${ziel.deviceId}"
                val wm = prefs.getLong(wmKey, 0L)
                if (wm >= maxId) continue
                val offen = lesbar.filter { (it.serverId ?: 0L) > wm }
                try {
                    for (teil in offen.chunked(200)) {
                        val eintraege = teil.mapNotNull { m ->
                            val buendel = try {
                                RwKrypto.verschluessele(m.plain ?: return@mapNotNull null, mapOf(ziel.deviceId to ziel.pubkey))
                            } catch (_: Exception) {
                                return@mapNotNull null
                            }
                            buildJsonObject {
                                put("convId", chat.id)
                                put("origMsgId", m.serverId)
                                put("senderId", m.senderId)
                                put("createdAt", java.time.Instant.ofEpochMilli(m.ts).toString())
                                put("ciphertext", parseJson(buendel)!!)
                            }
                        }
                        if (eintraege.isNotEmpty()) api.backfillSenden(ziel.deviceId, JsonArray(eintraege).toString())
                    }
                    prefs.edit().putLong(wmKey, maxId).apply()
                    Log.i(TAG, "Verlauf ${chat.id}: ${offen.size} DMs fuer Geraet ${ziel.deviceId.take(12)}… abgelegt")
                } catch (e: Exception) {
                    Log.w(TAG, "Verlauf fuer Geraet ${ziel.deviceId.take(12)}… nicht abgelegt", e)
                }
            }
        }
    }

    /** Geraeteschluessel eines Benutzers (5 min zwischengespeichert). */
    private fun geraeteVon(userId: String): List<RwGeraet> {
        val jetzt = System.currentTimeMillis()
        schluesselCache[userId]?.takeIf { jetzt - it.first < 5 * 60_000 }?.let { return it.second }
        val k = konto ?: return emptyList()
        val liste = RosenwegApi(k).schluessel(userId)
        schluesselCache[userId] = jetzt to liste
        return liste
    }

    private fun trenne(sauber: Boolean) {
        generation++
        verbunden = false
        quittungenUnterwegs.clear()
        erneuerung?.cancel(false)
        pingBackup?.cancel(false)
        val c = client ?: return
        client = null
        try {
            if (sauber && c.istVerbunden) {
                konto?.let { c.publish(RosenwegTopics.presence(it.userId), bauePresence(false).toByteArray(), 1, true) }
                c.trenne()
            } else {
                c.abbrechen()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trennen", e)
        }
    }

    // ── Lebenszeichen (PINGREQ) ─────────────────────────────────────────

    private fun pingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 0,
        Intent(this, RosenwegPingReceiver::class.java).setAction(AKTION_PING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun planePingAlarm() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val zeit = SystemClock.elapsedRealtime() + PING_ALLE_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, zeit, pingIntent())
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, zeit, pingIntent())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping-Alarm", e)
        }
    }

    private fun stoppePingAlarm() {
        try {
            getSystemService(AlarmManager::class.java)?.cancel(pingIntent())
        } catch (_: Exception) {
        }
    }

    /** Ersatz im Prozess, falls der (ungenaue) Alarm sich verspaetet. */
    private fun planePingBackup() {
        pingBackup?.cancel(false)
        pingBackup = arbeiter.scheduleWithFixedDelay({ pinge(null) }, PING_ALLE_MS, PING_ALLE_MS, TimeUnit.MILLISECONDS)
    }

    /** Arbeits-Thread: PINGREQ senden, nach 10 s PINGRESP pruefen, sonst neu verbinden. */
    private fun pinge(wachhalten: PowerManager.WakeLock?) {
        fun freigeben() {
            if (wachhalten?.isHeld == true) wachhalten.release()
        }
        val c = client
        if (!verbunden || c == null) {
            freigeben()
            return
        }
        if (wachhalten != null) planePingAlarm()
        if (!c.ping()) {
            freigeben()
            Log.w(TAG, "PINGREQ nicht sendbar - neu verbinden")
            verbinde()
            return
        }
        val gen = generation
        arbeiter.schedule({
            if (gen == generation && c.pingAusstehend() &&
                System.currentTimeMillis() - c.letzterPingMs >= PING_ANTWORT_MS
            ) {
                Log.w(TAG, "kein PINGRESP - neu verbinden")
                verbinde()
            }
            freigeben()
        }, PING_ANTWORT_MS, TimeUnit.MILLISECONDS)
    }

    private fun planeNeuversuch() {
        if (arbeiter.isShutdown) return
        geplant?.cancel(false)
        val warte = wartenS
        wartenS = (wartenS * 2).coerceAtMost(MAX_WARTEN_S)
        geplant = arbeiter.schedule({ if (!verbunden && !verbindetGerade) verbinde() }, warte, TimeUnit.SECONDS)
    }

    // ── Eingang (Arbeits-Thread) ────────────────────────────────────────

    private fun eingang(topic: String, text: String) {
        try {
            when {
                topic == RosenwegTopics.KRISE -> {
                    val aktiv = (parseJson(text) as? kotlinx.serialization.json.JsonObject)?.bool("active") == true
                    Log.i(TAG, "sys/crisis: aktiv=$aktiv")
                    RosenwegNotfall.krise(this, aktiv)
                }

                topic.startsWith("inbox/") -> {
                    // neues/entferntes Gespraech: (entprellt) Token + Gespraeche neu, neu verbinden
                    Log.i(TAG, "inbox: $text")
                    inboxGeplant?.cancel(false)
                    inboxGeplant = arbeiter.schedule({ verbinde() }, 1500, TimeUnit.MILLISECONDS)
                }

                topic.startsWith("presence/") -> RosenwegLive.nimmPraesenz(topic, text)

                topic.startsWith("typing/") -> RosenwegLive.nimmTippen(topic, text, speicher.eigeneId)

                topic.startsWith("receipt/") -> {
                    val q = RwQuittung.parse(topic, text) ?: return
                    if (q.chatId !in chatsNachId) return
                    if (q.userId == speicher.eigeneId && q.stufe >= RwQuittung.GELESEN) {
                        RosenwegNotfall.gelesenErkannt(this, q.chatId, q.clientMsgId)
                    }
                    if (speicher.nimmQuittung(q)) refreshMessages()
                }

                topic.startsWith("control/") -> {
                    val chat = chatsNachId[topic.removePrefix("control/")] ?: return
                    val s = parseSteuerung(text) ?: return
                    if (s is RwSteuerung.Gruppe) {
                        // Titel/Mitglieder geaendert → neu abgleichen (neues JWT fuer neue Rechte)
                        arbeiter.execute { verbinde() }
                        return
                    }
                    if (speicher.wendeAn(chat, s)) {
                        refreshMessages()
                        refreshConversations()
                    }
                }

                else -> {
                    val (prefix, _) = RosenwegTopics.nachrichtTopic(topic) ?: return
                    val chat = chatsNachPrefix[prefix] ?: return
                    val d = parseLiveNachricht(topic, text) ?: return
                    val e = speicher.nimmAuf(speicher.chat(chat.id) ?: chat, klartext(chat, d), ausVerlauf = false, gelesen = false)
                    refreshMessages()
                    refreshConversations()
                    RosenwegLive.nachrichtVon(chat.id, e.nachricht.senderId)
                    if (e.nachricht.media != null) ladeMedien(e.nachricht)
                    // Notfall/Entwarnung nur fuer frische Live-Nachrichten (nicht < 10 min alt)
                    if (e.neu && System.currentTimeMillis() - d.ts < 10 * 60_000) {
                        val eigen = e.nachricht.senderId == speicher.eigeneId
                        val istEntwarnung = chat.id == "notfall" && (d.clear || RosenwegNotfall.istEntwarnung(d.body))
                        if (!eigen || istEntwarnung) {
                            RosenwegNotfall.pruefe(this, chat, d, speicher.titel(chat), e.absenderName)
                        }
                    }
                    if (e.neu && e.nachricht.senderId != speicher.eigeneId) {
                        sendeQuittungen()
                        benachrichtige(e)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Eingang $topic", ex)
        }
    }

    private fun benachrichtige(e: RosenwegSpeicher.Eingang) {
        val titel = speicher.titel(e.chat)
        val absender = if (e.chat.kind == "dm") e.absenderName else "${e.absenderName} · $titel"
        showReceivedMessageNotification(
            messageId = e.nachricht.mirroredMessageId,
            isMms = false,
            address = RW_ADRESSE + e.chat.id,
            senderName = absender,
            body = e.anzeige,
            threadId = e.chat.threadId,
            bitmap = getNotificationBitmap(
                speicher.personenBildFuer(e.nachricht.senderId).ifEmpty { speicher.gespraechsBild(e.chat) }
            ),
        )
    }

    // ── Senden (Arbeits-Thread) ─────────────────────────────────────────

    /** Quittungen in Arbeit (rowId → Stufe), damit nichts doppelt rausgeht */
    private val quittungenUnterwegs = HashMap<Long, Int>()

    /** Arbeits-Thread: "zugestellt"/"gelesen" fuer fremde Nachrichten an receipt/<conv>/<ich>. */
    private fun sendeQuittungen() {
        val c = client ?: return
        if (!verbunden || !c.istVerbunden) return
        val ich = konto?.userId ?: return
        for (m in speicher.offeneQuittungen()) {
            if (m.senderId == ich) {
                speicher.quittungGesendet(m.rowId, 2)
                continue
            }
            val id = m.clientMsgId ?: continue
            if ((quittungenUnterwegs[m.rowId] ?: 0) >= m.rcptSoll) continue
            if (m.chatId !in chatsNachId) continue
            quittungenUnterwegs[m.rowId] = m.rcptSoll
            val topic = RwQuittung.topic(m.chatId, ich)
            // "gelesen" schliesst "zugestellt" ein; fehlt "zugestellt" noch, zuerst senden
            if (m.rcptGesendet < RwQuittung.ZUGESTELLT && m.rcptSoll >= RwQuittung.GELESEN) {
                c.publish(topic, RwQuittung.nutzlast(id, RwQuittung.ZUGESTELLT).toByteArray(), 1, false)
            }
            val stufe = m.rcptSoll
            c.publish(topic, RwQuittung.nutzlast(id, stufe).toByteArray(), 1, false) { ok ->
                arbeiter.execute {
                    if (quittungenUnterwegs[m.rowId] == stufe) quittungenUnterwegs.remove(m.rowId)
                    if (ok) speicher.quittungGesendet(m.rowId, stufe)
                }
            }
        }
    }

    private fun sendeAusstehendeJetzt() {
        val c = client ?: return
        if (!verbunden || !c.istVerbunden) return
        val ich = konto?.userId ?: return
        sendeQuittungen()
        for (m in speicher.ausstehende()) {
            val chat = chatsNachId[m.chatId]
            if (chat == null || !chat.canWrite || m.body == null) {
                speicher.setzeStatus(m.rowId, RwStatus.FEHLER)
                continue
            }
            val nutzlast = if (chat.kind == "dm") {
                // E2E: fuer alle Geraete des Gegenuebers und alle eigenen (auch dieses)
                val partner = speicher.dmPartner(chat)
                val ziele = try {
                    val fremde = partner?.let { geraeteVon(it) } ?: emptyList()
                    if (fremde.isEmpty()) {
                        Log.w(TAG, "DM: Gegenueber hat noch kein Geraet registriert")
                        speicher.setzeStatus(m.rowId, RwStatus.FEHLER)
                        toast("Gegenüber hat den Messenger noch nicht geöffnet – Nachricht nicht gesendet")
                        refreshMessages()
                        continue
                    }
                    (fremde + geraeteVon(ich)).associate { it.deviceId to it.pubkey } +
                        (schluessel?.let { mapOf(it.geraeteId to it.oeffentlichRoh) } ?: emptyMap())
                } catch (e: Exception) {
                    // Netz/Server: ausstehend lassen, naechster Versuch bei der naechsten Verbindung
                    Log.w(TAG, "DM: Schluessel nicht abrufbar", e)
                    continue
                }
                try {
                    baueAusgehendDm(ich, m.clientMsgId ?: continue, m.ts, RwKrypto.verschluessele(m.plain ?: m.body, ziele), m.replyTo)
                } catch (e: Exception) {
                    Log.w(TAG, "DM: Verschluesselung fehlgeschlagen", e)
                    speicher.setzeStatus(m.rowId, RwStatus.FEHLER)
                    continue
                }
            } else {
                baueAusgehend(ich, m.clientMsgId ?: continue, m.ts, m.body, m.replyTo, m.media)
            }
            // QoS 1: PUBACK → gesendet; ohne PUBACK bleibt sie ausstehend und geht bei der
            // naechsten Verbindung erneut raus (Server entdoppelt am clientMsgId)
            c.publish(RosenwegTopics.sendeTopic(chat.topicPrefix, ich), nutzlast.toByteArray(Charsets.UTF_8), 1, false) { ok ->
                if (ok) {
                    arbeiter.execute {
                        speicher.setzeStatus(m.rowId, RwStatus.GESENDET)
                        refreshMessages()
                    }
                }
            }
        }
    }
}

/** Ping-Alarm: kurzer WakeLock, dann PINGREQ im Dienst. */
class RosenwegPingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RosenwegMessenger:mqtt-ping")
        wl.acquire(15_000L)
        MessengerVerbindung.pingVomAlarm(context, wl)
    }
}
