package org.fossify.messages.rosenweg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RwZurueckhaltenTest {
    private var jetzt = 1_000_000L
    private var stand = RwZurueckhalten.Stand()
    private fun neu(zufall: Random = Random(1)) = RwZurueckhalten({ stand }, { stand = it }, { jetzt }, zufall)

    @Test
    fun wartezeitWaechstVon30sBis30min() {
        val mitte = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.5   // Faktor genau 1.0
        }
        assertEquals(30_000L, RwZurueckhalten.wartezeitMs(0, mitte))
        assertEquals(60_000L, RwZurueckhalten.wartezeitMs(1, mitte))
        assertEquals(120_000L, RwZurueckhalten.wartezeitMs(2, mitte))
        assertEquals(30 * 60_000L, RwZurueckhalten.wartezeitMs(6, mitte))
        assertEquals(30 * 60_000L, RwZurueckhalten.wartezeitMs(500, mitte))
    }

    @Test
    fun zufallBleibtInnerhalbPlusMinus20Prozent() {
        val r = Random(42)
        repeat(1000) {
            val w = RwZurueckhalten.wartezeitMs(0, r)
            assertTrue(w in 24_000L..36_000L)
        }
        repeat(1000) {
            assertTrue(RwZurueckhalten.wartezeitMs(20, r) <= 36 * 60_000L)
        }
    }

    @Test
    fun nieImSekundentakt() {
        val z = neu()
        assertTrue(z.darfJetzt())
        z.fehler(RwZurueckhalten.Art.SERVER)
        jetzt += 5_000
        assertFalse(z.darfJetzt())
        assertTrue(z.wartetNachServerfehler())
        // Netz wieder da hebt eine Wartezeit nach Serverfehler NICHT auf
        z.netzWiederDa()
        assertFalse(z.darfJetzt())
        jetzt += 40_000
        assertTrue(z.darfJetzt())
    }

    @Test
    fun netzfehlerDarfBeiNetzSofort() {
        val z = neu()
        z.fehler(RwZurueckhalten.Art.NETZ)
        assertFalse(z.darfJetzt())
        z.netzWiederDa()
        assertTrue(z.darfJetzt())
    }

    @Test
    fun erfolgSetztZurueck() {
        val z = neu()
        repeat(5) { z.fehler(RwZurueckhalten.Art.SERVER) }
        assertEquals(5, stand.fehler)
        z.erfolg()
        assertEquals(RwZurueckhalten.Stand(), stand)
    }

    @Test
    fun abgelehntNur401Und403() {
        assertTrue(RwZurueckhalten.istAbgelehnt(401))
        assertTrue(RwZurueckhalten.istAbgelehnt(403))
        for (c in listOf(0, 400, 404, 429, 500, 502, 503)) assertFalse(RwZurueckhalten.istAbgelehnt(c))
    }
}
