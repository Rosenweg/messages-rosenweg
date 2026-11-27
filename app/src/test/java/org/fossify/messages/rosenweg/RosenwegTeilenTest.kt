package org.fossify.messages.rosenweg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RosenwegTeilenTest {
    @Test
    fun nurEchteBilderVerkleinern() {
        assertTrue(RosenwegBild.verkleinern("image/jpeg"))
        assertTrue(RosenwegBild.verkleinern("image/png"))
        assertTrue(RosenwegBild.verkleinern("image/heic"))
        assertFalse(RosenwegBild.verkleinern("image/gif"))
        assertFalse(RosenwegBild.verkleinern("image/svg+xml"))
        assertFalse(RosenwegBild.verkleinern("application/pdf"))
        assertFalse(RosenwegBild.verkleinern("video/mp4"))
    }

    @Test
    fun zielgroesseLangeSeiteBegrenzt() {
        assertEquals(2048 to 1536, RosenwegBild.ziel(4000, 3000))
        assertEquals(1536 to 2048, RosenwegBild.ziel(3000, 4000))
        assertEquals(800 to 600, RosenwegBild.ziel(800, 600))
        assertEquals(2048 to 1, RosenwegBild.ziel(100_000, 10))
    }

    @Test
    fun teilerBleibtUeberDerZielgroesse() {
        assertEquals(1, RosenwegBild.teiler(2048, 1536))
        assertEquals(1, RosenwegBild.teiler(4000, 3000))
        assertEquals(2, RosenwegBild.teiler(4096, 3072))
        assertEquals(4, RosenwegBild.teiler(9000, 6000))
    }

    /** Teilen bleibt auch privat an: der Eingang darf nie bei den SMS-Bausteinen stehen. */
    @Test
    fun teilenIstKeinSmsBaustein() {
        assertFalse(RosenwegSmsModus.SMS_BAUSTEINE.any { it.endsWith("RosenwegTeilenActivity") })
    }
}
