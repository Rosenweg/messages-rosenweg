package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.Telephony
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.fadeIn
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_EVENT_BUS
import org.fossify.commons.helpers.LICENSE_INDICATOR_FAST_SCROLL
import org.fossify.commons.helpers.LICENSE_SMS_MMS
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.PERMISSION_SEND_SMS
import org.fossify.commons.helpers.SHORT_ANIMATION_DURATION
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.Release
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.adapters.ConversationsAdapter
import org.fossify.messages.adapters.SearchResultsAdapter
import org.fossify.messages.databinding.ActivityMainBinding
import org.fossify.messages.extensions.checkAndDeleteOldRecycleBinMessages
import org.fossify.messages.extensions.clearAllMessagesIfNeeded
import org.fossify.messages.extensions.clearExpiredScheduledMessages
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.SEARCHED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.models.Message
import org.fossify.messages.models.SearchResult
import org.fossify.messages.rosenweg.MessengerVerbindung
import org.fossify.messages.rosenweg.RosenwegPrivatAnmeldung
import org.fossify.messages.rosenweg.RosenwegSmsModus
import org.fossify.messages.rosenweg.isRosenwegThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    private val MAKE_DEFAULT_APP_REQUEST = 1

    // Rosenweg: privat (ohne Rosenweg Geraet) nur Messenger, keine SMS (RosenwegSmsModus)
    private var smsModus = RosenwegSmsModus.Modus.GETEILT
    private var abgelehntGezeigt = false
    private val nurMessenger get() = smsModus == RosenwegSmsModus.Modus.PRIVAT
    private var uebergangHinweisGezeigt = false

    private var storedTextColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null

    private val binding by viewBinding(ActivityMainBinding::inflate)

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.conversationsList))

        smsModus = RosenwegSmsModus.abgleichen(this)
        // Papierkorb leeren loescht SMS im Android-Speicher -> privat nie
        if (!nurMessenger) checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }

        if (checkAppSideloading()) {
            return
        }
    }

    override fun onResume() {
        super.onResume()
        // Rosenweg Messenger: Verbindung starten, falls die Geraete-App einen Zugang liefert
        if (!MessengerVerbindung.istAktiv()) {
            MessengerVerbindung.starteWennKonto(this)
        }
        // eigenes Handy (immer nur Messenger), nicht angemeldet: gemeinsamer Anmeldeschirm
        // (commons-rosenweg) - ohne Anmeldung ist die App nicht nutzbar, "Zurueck" schliesst sie
        if (RosenwegPrivatAnmeldung.schirmNoetig(this)) {
            RosenwegPrivatAnmeldung.starten(this)
            return
        }
        // eigenes Handy: zurueck von der Anmeldung (Karte oder Rosenweg-Konto) -> Zugang einrichten
        richteEinWennAusstehend()
        zeigeAbgelehntHinweis()
        // z.B. zurueck aus "Standard-Apps" (Hinweis weg) oder Rosenweg Geraet inzwischen da/weg
        val vorher = smsModus
        smsModus = RosenwegSmsModus.abgleichen(this)
        if (smsModus != vorher && !nurMessenger) {
            // privat -> geteilt (Rosenweg Geraet neu da): SMS-Weg (Rolle, Berechtigungen) neu aufbauen
            recreate()
            return
        }
        if (smsModus != vorher) initMessenger()
        updateMenuColors()
        refreshMenuItems()

        getOrCreateConversationsAdapter().apply {
            if (storedTextColor != getProperTextColor()) {
                updateTextColor(getProperTextColor())
            }

            if (storedFontSize != config.fontSize) {
                updateFontSize()
            }

            updateDrafts()
        }

        updateTextColors(binding.mainCoordinator)
        binding.searchHolder.setBackgroundColor(getProperBackgroundColor())

        val properPrimaryColor = getProperPrimaryColor()
        binding.noConversationsPlaceholder2.setTextColor(properPrimaryColor)
        binding.noConversationsPlaceholder2.underlineText()
        binding.conversationsFastscroller.updateColors(properPrimaryColor)
        binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu_main)
        binding.mainMenu.toggleHideOnScroll(true)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchClosedListener = {
            fadeOutSearch()
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            if (text.isNotEmpty()) {
                if (binding.searchHolder.alpha < 1f) {
                    binding.searchHolder.fadeIn()
                }
            } else {
                fadeOutSearch()
            }
            searchTextChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.rw_neuer_chat -> org.fossify.messages.rosenweg.RosenwegNeuerChat.zeige(this)
                R.id.rw_standard_sms -> zeigeUebergangHinweis(erzwingen = true)
                R.id.rw_anmelden -> RosenwegPrivatAnmeldung.starten(this)
                R.id.rw_abmelden -> frageAbmelden()
                R.id.show_recycle_bin -> launchRecycleBin()
                R.id.show_archived -> launchArchivedConversations()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.rw_neuer_chat).isVisible = MessengerVerbindung.istAktiv() ||
                org.fossify.messages.rosenweg.RosenwegKonto.eigeneIdGespeichert(this@MainActivity) != null
            // privat: Papierkorb gibt es nur fuer SMS (Rosenweg-Chats werden sofort geloescht)
            findItem(R.id.show_recycle_bin).isVisible = config.useRecycleBin && !nurMessenger
            // privat, aber noch Standard-SMS-App (aeltere Version): Weg zu "Standard-Apps"
            findItem(R.id.rw_standard_sms).isVisible = nurMessenger && RosenwegSmsModus.istStandardSmsApp(this@MainActivity)
            // eigenes Handy: selbst anmelden / abmelden (geteilte Telefone: ueber Rosenweg Geraet)
            val privat = smsModus != RosenwegSmsModus.Modus.GETEILT
            findItem(R.id.rw_anmelden).isVisible = privat && !RosenwegPrivatAnmeldung.angemeldet(this@MainActivity)
            findItem(R.id.rw_abmelden).isVisible = privat && RosenwegPrivatAnmeldung.hatZugang(this@MainActivity)
            findItem(R.id.show_archived).isVisible = config.isArchiveAvailable
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            if (resultCode == RESULT_OK) {
                askPermissions()
            } else {
                finish()
            }
        }
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedFontSize = config.fontSize
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun loadMessages() {
        when (smsModus) {
            RosenwegSmsModus.Modus.PRIVAT -> {
                // privat: keine SMS-Rolle, keine SMS-Berechtigungen - nur Benachrichtigungen
                if (RosenwegSmsModus.istStandardSmsApp(this)) zeigeUebergangHinweis()
                starteNurMessenger()
                return
            }
            RosenwegSmsModus.Modus.GETEILT -> {}
        }
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
                }
            } else {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional.
    // If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            handleNotificationPermission { granted ->
                                if (!granted) {
                                    PermissionRequiredDialog(
                                        activity = this,
                                        textId = org.fossify.commons.R.string.allow_notifications_incoming_messages,
                                        positiveActionCallback = { openNotificationSettings() })
                                }
                            }

                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun starteNurMessenger() {
        handleNotificationPermission { granted ->
            if (!granted) {
                PermissionRequiredDialog(
                    activity = this,
                    textId = org.fossify.commons.R.string.allow_notifications_incoming_messages,
                    positiveActionCallback = { openNotificationSettings() })
            }
        }
        initMessenger()
        if (bus == null) {
            bus = EventBus.getDefault()
            try {
                bus!!.register(this)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Privates Telefon, aber diese App ist (aus einer frueheren Version) noch Standard-SMS-App.
     * Android laesst eine App die Rolle nicht selbst abgeben; die Person waehlt ihre SMS-App unter
     * "Standard-Apps". Die SMS-Bausteine sind trotzdem schon aus (RosenwegSmsModus), SMS nimmt
     * diese App hier also nicht mehr an - darum der Hinweis (dazu eine Benachrichtigung).
     */
    private fun zeigeUebergangHinweis(erzwingen: Boolean = false) {
        if ((uebergangHinweisGezeigt && !erzwingen) || isFinishing) return
        uebergangHinweisGezeigt = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(RosenwegSmsModus.ROLLE_TITEL)
            .setMessage(RosenwegSmsModus.ROLLE_TEXT)
            .setPositiveButton("Standard-Apps öffnen") { _, _ -> oeffneStandardApps() }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun oeffneStandardApps() {
        val versuche = listOf(
            RosenwegSmsModus.standardAppsEinstellung(),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in versuche) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        toast("Bitte in den Einstellungen unter Apps › Standard-Apps › SMS-App umstellen")
    }

    private fun initMessenger() {
        checkWhatsNewDialog()
        storeStateVariables()
        if (nurMessenger) {
            zeigeNurRosenwegChats()
        } else {
            getCachedConversations()
        }
        binding.noConversationsPlaceholder2.setOnClickListener {
            launchNewConversation()
        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
    }

    /** privat: nur die Rosenweg-Chats (negative Thread-IDs) aus der eigenen DB, nie der SMS-Speicher */
    private fun zeigeNurRosenwegChats() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getNonArchived().filter { it.threadId.isRosenwegThread() }
                    .toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }
            runOnUiThread { setupConversations(conversations) }
        }
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }

            val archived = try {
                conversationsDB.getAllArchived()
            } catch (_: Exception) {
                listOf()
            }

            runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations(
                    (conversations + archived).toMutableList() as ArrayList<Conversation>
                )
            }
            conversations.forEach {
                clearExpiredScheduledMessages(it.threadId)
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)

            conversations.forEach { clonedConversation ->
                val threadIds = cachedConversations.map { it.threadId }
                if (!threadIds.contains(clonedConversation.threadId)) {
                    conversationsDB.insertOrUpdate(clonedConversation)
                    cachedConversations.add(clonedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                // Rosenweg-Chats (negative Thread-ID) stehen nie im Telephony-Provider
                if (isConversationDeleted && !isTemporaryThread && !threadId.isRosenwegThread()) {
                    conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    // delete the original temporary thread and move any scheduled messages
                    // to the new thread
                    conversationsDB.deleteThreadId(threadId)
                    messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            messagesDB.insertOrUpdate(
                                message.copy(threadId = newConversation.threadId)
                            )
                        }
                    insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv, new = it
                    )
                }
                if (conv != null) {
                    // FIXME: Scheduled message date is being reset here. Conversations with
                    //  scheduled messages will have their original date.
                    insertOrUpdateConversation(conv)
                }
            }

            val allConversations = conversationsDB.getNonArchived() as ArrayList<Conversation>
            runOnUiThread {
                setupConversations(allConversations)
            }

            if (config.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = getMessages(threadId, includeScheduledMessages = false)
                    messages.chunked(30).forEach { currentMessages ->
                        messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter {
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun setupConversations(
        conversations: ArrayList<Conversation>,
        cached: Boolean = false,
    ) {
        val sortedConversations = conversations
            .sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }.thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>

        if (cached && config.appRunCount == 1 && !nurMessenger) {
            // there are no cached conversations on the first run so we show the
            // loading placeholder and progress until we are done loading from telephony
            showOrHideProgress(conversations.isEmpty())
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
            binding.noConversationsPlaceholder.beVisible()
            binding.noConversationsPlaceholder.text = getString(R.string.loading_messages)
        } else {
            binding.conversationsProgressBar.hide()
            binding.noConversationsPlaceholder.beGone()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        if (nurMessenger) {
            val angemeldet = rosenwegAngemeldet()
            val einrichten = RosenwegPrivatAnmeldung.richtetGeradeEin()
            binding.noConversationsPlaceholder.text = when {
                angemeldet -> "Noch keine Rosenweg-Chats"
                einrichten -> "Rosenweg Messenger wird eingerichtet …"
                else -> "Melde dich an (Rosenweg-Karte oder Rosenweg-Konto), dann siehst du hier deine Rosenweg-Chats."
            }
            // nicht angemeldet: der Knopf unter dem Text oeffnet den Anmeldeschirm (launchNewConversation)
            binding.noConversationsPlaceholder2.text = if (angemeldet) "Neuen Rosenweg-Chat beginnen" else "Anmelden"
            binding.noConversationsPlaceholder2.beVisibleIf(show && !einrichten)
            binding.conversationsFab.beVisibleIf(angemeldet)
            return
        }
        binding.noConversationsPlaceholder.text = getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun rosenwegAngemeldet() = if (smsModus != RosenwegSmsModus.Modus.GETEILT) {
        RosenwegPrivatAnmeldung.angemeldet(this)
    } else {
        MessengerVerbindung.istAktiv() || org.fossify.messages.rosenweg.RosenwegKonto.eigeneIdGespeichert(this) != null
    }

    /** Eigenes Handy: nach der Anmeldung im Browser den Messenger-Zugang einrichten. */
    private fun richteEinWennAusstehend() {
        if (smsModus == RosenwegSmsModus.Modus.GETEILT || !RosenwegPrivatAnmeldung.ausstehend(this) ||
            RosenwegPrivatAnmeldung.richtetGeradeEin()
        ) return
        ensureBackgroundThread {
            runOnUiThread { if (nurMessenger) showOrHidePlaceholder(true) }
            val meldung = RosenwegPrivatAnmeldung.einrichtenWennAusstehend(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                meldung?.let { toast(it, android.widget.Toast.LENGTH_LONG) }
                refreshMenuItems()
                if (nurMessenger) zeigeNurRosenwegChats()
                if (RosenwegPrivatAnmeldung.angemeldet(this)) MessengerVerbindung.starteWennKonto(this)
            }
        }
    }

    /**
     * Eigenes Handy, Anmeldung abgelehnt (401/403): der Messenger fragt nicht mehr nach. Einmal je
     * Bildschirm sagen, warum, und neu anmelden anbieten (die Chats bleiben).
     */
    private fun zeigeAbgelehntHinweis() {
        if (abgelehntGezeigt || isFinishing || smsModus == RosenwegSmsModus.Modus.GETEILT ||
            !RosenwegPrivatAnmeldung.abgelehnt(this) || RosenwegPrivatAnmeldung.ausstehend(this)
        ) return
        abgelehntGezeigt = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bitte neu anmelden")
            .setMessage(
                "Der Rosenweg hat die Anmeldung des Messengers abgelehnt. Er empfängt und sendet darum " +
                    "nichts mehr. Melde dich neu an – deine Rosenweg-Chats bleiben erhalten."
            )
            .setPositiveButton("Neu anmelden") { _, _ -> RosenwegPrivatAnmeldung.starten(this) }
            .setNegativeButton("Später", null)
            .show()
    }

    /** Eigenes Handy: abmelden = Zugang an der Website loeschen und alle Rosenweg-Chats hier loeschen. */
    private fun frageAbmelden() {
        val wer = RosenwegPrivatAnmeldung.name(this)?.let { " ($it)" } ?: ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Vom Rosenweg Messenger abmelden?")
            .setMessage(
                "Dein Rosenweg-Konto$wer wird auf diesem Handy abgemeldet. Die Rosenweg-Chats " +
                    "auf diesem Handy werden gelöscht; auf deinen anderen Geräten und im Portal bleiben sie. " +
                    "Neu anmelden geht jederzeit."
            )
            .setPositiveButton("Abmelden") { _, _ ->
                ensureBackgroundThread {
                    RosenwegPrivatAnmeldung.abmelden(applicationContext)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        toast("Abgemeldet")
                        recreate()
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        Intent(this, ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            startActivity(this)
        }
    }

    private fun launchNewConversation() {
        hideKeyboard()
        // privat: neuer Chat = Rosenweg-Chat (die SMS-Kontaktauswahl ist dort aus)
        if (nurMessenger) {
            if (rosenwegAngemeldet()) {
                org.fossify.messages.rosenweg.RosenwegNeuerChat.zeige(this)
            } else {
                RosenwegPrivatAnmeldung.starten(this)
            }
            return
        }
        Intent(this, NewConversationActivity::class.java).apply {
            startActivity(this)
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcut() {
        if (nurMessenger) {
            // Verknuepfung "Neue Unterhaltung" fuehrt zur SMS-Kontaktauswahl (privat aus)
            try {
                getSystemService(ShortcutManager::class.java)?.removeAllDynamicShortcuts()
            } catch (_: Exception) {
            }
            config.lastHandledShortcutColor = -1
            return
        }
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor != appIconColor) {
            val newConversation = getCreateNewContactShortcut(appIconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = listOf(newConversation)
                config.lastHandledShortcutColor = appIconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(this, org.fossify.commons.R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(
            org.fossify.commons.R.id.shortcut_plus_background
        ).applyColorFilter(appIconColor)

        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        if (!binding.mainMenu.isSearchOpen && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.length >= 2)
        if (text.length >= 2) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                var messages = messagesDB.getMessagesWithText(searchQuery)
                var conversations = conversationsDB.getConversationsWithText(searchQuery)
                if (nurMessenger) {
                    // privat: SMS aus frueheren Versionen im Zwischenspeicher nicht zeigen
                    messages = messages.filter { it.threadId.isRosenwegThread() }
                    conversations = conversations.filter { it.threadId.isRosenwegThread() }
                }
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val searchResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val date = (conversation.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                snippet = conversation.phoneNumber,
                date = date,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri
            )
            searchResults.add(searchResult)
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val date = (message.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                snippet = message.body,
                date = date,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri
            )
            searchResults.add(searchResult)
        }

        runOnUiThread {
            binding.searchResultsList.beVisibleIf(searchResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(searchResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchResults, binding.searchResultsList, searchedText) {
                    hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchResults, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        hideKeyboard()
        startActivity(Intent(applicationContext, RecycleBinConversationsActivity::class.java))
    }

    private fun launchArchivedConversations() {
        hideKeyboard()
        startActivity(Intent(applicationContext, ArchivedConversationsActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or LICENSE_SMS_MMS or LICENSE_INDICATOR_FAST_SCROLL

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.faq_2_title,
                text = R.string.faq_2_text
            ),
            FAQItem(
                title = R.string.faq_3_title,
                text = R.string.faq_3_text
            ),
            FAQItem(
                title = R.string.faq_4_title,
                text = R.string.faq_4_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (resources.getBoolean(org.fossify.commons.R.bool.is_google_play_build)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        initMessenger()
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
