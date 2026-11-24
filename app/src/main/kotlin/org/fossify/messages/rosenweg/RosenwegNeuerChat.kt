package org.fossify.messages.rosenweg

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.refreshConversations

/** "Neuer Rosenweg-Chat": Direktnachricht an eine Person oder neue Gruppe (wie die PWA). */
object RosenwegNeuerChat {
    private const val TAG = "RosenwegNeuerChat"

    fun zeige(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("Neuer Rosenweg-Chat")
            .setItems(arrayOf("Direktnachricht", "Neue Gruppe")) { _, i ->
                if (i == 0) waehlePerson(a) else neueGruppe(a)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun personen(a: Activity, weiter: (List<RwUser>) -> Unit) {
        ensureBackgroundThread {
            val liste = RosenwegSpeicher.get(a).personenListe()
            a.runOnUiThread {
                if (liste.isEmpty()) a.toast("Verzeichnis noch nicht geladen – bitte gleich nochmals versuchen") else weiter(liste)
            }
        }
    }

    private fun waehlePerson(a: Activity) = personen(a) { liste ->
        AlertDialog.Builder(a)
            .setTitle("Direktnachricht an …")
            .setItems(liste.map { it.name }.toTypedArray()) { _, i -> anlegen(a, dmMit = liste[i].id, titel = null, mitglieder = emptyList()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun neueGruppe(a: Activity) {
        val feld = EditText(a).apply { hint = "Name der Gruppe" }
        AlertDialog.Builder(a)
            .setTitle("Neue Gruppe")
            .setView(feld)
            .setPositiveButton("Weiter") { _, _ ->
                val titel = feld.text.toString().trim()
                if (titel.isEmpty()) {
                    a.toast("Bitte einen Namen eingeben")
                    return@setPositiveButton
                }
                personen(a) { liste ->
                    val gewaehlt = BooleanArray(liste.size)
                    AlertDialog.Builder(a)
                        .setTitle("Mitglieder für „$titel“")
                        .setMultiChoiceItems(liste.map { it.name }.toTypedArray(), gewaehlt) { _, i, an -> gewaehlt[i] = an }
                        .setPositiveButton("Gruppe anlegen") { _, _ ->
                            anlegen(a, null, titel, liste.filterIndexed { i, _ -> gewaehlt[i] }.map { it.id })
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun anlegen(a: Activity, dmMit: String?, titel: String?, mitglieder: List<String>) {
        ensureBackgroundThread {
            try {
                val konto = RosenwegKonto.hole(a) ?: throw IllegalStateException("nicht angemeldet")
                val g = RosenwegApi(konto).neuesGespraech(dmMit, titel, mitglieder)
                val speicher = RosenwegSpeicher.get(a)
                val chat = speicher.nimmGespraechAuf(g)
                MessengerVerbindung.neuVerbinden()
                refreshConversations()
                val name = speicher.titel(chat)
                a.runOnUiThread {
                    a.startActivity(Intent(a, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, chat.threadId)
                        putExtra(THREAD_TITLE, name)
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chat anlegen", e)
                a.toast("Chat konnte nicht angelegt werden")
            }
        }
    }
}
