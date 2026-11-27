package org.fossify.messages.rosenweg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RosenwegSmsModusTest {
    @Test
    fun modusAusGeraetUndRolle() {
        assertEquals(RosenwegSmsModus.Modus.GETEILT, RosenwegSmsModus.modusAus(geraetDa = true))
        // privat immer nur Messenger, auch wenn die App noch Standard-SMS-App ist (Stefan 26.11.2026)
        assertEquals(RosenwegSmsModus.Modus.PRIVAT, RosenwegSmsModus.modusAus(geraetDa = false))
    }

    @Test
    fun smsBausteineNurPrivatAus() {
        assertTrue(RosenwegSmsModus.smsBausteineAn(RosenwegSmsModus.Modus.GETEILT))
        assertFalse(RosenwegSmsModus.smsBausteineAn(RosenwegSmsModus.Modus.PRIVAT))
    }

    /** Hinweis "gewohnte SMS-App wieder einstellen" nur privat und nur, solange die Rolle bei uns ist. */
    @Test
    fun rolleHinweisNurPrivatMitRolle() {
        assertTrue(RosenwegSmsModus.rolleHinweisNoetig(RosenwegSmsModus.Modus.PRIVAT, standardSmsApp = true))
        assertFalse(RosenwegSmsModus.rolleHinweisNoetig(RosenwegSmsModus.Modus.PRIVAT, standardSmsApp = false))
        assertFalse(RosenwegSmsModus.rolleHinweisNoetig(RosenwegSmsModus.Modus.GETEILT, standardSmsApp = true))
        assertFalse(RosenwegSmsModus.rolleHinweisNoetig(RosenwegSmsModus.Modus.GETEILT, standardSmsApp = false))
    }

    private val manifest: List<Element> by lazy {
        val f = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(f)
        val app = doc.getElementsByTagName("application").item(0) as Element
        (0 until app.childNodes.length).mapNotNull { app.childNodes.item(it) as? Element }
    }

    private fun klasse(name: String) = if (name.startsWith(".")) "org.fossify.messages$name" else name

    private fun aktionen(e: Element): Set<String> {
        val a = e.getElementsByTagName("action")
        return (0 until a.length).map { (a.item(it) as Element).getAttribute("android:name") }.toSet()
    }

    @Test
    fun alleBausteineStehenImManifest() {
        val namen = manifest.map { klasse(it.getAttribute("android:name")) }.toSet()
        RosenwegSmsModus.SMS_BAUSTEINE.forEach { assertTrue("$it fehlt im Manifest", it in namen) }
    }

    /** Alles, was Android fuer die Rolle "Standard-SMS-App" verlangt, muss privat ausschaltbar sein. */
    @Test
    fun smsRolleBausteineSindAusschaltbar() {
        val rolle = setOf(
            "android.provider.Telephony.SMS_DELIVER",
            "android.provider.Telephony.WAP_PUSH_DELIVER",
            "android.intent.action.RESPOND_VIA_MESSAGE",
            "android.intent.action.SENDTO",
        )
        val halter = manifest.filter { e -> aktionen(e).any { it in rolle } }
            .map { klasse(it.getAttribute("android:name")) }
        assertTrue(halter.isNotEmpty())
        halter.forEach { assertTrue("$it haelt SMS-Rolle, ist aber nicht in SMS_BAUSTEINE", it in RosenwegSmsModus.SMS_BAUSTEINE) }
    }

    /** Der Modus-Empfaenger selbst darf nie in der Liste stehen (sonst kein Rueckweg). */
    @Test
    fun modusEmpfaengerBleibtAn() {
        assertFalse(RosenwegSmsModus.SMS_BAUSTEINE.any { it.endsWith("RosenwegSmsModusReceiver") })
    }
}
