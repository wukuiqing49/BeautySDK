package com.beautysdk

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnalysisFrameMappingTest {
    private fun near(x: Float,y: Float,p: FacePoint) { assertEquals(x,p.x,.00001f);assertEquals(y,p.y,.00001f) }
    @Test fun cropAndLetterboxUseExplicitRegions() {
        val mapping=AnalysisFrameMapping(FrameGeometry(640,480),FrameGeometry(720,1280),
            FrameRegion(.25f,0f,.75f,1f),FrameRegion(0f,.125f,1f,.875f))
        near(0f,.125f,mapping.map(FacePoint(.25f,0f)))
        near(1f,.875f,mapping.map(FacePoint(.75f,1f)))
        near(.5f,.5f,mapping.map(FacePoint(.5f,.5f)))
    }
    @Test fun everyRotationAndMirrorMapsToTargetRawCoordinates() {
        val p=FacePoint(.2f,.3f)
        val expected=listOf(FacePoint(.2f,.3f),FacePoint(.7f,.2f),FacePoint(.8f,.7f),FacePoint(.3f,.8f))
        for ((i,rotation) in listOf(0,90,180,270).withIndex()) for (mirror in listOf(false,true)) {
            val mapping=AnalysisFrameMapping(FrameGeometry(320,240,rotation,mirror),FrameGeometry(1080,1920))
            val q=expected[i];near(if(mirror) 1f-q.x else q.x,q.y,mapping.map(p))
            val inverse=AnalysisFrameMapping(mapping.target,mapping.source)
            near(p.x,p.y,inverse.map(mapping.map(p)))
        }
    }
    @Test fun cropDropsOutsideFacesAndAnchorsWithoutClampingThem() {
        val mapping=AnalysisFrameMapping(FrameGeometry(640,480),FrameGeometry(720,1280),
            FrameRegion(.25f,.25f,.75f,.75f),FrameRegion(.1f,.1f,.9f,.9f))
        assertNull(mapping.map(FaceObservation(FaceBounds(0f,0f,.2f,.2f))))
        val face=mapping.map(FaceObservation(FaceBounds(.1f,.1f,.6f,.6f),mapOf(
            FaceAnchor.LEFT_EYE to FacePoint(.2f,.4f),FaceAnchor.RIGHT_EYE to FacePoint(.5f,.4f))))!!
        assertFalse(face.anchors.containsKey(FaceAnchor.LEFT_EYE))
        assertTrue(face.anchors.containsKey(FaceAnchor.RIGHT_EYE))
        assertEquals(.1f,face.bounds.left,.00001f)
        assertEquals(.1f,face.bounds.top,.00001f)
    }
    @Test fun mappedSessionPreservesAgeCachesCoordinatesAndInvalidatesResolutionChanges() {
        val done=CountDownLatch(1)
        var rendered: FaceObservation?=null
        val session=BeautySession(factory=FaceAnalyzerFactory { object: FaceAnalyzer {
            override val id="mapping-fixture"
            override val capabilities=emptySet<FaceCapability>()
            override fun analyze(image: AnalysisImage): List<FaceObservation> {
                assertEquals(4,image.info.width);assertEquals(2,image.info.height)
                return listOf(FaceObservation(FaceBounds(.3f,.2f,.7f,.8f),mapOf(FaceAnchor.NOSE to FacePoint(.5f,.5f))))
            }
            override fun close()=Unit
        } },renderer=object: BeautyRenderer {
            override fun render(frame: VideoFrame,settings: BeautySettings,face: FaceObservation?): VideoFrame {
                rendered=face; return frame
            }
            override fun close()=Unit
        },onAnalysisTiming={done.countDown()},maxResultAgeNs=150)
        session.use {
            it.settings=BeautySettings(glasses=true)
            it.start()
            val mapping=AnalysisFrameMapping(FrameGeometry(4,2),FrameGeometry(720,1280),FrameRegion(.25f,0f,.75f,1f))
            it.setAnalysisMapping(mapping)
            it.submitAnalysis(RgbaFrame(mapping.source.at(100),ByteBuffer.allocateDirect(32),16))
            assertTrue(done.await(5,TimeUnit.SECONDS))
            val matrix=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
            fun output(time: Long)=TextureFrame(mapping.target.at(time),1,TextureTarget.TEXTURE_2D,matrix)
            it.process(output(120));near(.5f,.5f,rendered!!.anchors.getValue(FaceAnchor.NOSE))
            val result=it.lastFrameAnalysis.result!!
            assertEquals(100,result.info.timestampNs);assertEquals(720,result.info.width)
            assertEquals(20L,it.lastFrameAnalysis.resultAgeNs)
            it.process(output(130));assertSame(result,it.lastFrameAnalysis.result)
            it.process(output(251));assertEquals(FaceAnalysisState.STALE,it.lastFrameAnalysis.state);assertNull(rendered)
            // 即使同一宽高比，目标720->1080也要显式更新映射，旧坐标不会自动跨配置生效。
            it.setAnalysisMapping(mapping.copy(target=FrameGeometry(1080,1920)))
            assertNull(it.latestFaces(FrameInfo(1080,1920,252)))
            assertThrows(IllegalArgumentException::class.java) { it.submitAnalysis(RgbaFrame(FrameInfo(2,2,253),ByteBuffer.allocateDirect(16),8)) }
        }
    }
    @Test fun regionsRejectInvalidAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) { FrameRegion(right=0f) }
        assertThrows(IllegalArgumentException::class.java) { FrameRegion(left=Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { FrameRegion(bottom=1.1f) }
    }
}
