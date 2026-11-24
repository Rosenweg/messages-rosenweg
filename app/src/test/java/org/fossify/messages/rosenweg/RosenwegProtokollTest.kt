package org.fossify.messages.rosenweg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class RosenwegProtokollTest {

    private fun jwt(nutzlast: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
            enc.encodeToString(nutzlast.toByteArray()) + ".sig"
    }

    @Test
    fun threadIdsSindNegativUndUmkehrbar() {
        val t = RosenwegIds.threadId(7)
        assertEquals(-1_000_000_000_007L, t)
        assertTrue(t.isRosenwegThread())
        assertFalse(12345L.isRosenwegThread())
        assertEquals(7L, RosenwegIds.chatSeq(t))
        assertEquals(-42L, RosenwegIds.nachrichtId(42))
        assertEquals(42L, RosenwegIds.zeile(-42))
    }

    @Test
    fun clientMsgIdWieDiePwa() {
        val id = RosenwegIds.neueClientMsgId(1732440000000, Random(1))
        assertTrue(id, Regex("^c_1732440000000_[0-9a-z]{6}$").matches(id))
    }

    @Test
    fun jwtSubAlsZahlWirdText() {
        val a = leseJwt(jwt("""{"sub":42,"name":"Stefan","exp":1732440000,"pub_topics":["group/g1","presence/42"]}"""))!!
        assertEquals("42", a.sub)
        assertEquals("Stefan", a.name)
        assertEquals(1732440000L, a.exp)
        assertEquals(listOf("group/g1", "presence/42"), a.pubTopics)
        assertEquals("abc", leseJwt(jwt("""{"sub":"abc"}"""))!!.sub)
        assertNull(leseJwt("kaputt"))
    }

    @Test
    fun schreibrechtAusPubTopics() {
        val pub = listOf("presence/1", "group/g1", "dm/dm-x")
        assertTrue(darfSchreiben(pub, "group/g1"))
        assertTrue(darfSchreiben(pub, "dm/dm-x"))
        assertFalse(darfSchreiben(pub, "bcast/notfall"))
        assertFalse(darfSchreiben(pub, "group/g10"))
        assertTrue(darfSchreiben(listOf("bcast/notfall/msg/1"), "bcast/notfall"))
    }

    @Test
    fun absenderKommtAusDemTopic() {
        val d = parseLiveNachricht(
            "group/g1/msg/17",
            """{"senderId":"99","clientMsgId":"c_1_abcdef","ts":1732440000000,"body":"Hallo"}"""
        )!!
        assertEquals("17", d.senderId)
        assertEquals("c_1_abcdef", d.clientMsgId)
        assertEquals(1732440000000L, d.ts)
        assertEquals("Hallo", d.body)
        assertEquals(RW_SYSTEM_ABSENDER, parseLiveNachricht("bcast/notfall/msg", """{"body":"x"}""")!!.senderId)
        assertEquals("group/g1" to "g1", RosenwegTopics.nachrichtTopic("group/g1/msg/17"))
        assertNull(RosenwegTopics.nachrichtTopic("control/g1"))
        assertEquals("group/g1/msg/5", RosenwegTopics.sendeTopic("group/g1", "5"))
        assertEquals("group/g1/msg/#", RosenwegTopics.empfangsFilter("group/g1"))
    }

    @Test
    fun dmMitCiphertext() {
        val d = parseLiveNachricht("dm/dm-1/msg/3", """{"clientMsgId":"c","ts":1,"ciphertext":{"v":1,"alg":"ecdh-p256-aesgcm-v1"}}""")!!
        assertNull(d.body)
        assertNotNull(d.ciphertext)
        assertEquals(RW_TEXT_VERSCHLUESSELT, anzeigeText(d.body, d.ciphertext, false, null, null, null, emptyMap()))
    }

    @Test
    fun ausgehendRundreise() {
        val text = baueAusgehend("5", "c_1_aaaaaa", 1732440000000, "Grüezi \"du\"")
        val d = parseLiveNachricht("group/g1/msg/5", text)!!
        assertEquals("Grüezi \"du\"", d.body)
        assertEquals("c_1_aaaaaa", d.clientMsgId)
        assertEquals(1732440000000L, d.ts)
    }

    @Test
    fun verlaufMitTextIdsUndIsoZeit() {
        val json = """{"convId":"g1","messages":[
            {"id":"12","conv_id":"g1","sender_id":"4","kind":"group","body":"a","ciphertext":null,
             "client_msg_id":"c_1_x","created_at":"2024-11-24T09:20:00.000Z","reply_to":{"preview":"vorher"},
             "edited_at":null,"deleted_at":null,"media":null,"reactions":[{"emoji":"👍","users":["1",2]}]},
            {"id":13,"sender_id":5,"body":null,"deleted":true,"created_at":"2024-11-24T09:21:00Z","reactions":[]}
        ]}"""
        val l = parseVerlauf(json)
        assertEquals(2, l.size)
        assertEquals(12L, l[0].serverId)
        assertEquals(1732440000000L, l[0].ts)
        assertEquals("vorher", l[0].replyPreview)
        assertEquals(listOf("1", "2"), l[0].reactions["👍"])
        assertEquals("5", l[1].senderId)
        assertTrue(l[1].deleted)
    }

    @Test
    fun steuerungUndReaktionen() {
        val r = parseSteuerung("""{"type":"reaction","clientMsgId":"c","userId":7,"emoji":"👍","op":"add"}""")
        assertEquals(RwSteuerung.Reaktion("c", "7", "👍", false), r)
        assertEquals(RwSteuerung.Loeschen("c"), parseSteuerung("""{"type":"delete","clientMsgId":"c","by":"1"}"""))
        assertEquals(RwSteuerung.Bearbeiten("c", "neu", 1732440000123), parseSteuerung("""{"type":"edit","clientMsgId":"c","body":"neu","editedAt":1732440000123}"""))
        assertTrue(parseSteuerung("""{"type":"group","action":"members"}""") is RwSteuerung.Gruppe)

        var map = wendeReaktionAn(emptyMap(), "1", "👍", false)
        map = wendeReaktionAn(map, "2", "👍", false)
        map = wendeReaktionAn(map, "2", "👍", false)
        assertEquals(listOf("1", "2"), map["👍"])
        assertEquals(map, reaktionenAusJson(reaktionenZuJson(map)))
        assertEquals("x · 👍2", anzeigeText("x", null, false, null, null, null, map))
        map = wendeReaktionAn(map, "1", "👍", true)
        map = wendeReaktionAn(map, "2", "👍", true)
        assertTrue(map.isEmpty())
        assertEquals(RW_TEXT_GELOESCHT, anzeigeText("x", null, true, null, null, null, map))
    }

    @Test
    fun gespraecheUndVerzeichnis() {
        val g = parseGespraeche("""{"conversations":[{"id":"dm-ab","kind":"dm","title":null,"topic_prefix":"dm/dm-ab",
            "metadata":{"participants":[1,"2"],"avatar":"data:xyz"},"role":"member"}]}""")
        assertEquals(listOf("1", "2"), g[0].participants)
        assertNull(g[0].title)
        assertFalse(g[0].metadata!!.contains("avatar"))
        assertEquals(mapOf("3" to "Anna", "4" to "b@x.ch"), parseVerzeichnis("""{"users":[{"id":3,"name":"Anna"},{"id":"4","email":"b@x.ch"}]}"""))
        assertEquals(listOf("1", "9"), parseMitglieder("""{"members":[{"userId":"1","role":"admin"},{"userId":9}]}"""))
    }

    @Test
    fun zeitFormate() {
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive(1732440000)))
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive("2024-11-24T10:20:00+01:00")))
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive("2024-11-24 09:20:00+00")))
    }
}
