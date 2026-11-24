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
        val a = anzeigeText("x", null, false, null, "Anna: Hallo", null, map)
        assertEquals("👍 2", zerlegeAnzeige(a).reaktionen)
        assertEquals("x", zerlegeAnzeige(a).text)
        assertEquals("Anna", zerlegeAnzeige(a).zitatName)
        assertEquals("Hallo", zerlegeAnzeige(a).zitatText)
        assertEquals("x", ausschnitt(a))
        assertEquals("👍 ❤️ 3", reaktionsChip(mapOf("❤️" to listOf("1"), "👍" to listOf("1", "2"))))
        assertEquals("👍", reaktionsChip(mapOf("👍" to listOf("1"))))
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
        assertEquals(
            mapOf("3" to RwPerson("Anna", "data:image/png;base64,AA=="), "4" to RwPerson("b@x.ch", null)),
            parseVerzeichnis("""{"users":[{"id":3,"name":"Anna","avatar":"data:image/png;base64,AA=="},{"id":"4","email":"b@x.ch","avatar":null}]}""")
        )
        val mitLogo = parseGespraeche("""{"conversations":[{"id":"technik","kind":"group","title":"Technik","topic_prefix":"group/technik","metadata":{"avatar":"data:image/png;base64,AA=="}}]}""")
        assertEquals("data:image/png;base64,AA==", mitLogo[0].avatar)
        assertEquals(listOf("1", "9"), parseMitglieder("""{"members":[{"userId":"1","role":"admin"},{"userId":9}]}"""))
    }

    @Test
    fun zeitFormate() {
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive(1732440000)))
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive("2024-11-24T10:20:00+01:00")))
        assertEquals(1732440000000L, parseZeit(kotlinx.serialization.json.JsonPrimitive("2024-11-24 09:20:00+00")))
    }

    @Test
    fun quittungen() {
        val q = RwQuittung.parse("receipt/g1/42", """{"msgId":"c_1_abcdef","state":"read","ts":1}""")
        assertEquals(RwQuittung.Eingang("g1", "42", "c_1_abcdef", RwQuittung.GELESEN), q)
        assertEquals(RwQuittung.ZUGESTELLT, RwQuittung.parse("receipt/g1/7", """{"msgId":"c","state":"delivered"}""")!!.stufe)
        assertNull(RwQuittung.parse("receipt/g1/7", """{"msgId":"c","state":"typing"}"""))
        assertEquals("receipt/g1/5", RwQuittung.topic("g1", "5"))
        val n = parseJson(RwQuittung.nutzlast("c_1", RwQuittung.GELESEN, 9)).toString()
        assertEquals("""{"msgId":"c_1","state":"read","ts":9}""", n)

        assertEquals(RwQuittung.HAKEN_GESPEICHERT, RwQuittung.haken(0, 0, 0))
        assertEquals(RwQuittung.HAKEN_GESPEICHERT, RwQuittung.haken(5, 5, 0))
        assertEquals(RwQuittung.HAKEN_GESPEICHERT, RwQuittung.haken(1, 0, 2))
        assertEquals(RwQuittung.HAKEN_ZUGESTELLT, RwQuittung.haken(2, 1, 2))
        assertEquals(RwQuittung.HAKEN_GELESEN, RwQuittung.haken(2, 2, 2))
        assertEquals(RwQuittung.HAKEN_GELESEN, RwQuittung.haken(1, 1, 1))

        val v = parseVerlauf("""{"messages":[{"id":1,"sender_id":"5","body":"x","client_msg_id":"c","created_at":1732440000000,
            "receipt":{"delivered":3,"read":1,"of":4}}]}""")
        assertEquals(RwQuittungZahlen(3, 1, 4), v[0].quittung)
    }

    @Test
    fun jwtAvatar() {
        assertEquals("https://x/y.png", leseJwt(jwt("""{"sub":"1","avatar_url":"https://x/y.png"}"""))!!.avatar)
    }
}
