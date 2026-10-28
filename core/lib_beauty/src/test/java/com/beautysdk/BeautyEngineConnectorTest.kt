package com.beautysdk

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BeautyEngineConnectorTest {
    private fun frame(time:Long=1)=RgbaFrame(FrameInfo(1,1,time),ByteBuffer.wrap(byteArrayOf(20,30,40,-1)),4)
    private class Adapter : BeautyEngineFrameAdapter<RgbaFrame> {
        val events=mutableListOf<String>()
        var failRead=false; var failWrite=false; var failBypass=false; var accept=true
        var output:VideoFrame?=null
        override fun read(frame:RgbaFrame):VideoFrame { events+="read"; check(!failRead); return frame }
        override fun write(frame:RgbaFrame,output:VideoFrame):Boolean {
            events+="write"; check(!failWrite); this.output=output; return accept
        }
        override fun passthrough(frame:RgbaFrame):Boolean { events+="bypass"; check(!failBypass); return accept }
    }
    @Test fun realBeautyIsProcessedAndWrittenThroughOneEntry() {
        val adapter=Adapter()
        val pipeline=BeautyEnginePipeline(renderer=RgbaBeautyRenderer(),textureAnalysis=false)
        pipeline.settings=BeautySettings(whitening=1f)
        BeautyEngineConnector(adapter,pipeline).use { connector ->
            connector.start()
            assertEquals(BeautyEngineFrameResult.PROCESSED,connector.onFrame(frame()))
            assertEquals(listOf("read","write"),adapter.events)
            assertTrue(((adapter.output as RgbaFrame).readOnlyPixels().get(0).toInt() and 255)>20)
        }
    }
    @Test fun identityStillWritesOriginalFrameToEngine() {
        val adapter=Adapter(); val input=frame()
        BeautyEngineConnector(adapter,BeautyProcessor()).use { c ->
            c.start(); assertEquals(BeautyEngineFrameResult.BYPASSED,c.onFrame(input))
            assertSame(input,adapter.output); assertEquals(listOf("read","write"),adapter.events)
        }
    }
    @Test fun readFailureFallsBackOnceAndReports() {
        val adapter=Adapter().apply { failRead=true }; val errors=mutableListOf<Exception>()
        BeautyEngineConnector(adapter,BeautyProcessor(),errors::add).use { c ->
            c.start(); assertEquals(BeautyEngineFrameResult.BYPASSED,c.onFrame(frame()))
            assertEquals(listOf("read","bypass"),adapter.events); assertEquals(1,errors.size)
        }
    }
    @Test fun processingFailureFallsBackButWritingFailureNeverRetries() {
        val adapter=Adapter()
        val failing=object:FrameProcessor {
            override fun start() { }; override fun stop() { }; override fun close() { }
            override fun process(frame:VideoFrame):VideoFrame = error("fixture failure")
        }
        BeautyEngineConnector(adapter,failing).use { c ->
            c.start(); assertEquals(BeautyEngineFrameResult.BYPASSED,c.onFrame(frame()))
            assertEquals(listOf("read","bypass"),adapter.events)
        }
        adapter.events.clear(); adapter.failWrite=true
        BeautyEngineConnector(adapter,BeautyProcessor()).use { c ->
            c.start(); assertEquals(BeautyEngineFrameResult.REJECTED,c.onFrame(frame()))
            assertEquals(listOf("read","write"),adapter.events)
        }
    }
    @Test fun engineRejectionAndFallbackFailureAreExplicit() {
        val adapter=Adapter().apply { accept=false }
        BeautyEngineConnector(adapter,BeautyProcessor()).use { c ->
            c.start(); assertFalse(c.onFrame(frame()).accepted)
            adapter.failRead=true; adapter.failBypass=true
            assertEquals(BeautyEngineFrameResult.REJECTED,c.onFrame(frame(2)))
        }
    }
    @Test fun restartResetsBothTimeLinesAndDuplicateFrameBypasses() {
        val adapter=Adapter()
        BeautyEngineConnector(adapter,BeautyEnginePipeline(renderer=RgbaBeautyRenderer(),textureAnalysis=false)).use { c ->
            c.start(); assertTrue(c.onFrame(frame(100)).accepted)
            adapter.events.clear(); c.onFrame(frame(100))
            assertEquals(listOf("read","bypass"),adapter.events)
            c.resetSource(); adapter.events.clear(); assertTrue(c.onFrame(frame(1)).accepted)
            assertEquals(listOf("read","write"),adapter.events)
        }
    }
    @Test fun lifecycleAndWrongThreadAreRejectedBeforeTouchingEngine() {
        val adapter=Adapter(); val c=BeautyEngineConnector(adapter,BeautyProcessor())
        assertThrows(IllegalStateException::class.java) { c.onFrame(frame()) }
        c.start()
        val failure=AtomicReference<Throwable>()
        Thread { try { c.onFrame(frame()) } catch(e:Throwable) { failure.set(e) } }.apply { start(); join() }
        assertTrue(failure.get() is IllegalStateException); assertTrue(adapter.events.isEmpty())
        failure.set(null)
        Thread { try { c.close() } catch(e:Throwable) { failure.set(e) } }.apply { start(); join() }
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(c.onFrame(frame()).accepted); c.close(); c.close()
        assertThrows(IllegalStateException::class.java) { c.onFrame(frame(2)) }
    }
    @Test fun reentrantWriteCannotCauseDoubleSubmission() {
        var writes=0; var bypasses=0
        lateinit var c:BeautyEngineConnector<RgbaFrame>
        val adapter=object:BeautyEngineFrameAdapter<RgbaFrame> {
            override fun read(frame:RgbaFrame)=frame
            override fun write(frame:RgbaFrame,output:VideoFrame):Boolean { writes++; c.onFrame(frame); return true }
            override fun passthrough(frame:RgbaFrame):Boolean { bypasses++; return true }
        }
        c=BeautyEngineConnector(adapter,BeautyProcessor())
        c.use { it.start(); assertEquals(BeautyEngineFrameResult.REJECTED,it.onFrame(frame())) }
        assertEquals(1,writes); assertEquals(0,bypasses)
    }
    @Test fun redrawAppliesSettingsWithoutAdvancingVideoTime() {
        val adapter=Adapter()
        val pipeline=BeautyEnginePipeline(renderer=RgbaBeautyRenderer(),textureAnalysis=false)
        BeautyEngineConnector(adapter,pipeline).use { c ->
            c.start(); c.onFrame(frame(10))
            pipeline.settings=BeautySettings(whitening=1f)
            assertEquals(BeautyEngineFrameResult.PROCESSED,c.onRedraw(frame(10)))
            assertTrue(((adapter.output as RgbaFrame).readOnlyPixels().get(0).toInt() and 255)>20)
            adapter.events.clear()
            c.onRedraw(frame(11))
            assertEquals(listOf("read","bypass"),adapter.events)
            adapter.events.clear(); c.onFrame(frame(11))
            assertEquals(listOf("read","write"),adapter.events)
            c.resetSource(); adapter.events.clear(); c.onRedraw(frame(11))
            assertEquals(listOf("read","bypass"),adapter.events)
        }
    }
    @Test fun unsupportedRedrawDoesNotResubmitProcessor() {
        val adapter=Adapter()
        BeautyEngineConnector(adapter,BeautyProcessor()).use { c ->
            c.start(); c.onFrame(frame()); adapter.events.clear()
            c.onRedraw(frame())
            assertEquals(listOf("read","bypass"),adapter.events)
            adapter.events.clear(); c.onFrame(frame(2))
            assertEquals(listOf("read","write"),adapter.events)
        }
    }
}
