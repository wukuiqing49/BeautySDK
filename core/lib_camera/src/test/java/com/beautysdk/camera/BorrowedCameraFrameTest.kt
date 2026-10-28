package com.beautysdk.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BorrowedCameraFrameTest {
    @Test fun borrowedFrameExposesStrideWithoutCopyAndExpiresAfterCallback() {
        val pixels = ByteBuffer.allocateDirect(24)
        val frame = CameraFrame(2,2,1,90,CameraLens.FRONT,1,pixels,16,true)
        pixels.put(16,42)
        assertEquals(42,frame.readOnlyPixels().get(16).toInt())
        assertEquals(16,frame.rowStrideBytes)
        frame.invalidate()
        assertThrows(IllegalStateException::class.java) { frame.readOnlyPixels() }
    }
    @Test fun existingOwnedFrameRemainsValidAfterCallback() {
        val frame = CameraFrame(1,1,1,0,CameraLens.BACK,1,byteArrayOf(1,2,3,4))
        frame.invalidate()
        assertFalse(frame.borrowed)
        assertEquals(1,frame.readOnlyPixels().get(0).toInt())
    }
}
