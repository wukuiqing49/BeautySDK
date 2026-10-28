package com.beautysdk

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Yuv420InputTest {
    private fun plane(vararg values: Int) = YuvPlane(ByteBuffer.wrap(values.map { it.toByte() }.toByteArray()), values.size, 1)
    @Test fun limitedBlackAndWhitePreserveMetadata() {
        val info = FrameInfo(2, 1, 99, 90, true)
        val result = Yuv420RgbaConverter().convert(Yuv420Frame(info, plane(16,235), plane(128), plane(128), YuvColorSpace.BT601_LIMITED))
        assertEquals(info, result.info)
        val data = result.readOnlyPixels()
        assertEquals(listOf(0,0,0,255,255,255,255,255), (0 until 8).map { data.get(it).toInt() and 255 })
    }
    @Test fun nv21OffsetPixelStrideAndOddDimensions() {
        val y = ByteBuffer.wrap(byteArrayOf(99,16,16,16,99,16,16,16)).apply { position(1) }
        val vu = ByteBuffer.wrap(byteArrayOf(128.toByte(),128.toByte(),128.toByte(),128.toByte()))
        val v = YuvPlane(vu,4,2)
        val u = YuvPlane(vu.duplicate().apply { position(1) },4,2)
        val result = Yuv420RgbaConverter().convert(Yuv420Frame(FrameInfo(3,2,1),YuvPlane(y,4,1),u,v,YuvColorSpace.BT709_LIMITED))
        assertEquals(24,result.readOnlyPixels().remaining())
        assertEquals(1,y.position())
        assertEquals(0,result.readOnlyPixels().get(20).toInt())
    }
    @Test fun matricesDifferAndChromaOrderingMatters() {
        fun rgb(space: YuvColorSpace) = Yuv420RgbaConverter().convert(Yuv420Frame(FrameInfo(1,1,1),plane(100),plane(90),plane(200),space)).readOnlyPixels()
        val a=rgb(YuvColorSpace.BT601_FULL); val b=rgb(YuvColorSpace.BT709_FULL)
        assertEquals(201,a.get(0).toInt() and 255)
        assertEquals(62,a.get(1).toInt() and 255)
        assertEquals(33,a.get(2).toInt() and 255)
        assertNotEquals(a.get(0),b.get(0))
    }
    @Test fun downsamplesBeforeConversionAndReusesBorrowedStorage() {
        val converter=Yuv420RgbaConverter(16)
        val info=FrameInfo(32,16,1)
        fun source(value: Int)=Yuv420Frame(info,YuvPlane(ByteBuffer.wrap(ByteArray(512){value.toByte()}),32,1),
            YuvPlane(ByteBuffer.wrap(ByteArray(128){128.toByte()}),16,1),YuvPlane(ByteBuffer.wrap(ByteArray(128){128.toByte()}),16,1),YuvColorSpace.BT601_FULL)
        val first=converter.convert(source(0))
        assertEquals(16,first.info.width); assertEquals(8,first.info.height)
        assertTrue(first.readOnlyPixels().isDirect)
        converter.convert(source(255))
        assertEquals(255,first.readOnlyPixels().get(0).toInt() and 255)
    }
    @Test fun invalidPlaneRejectedBeforeRead() {
        assertThrows(IllegalArgumentException::class.java) {
            Yuv420Frame(FrameInfo(3,3,1),plane(0),plane(128),plane(128),YuvColorSpace.BT601_FULL)
        }
    }
    @Test fun textureLeaseReleaseIsIdempotentAcrossThreads() {
        var releases=0
        val lease=GlTextureOutputPool.Lease(TextureFrame(FrameInfo(1,1,1),1,TextureTarget.TEXTURE_2D,FloatArray(16)),{ releases++ })
        Thread { lease.close(); lease.close() }.apply { start(); join() }
        lease.close(); assertEquals(1,releases)
    }
}
