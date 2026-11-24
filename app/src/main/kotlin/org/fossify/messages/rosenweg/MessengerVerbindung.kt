package org.fossify.messages.rosenweg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
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
 * Alles laeuft auf EINEM Arbeits-Thread (arbeiter); Paho-Rueckrufe werden dorthin
 * weitergereicht. Bei jeder (Wieder-)Verbindung wird ein frisches MQTT-JWT geholt,
 * weil es die Topic-Rechte traegt.
 */
class MessengerVerbindung : Service() {

    companion object {
        private const val TAG = "MessengerVerbindung"
        private const val BROKER = "wss://mqtt.rosenweg4303.ch:443/mqtt"
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
                        Log.w(TAG, "Dienst nicht startbar: ${e.message}")
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
                Log.w(TAG, "Senden: Dienst nicht startbar: ${e.message}")
            }
        }

        fun istAktiv() = laeuft
    }

    private val arbeiter = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "rosenweg-mqtt") }.apply {
        // geplante Neuversuche nach dem Beenden des Dienstes verwerfen
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private lateinit var speicher: RosenwegSpeicher

    private var client: MqttAsyncClient? = null
    private var generation = 0
    private var verbunden = false
    private var verbindetGerade = false
    private var wartenS = 2L
    private var geplant: ScheduledFuture<*>? = null
    private var erneuerung: ScheduledFuture<*>? = null
    private var konto: RosenwegKonto? = null
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
        arbeiter.execute { trenne(sauber = true) }
        arbeiter.shutdown()
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
            Log.w(TAG, "startForeground fehlgeschlagen: ${e.message}")
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
            Log.w(TAG, "Netz-Rueckruf: ${e.message}")
        }
    }

    // ── Verbindungsaufbau (Arbeits-Thread) ──────────────────────────────

    private fun verbinde() {
        if (arbeiter.isShutdown) return
        trenne(sauber = false)
        verbindetGerade = true
        val k = RosenwegKonto.hole(this)
        if (k == null) {
            Log.i(TAG, "kein Messenger-Zugang mehr - Dienst endet")
            verbindetGerade = false
            stopSelf()
            return
        }
        konto = k
        RosenwegKonto.merkeEigeneId(this, k.userId, k.name)
        try {
            val api = RosenwegApi(k)
            val deviceId = RosenwegKonto.geraeteId(this)
            val zugang = api.mqttToken(deviceId)
            pubTopics = zugang.angaben?.pubTopics?.takeIf { zugang.angaben.sub != null }

            if (System.currentTimeMillis() - verzeichnisGeholt > VERZEICHNIS_ALTER_MS) {
                try {
                    speicher.speichereVerzeichnis(api.verzeichnis())
                    verzeichnisGeholt = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.w(TAG, "Verzeichnis: ${e.message}")
                }
            }

            val gespraeche = api.gespraeche()
            val mitglieder = HashMap<String, List<String>>()
            for (g in gespraeche) {
                if (g.kind == "group") {
                    try {
                        mitglieder[g.id] = api.mitglieder(g.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "Mitglieder ${g.id}: ${e.message}")
                    }
                }
            }
            if (!spiegelGeprueft) {
                // Fossify leert seine DB einmalig (clearAllMessagesIfNeeded) - Spiegel sicher neu schreiben
                speicher.spiegleAlles()
                spiegelGeprueft = true
            }
            val chats = speicher.gleicheGespraecheAb(gespraeche, pubTopics, mitglieder)
            chatsNachPrefix = chats.associateBy { it.topicPrefix }
            chatsNachId = chats.associateBy { it.id }
            refreshConversations()

            verbindeMqtt(k, deviceId, zugang)
        } catch (e: Exception) {
            Log.w(TAG, "Verbindung fehlgeschlagen: ${e.message}")
            verbindetGerade = false
            if (e is RosenwegApi.HttpFehler && e.code == 401) {
                RosenwegKonto.vergiss()
            }
            planeNeuversuch()
        }
    }

    private fun verbindeMqtt(k: RosenwegKonto, deviceId: String, zugang: RosenwegApi.MqttZugang) {
        val gen = ++generation
        val c = MqttAsyncClient(BROKER, "and_$deviceId", MemoryPersistence())
        client = c
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {}

            override fun connectionLost(cause: Throwable?) {
                arbeiter.execute {
                    if (gen != generation) return@execute
                    Log.i(TAG, "Verbindung verloren: ${cause?.message}")
                    verbunden = false
                    planeNeuversuch()
                }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val text = String(message.payload, Charsets.UTF_8)
                arbeiter.execute {
                    if (gen == generation) eingang(topic, text)
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val optionen = MqttConnectOptions().apply {
            userName = zugang.token
            password = zugang.token.toCharArray()
            isCleanSession = true
            keepAliveInterval = 60
            connectionTimeout = 20
            isAutomaticReconnect = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            setWill(RosenwegTopics.presence(k.userId), bauePresence(false).toByteArray(), 1, true)
        }

        c.connect(optionen, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                arbeiter.execute {
                    if (gen != generation) return@execute
                    nachVerbindung(c, k, zugang)
                }
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                arbeiter.execute {
                    if (gen != generation) return@execute
                    Log.w(TAG, "MQTT-Verbindung fehlgeschlagen: ${exception?.message}")
                    verbindetGerade = false
                    planeNeuversuch()
                }
            }
        })
    }

    private fun nachVerbindung(c: MqttAsyncClient, k: RosenwegKonto, zugang: RosenwegApi.MqttZugang) {
        verbunden = true
        verbindetGerade = false
        wartenS = 2L
        try {
            c.publish(RosenwegTopics.presence(k.userId), bauePresence(true).toByteArray(), 1, true)
            val filter = ArrayList<String>()
            filter.add(RosenwegTopics.KRISE)
            for (chat in chatsNachId.values) {
                filter.add(RosenwegTopics.empfangsFilter(chat.topicPrefix))
                filter.add(RosenwegTopics.control(chat.id))
            }
            c.subscribe(filter.toTypedArray(), IntArray(filter.size) { 1 })
        } catch (e: Exception) {
            Log.w(TAG, "Abonnieren fehlgeschlagen: ${e.message}")
        }

        // Neues JWT kurz vor Ablauf (bzw. vor Ablauf des Anlage-Tokens) → neu verbinden
        val bisToken = (zugang.expiresInSek - 120).coerceAtLeast(60)
        val bisKonto = ((k.gueltigBisMs - System.currentTimeMillis() - RosenwegKonto.ERNEUERN_VOR_MS) / 1000)
            .let { if (k.gueltigBisMs <= 0) Long.MAX_VALUE else it.coerceAtLeast(60) }
        erneuerung?.cancel(false)
        erneuerung = arbeiter.schedule({ verbinde() }, minOf(bisToken, bisKonto), TimeUnit.SECONDS)

        holeVerlauf()
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
                        speicher.nimmAuf(chat, d, ausVerlauf = true, gelesen = erstimport)
                        geaendert = true
                    }
                    chat = speicher.chat(chat.id) ?: break
                    if (liste.size < 500) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Verlauf ${chat.id}: ${e.message}")
            }
        }
        if (geaendert) {
            refreshMessages()
            refreshConversations()
        }
    }

    private fun trenne(sauber: Boolean) {
        generation++
        verbunden = false
        erneuerung?.cancel(false)
        val c = client ?: return
        client = null
        try {
            if (sauber && c.isConnected) {
                konto?.let { c.publish(RosenwegTopics.presence(it.userId), bauePresence(false).toByteArray(), 1, true) }
                c.disconnect(2000).waitForCompletion(2500)
            } else {
                c.disconnectForcibly(500, 500)
            }
        } catch (_: Exception) {
        }
        try {
            c.close()
        } catch (_: Exception) {
        }
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
                    // Notfall-UX folgt in einem spaeteren Schritt; hier nur protokollieren.
                    Log.i(TAG, "sys/crisis: $text")
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
                    val e = speicher.nimmAuf(speicher.chat(chat.id) ?: chat, d, ausVerlauf = false, gelesen = false)
                    refreshMessages()
                    refreshConversations()
                    if (e.neu && e.nachricht.senderId != speicher.eigeneId) {
                        benachrichtige(e)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Eingang $topic: ${ex.message}")
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
            bitmap = null,
        )
    }

    // ── Senden (Arbeits-Thread) ─────────────────────────────────────────

    private fun sendeAusstehendeJetzt() {
        val c = client ?: return
        if (!verbunden || !c.isConnected) return
        val ich = konto?.userId ?: return
        for (m in speicher.ausstehende()) {
            val chat = chatsNachId[m.chatId]
            if (chat == null || chat.kind == "dm" || !chat.canWrite || m.body == null) {
                speicher.setzeStatus(m.rowId, RwStatus.FEHLER)
                continue
            }
            val nutzlast = baueAusgehend(ich, m.clientMsgId ?: continue, m.ts, m.body)
            try {
                c.publish(RosenwegTopics.sendeTopic(chat.topicPrefix, ich), nutzlast.toByteArray(Charsets.UTF_8), 1, false,
                    null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            arbeiter.execute {
                                speicher.setzeStatus(m.rowId, RwStatus.GESENDET)
                                refreshMessages()
                            }
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            // bleibt ausstehend; naechste Verbindung versucht es erneut
                            Log.w(TAG, "Senden fehlgeschlagen: ${exception?.message}")
                        }
                    })
            } catch (e: Exception) {
                Log.w(TAG, "Senden: ${e.message}")
            }
        }
    }
}
