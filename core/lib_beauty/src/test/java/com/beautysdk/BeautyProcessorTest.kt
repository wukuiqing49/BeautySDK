package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.util.concurrent.atomic.AtomicReference

class BeautyProcessorTest {
    private fun frame(time: Long = 0) = RgbaFrame(FrameInfo(1, 1, time), ByteBuffer.allocate(4), 4)

    @Test fun lifecycleAndRestartPreserveOriginalFrame() {
        val processor = BeautyProcessor()
        assertThrows(IllegalStateException::class.java) { processor.process(frame()) }
        processor.start()
        processor.start()
        val input = frame(100)
        assertSame(input, processor.process(input))
        processor.stop()
        assertThrows(IllegalStateException::class.java) { processor.process(frame(101)) }
        processor.start()
        val restarted = frame(0)
        assertSame(restarted, processor.process(restarted))
        processor.close()
        processor.close()
        assertThrows(IllegalStateException::class.java) { processor.start() }
        assertThrows(IllegalStateException::class.java) { processor.process(frame(200)) }
    }

    @Test fun duplicateAndBackwardsTimestampsDoNotAdvanceSession() {
        BeautyProcessor().use { processor ->
            processor.start()
            processor.process(frame(10))
            assertThrows(IllegalArgumentException::class.java) { processor.process(frame(10)) }
            assertThrows(IllegalArgumentException::class.java) { processor.process(frame(9)) }
            processor.start() // 重复 start 不能重置活跃会话时间戳。
            assertThrows(IllegalArgumentException::class.java) { processor.process(frame(0)) }
            processor.process(frame(11))
        }
    }

    @Test fun wrongThreadCannotProcessOrCloseOwnerSession() {
        BeautyProcessor().use { processor ->
            processor.start()
            val processError = AtomicReference<Throwable>()
            val closeError = AtomicReference<Throwable>()
            val worker = Thread {
                try { processor.process(frame()) } catch (error: Throwable) { processError.set(error) }
                try { processor.close() } catch (error: Throwable) { closeError.set(error) }
            }
            worker.start()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(processError.get() is IllegalStateException)
            assertTrue(closeError.get() is IllegalStateException)
            processor.process(frame())
        }
    }

    @Test fun rgbaSliceRespectsPositionPaddingAndReadOnlyOwnership() {
        val buffer = ByteBuffer.allocate(32)
        buffer.put(3, 99)
        buffer.position(3)
        buffer.limit(23)
        val input = RgbaFrame(FrameInfo(2, 2, 0), buffer, 12)
        buffer.position(0)
        val view = input.readOnlyPixels()
        assertEquals(20, view.remaining())
        assertEquals(99.toByte(), view.get())
        assertEquals(0, input.readOnlyPixels().position())
        assertThrows(ReadOnlyBufferException::class.java) { view.put(0, 1) }
        buffer.put(3, 42) // 只读视图不是数据副本：宿主仍拥有内存。
        assertEquals(42.toByte(), input.readOnlyPixels().get(0))
    }

    @Test fun rejectsUndersizedAndOverflowSizedBuffers() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFrame(FrameInfo(2, 2, 0), ByteBuffer.allocate(19), 12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFrame(FrameInfo(2, 1, 0), ByteBuffer.allocate(8), 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFrame(FrameInfo(Int.MAX_VALUE, 2, 0), ByteBuffer.allocate(4), Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) { FrameInfo(0, 1, 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameInfo(1, 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { FrameInfo(1, 1, 0, 45) }
    }

    @Test fun textureTransformCannotBeMutatedThroughCallerArrays() {
        val matrix = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        val texture = TextureFrame(FrameInfo(640, 480, 0, 90, true), 7, TextureTarget.EXTERNAL_OES, matrix)
        matrix[0] = 0f
        texture.copyTransform()[0] = 2f
        assertEquals(1f, texture.copyTransform()[0], 0f)
        assertThrows(IllegalArgumentException::class.java) {
            TextureFrame(texture.info, 0, texture.target, matrix)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextureFrame(texture.info, 7, texture.target, FloatArray(15))
        }
        matrix[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            TextureFrame(texture.info, 7, texture.target, matrix)
        }
    }
}
