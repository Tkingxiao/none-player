package com.noneplayer.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AviDurationTest {
    @Test
    fun avihMicrosecondsTimesFrames() {
        val bytes = ByteArray(64)
        "avih".encodeToByteArray().copyInto(bytes, 12)
        writeU32(bytes, 16, 56)
        writeU32(bytes, 20, 66_666) // us/frame
        writeU32(bytes, 36, 45) // total frames
        assertEquals(3_000L, AviDuration.fromPrefix(bytes))
    }

    @Test
    fun strhWhenAvihHasNoFrameCount() {
        val bytes = ByteArray(80)
        "strh".encodeToByteArray().copyInto(bytes, 8)
        writeU32(bytes, 12, 48)
        writeU32(bytes, 8 + 8 + 20, 1) // scale
        writeU32(bytes, 8 + 8 + 24, 15) // rate
        writeU32(bytes, 8 + 8 + 32, 61) // length
        assertEquals(4_066L, AviDuration.fromPrefix(bytes))
    }

    private fun writeU32(bytes: ByteArray, index: Int, value: Long) {
        bytes[index] = value.toByte()
        bytes[index + 1] = (value shr 8).toByte()
        bytes[index + 2] = (value shr 16).toByte()
        bytes[index + 3] = (value shr 24).toByte()
    }
}
