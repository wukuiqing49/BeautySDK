package com.beautysdk

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ReusableRgbInputTest {
    @Test fun fusedConversionMatchesReferenceForPaddingScalingAndAllOrientations() {
        val width = 35
        val height = 23
        val stride = width * 4 + 12
        val source = ByteBuffer.allocateDirect(stride * height + 7)
        for (i in 0 until source.capacity()) source.put(i, (i * 31).toByte())
        source.position(7)
        val original = ByteArray(source.remaining()).also { source.duplicate().get(it) }
        val converter = ReusableRgbInput(16)
        for (rotation in listOf(0,90,180,270)) for (mirror in listOf(false,true)) {
            val frame = RgbaFrame(FrameInfo(width,height,1,rotation,mirror),source,stride)
            val expected = AnalysisOrientation.upright(AnalysisImage.copyOf(frame,16)).image
            val actual = converter.prepare(frame)
            assertTrue(actual.isDirect)
            assertEquals(expected.width,converter.width)
            assertEquals(expected.height,converter.height)
            val bytes = ByteArray(actual.remaining()).also { actual.duplicate().get(it) }
            assertArrayEquals(expected.pixels,bytes)
            assertSame(actual,converter.prepare(frame))
            assertEquals(7,source.position())
            assertArrayEquals(original,ByteArray(source.remaining()).also { source.duplicate().get(it) })
        }
        converter.clear()
        assertEquals(0,converter.width)
    }
}
