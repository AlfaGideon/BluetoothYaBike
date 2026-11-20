package ru.alfagideon.bluetoothyabike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTest {

    @Test
    fun parsesSpacesAndCase() {
        assertArrayEquals(byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte()), Hex.parse("01 ab FF"))
    }

    @Test
    fun parsesNoSeparatorsAndPrefixes() {
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), Hex.parse("0A0B"))
        assertArrayEquals(byteArrayOf(0x01, 0x02), Hex.parse("0x01 0x02"))
    }

    @Test
    fun rejectsOddLength() {
        assertNull(Hex.parse("0 1 2"))
    }

    @Test
    fun rejectsNonHex() {
        assertNull(Hex.parse("Z1 22"))
    }

    @Test
    fun emptyIsEmptyArray() {
        assertArrayEquals(ByteArray(0), Hex.parse("  "))
    }

    @Test
    fun formatsWithSpaces() {
        assertEquals("01 0A FF", Hex.toHex(byteArrayOf(0x01, 0x0A, 0xFF.toByte())))
    }

    @Test
    fun asciiDetection() {
        assertTrue(Hex.looksLikeAscii("HELLO".toByteArray()))
        assertTrue(!Hex.looksLikeAscii(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun prettyAddsAsciiForText() {
        assertTrue(Hex.pretty("OK".toByteArray()).endsWith("|  OK"))
    }
}
