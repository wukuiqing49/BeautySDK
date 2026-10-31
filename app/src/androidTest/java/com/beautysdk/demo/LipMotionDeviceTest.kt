package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.mediapipe.NativeFaceMotion
import java.nio.ByteBuffer
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** 全部使用生成纹理，测试局部运动与头部运动分离；不采集或保存相机画面。 */
class LipMotionDeviceTest {
    private val lips=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
    private fun fixture():Pair<ByteArray,FaceObservation> {
        val image=ByteArray(320*320)
        var random=4721
        for(i in image.indices){random=random*1664525+1013904223;image[i]=(40+((random ushr 24) and 127)).toByte()}
        val points=MutableList(478){FacePoint(.5f,.4f)}
        val anchors=intArrayOf(33,133,362,263,1,4,168,197,50,280,205,425,152)
        anchors.forEachIndexed { i,index ->points[index]=FacePoint((40+(i%5)*52)/320f,(45+(i/5)*44)/320f) }
        // 顺序与实际口红三角网格一致：外环20点、内环20点。
        for(i in 0 until 20){val angle=PI+i*PI/10;points[lips[i]]=FacePoint((160+cos(angle)*45).toFloat()/320,(225+sin(angle)*25).toFloat()/320)}
        for(i in 0 until 20){val angle=PI+i*PI/10;points[lips[i+20]]=FacePoint((160+cos(angle)*32).toFloat()/320,(225+sin(angle)*13).toFloat()/320)}
        return image to FaceObservation(FaceBounds(.05f,.05f,.95f,.95f),mapOf(FaceAnchor.NOSE to points[1],FaceAnchor.LEFT_MOUTH to points[61],FaceAnchor.RIGHT_MOUTH to points[291],FaceAnchor.UPPER_LIP to points[0]),points)
    }
    private fun frame(gray:ByteArray,time:Long):RgbaFrame {
        val rgba=ByteBuffer.allocateDirect(320*320*4)
        for(v in gray)rgba.put(v).put(v).put(v).put(255.toByte())
        rgba.flip();return RgbaFrame(FrameInfo(320,320,time),rgba,1280)
    }
    @Test fun moustacheOrientationSurvivesTrackingInEveryRotationAndMirror() {
        val (image,upright)=fixture()
        for(rotation in listOf(0,90,180,270))for(mirrored in listOf(false,true))for(local in listOf(false,true)) {
            val info=FrameInfo(320,320,1,rotationDegrees=rotation,mirrored=mirrored)
            val mapping=AnalysisCoordinates(info)
            val face=mapping.toRaw(upright)
            val raw=ByteArray(image.size)
            for(y in 0 until 320)for(x in 0 until 320) {
                val p=mapping.toRaw(FacePoint((x+.5f)/320,(y+.5f)/320))
                raw[(p.y*320).toInt().coerceIn(0,319)*320+(p.x*320).toInt().coerceIn(0,319)]=image[y*320+x]
            }
            val estimator=NativeFaceMotion(local)
            val reference=estimator.prepare(frame(raw,1))
            val tracked=checkNotNull(estimator.estimate(reference,estimator.prepare(frame(raw,33_000_001)),face))
            val before=checkNotNull(StickerGeometry.place(face,info).moustache)
            val after=checkNotNull(StickerGeometry.place(tracked,info).moustache)
            val direction=cos(after.angleRadians-before.angleRadians)
            assertTrue("Moustache flipped: rotation=$rotation mirror=$mirrored local=$local direction=$direction",direction>.99f)
            assertEquals(before.spanPixels,after.spanPixels,1f)
        }
    }
    private fun runMotion(deform:Boolean,rotated:Boolean=false,occluded:Boolean=false) {
        val (image,initialFace)=fixture();val changed=image.copyOf()
        val face=if(!rotated)initialFace else FaceObservation(initialFace.bounds,initialFace.anchors.mapValues { (_,p)->FacePoint(p.y,p.x) },initialFace.landmarks.map { FacePoint(it.y,it.x) })
        // 头部静止，口红区域整体移动或上下唇分别展开；线性插值生成亚像素真值。
        for(y in 180..270)for(x in 100..220) {
            val sourceY=if(deform)225f+(y-225)/1.08f else y-2f
            val low=floor(sourceY).toInt();val fraction=sourceY-low
            changed[y*320+x]=(((image[low*320+x].toInt() and 255)*(1-fraction)+(image[(low+1)*320+x].toInt() and 255)*fraction).roundToInt()).toByte()
        }
        if(occluded) for(y in 216..234)for(x in 106..124)changed[y*320+x]=100.toByte()
        val old=NativeFaceMotion();val local=NativeFaceMotion(enableLipTracking=true)
        fun orient(data:ByteArray)=if(!rotated)data else ByteArray(data.size).also { out ->
            for(y in 0 until 320)for(x in 0 until 320)out[x*320+y]=data[y*320+x]
        }
        val before=frame(orient(image),1);val after=frame(orient(changed),33_000_001)
        val base=old.estimate(old.prepare(before),old.prepare(after),face)!!
        val reference=local.prepare(before);val current=local.prepare(after)
        val times=mutableListOf<Double>();var result:FaceObservation?=null
        repeat(8){val start=System.nanoTime();result=local.estimate(reference,current,face);times+=(System.nanoTime()-start)/1e6}
        assertNotNull(result)
        fun error(f:FaceObservation)=lips.map { i ->
            val source=initialFace.landmarks[i].y*320
            val expected=if(deform)225+(source-225)*1.08f else source+2
            abs((if(rotated)f.landmarks[i].x else f.landmarks[i].y)*320-expected)
        }.average()
        val baseline=error(base);val refined=error(result!!)
        assertTrue("local lip error $refined vs baseline $baseline",refined<baseline*.65)
        assertEquals(face.anchors.getValue(FaceAnchor.NOSE).y,result!!.anchors.getValue(FaceAnchor.NOSE).y,.004f)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("stream","\nLIP_LOCAL deform=$deform rotatedMirrored=$rotated occludedCorner=$occluded baselineErrorPx=$baseline localErrorPx=$refined meanMs=${times.average()} maxMs=${times.maxOrNull()}\n")})
    }
    @Test fun oneOccludedCornerKeepsSupportedLipDeformation()=runMotion(true,occluded=true)
    @Test fun lipsMoveWithoutMovingTheHead()=runMotion(false)
    @Test fun lipsDeformWithoutMovingTheHead()=runMotion(true)
    @Test fun localTrackingRespectsRotatedMirroredInputs()=runMotion(false,true)
    /** 已知整帧位移叠加嘴部独立运动，连续接力而非每次回到首帧。 */
    @Test fun fastTranslationKeepsFullContourAcrossAdjacentFrames() {
        val (image,initial)=fixture();val estimator=NativeFaceMotion(true)
        var previous=estimator.prepare(frame(image,1));var face=initial
        var maxMean=0.0;val costs=mutableListOf<Double>()
        for(step in 1..8) {
            val dx=step*8;val lipDy=if(step%4<2)0 else 2
            val moved=ByteArray(image.size)
            for(y in 0 until 320)for(x in dx until 320) {
                val sx=x-dx;val sy=if(sx in 100..220 && y in 180..270)y-lipDy else y
                if(sy in 0 until 320)moved[y*320+x]=image[sy*320+sx]
            }
            val current=estimator.prepare(frame(moved,1+step*33_000_000L))
            val start=System.nanoTime();val result=estimator.estimate(previous,current,face)
            costs+=(System.nanoTime()-start)/1e6
            assertNotNull("adjacent step=$step",result);face=result!!
            val error=lips.map {index ->
                val p=face.landmarks[index];val original=initial.landmarks[index]
                hypot((p.x-original.x)*320-dx,(p.y-original.y)*320-lipDy).toDouble()
            }.average()
            maxMean=maxOf(maxMean,error)
            assertTrue("full contour step=$step meanErrorPx=$error",error<3.0)
            val nose=face.anchors.getValue(FaceAnchor.NOSE);val origin=initial.anchors.getValue(FaceAnchor.NOSE)
            assertEquals(origin.x+dx/320f,nose.x,2f/320)
            assertEquals(origin.y,nose.y,2f/320)
            previous=current
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("stream","\nLIP_ADJACENT frames=8 frameShiftPx=8 totalShiftPx=64 intervalMs=33 maxMeanContourErrorPx=$maxMean meanEstimateMs=${costs.average()}\n")
        })
    }

    @Test fun blankMouthRejectsLocalCorrectionAndStaticDoesNotSnap() {
        val (image,original)=fixture()
        val face=FaceObservation(original.bounds,original.anchors,original.landmarks.map { FacePoint(it.x+.0007f,it.y+.0007f) })
        val estimator=NativeFaceMotion(true);val before=frame(image,1);val reference=estimator.prepare(before)
        val same=estimator.estimate(reference,estimator.prepare(frame(image,33_000_001)),face)!!
        lips.forEach { assertEquals(face.landmarks[it].x,same.landmarks[it].x,.0015f);assertEquals(face.landmarks[it].y,same.landmarks[it].y,.0015f) }
        val covered=image.copyOf();for(y in 180..270)for(x in 100..220)covered[y*320+x]=100.toByte()
        val current=frame(covered,66_000_001)
        val result=estimator.estimate(reference,estimator.prepare(current),face)!!
        val baseline=NativeFaceMotion().let { it.estimate(it.prepare(before),it.prepare(current),face) }!!
        lips.forEach {assertEquals(baseline.landmarks[it],result.landmarks[it])}
        // 公共FacePoint允许有限但越界的坐标，JNI必须在整数采样前拒绝它。
        val invalid=face.landmarks.toMutableList().also {it[37]=FacePoint(1e20f,1e20f)}
        val malformed=FaceObservation(face.bounds,face.anchors,invalid)
        assertNotNull(estimator.estimate(reference,estimator.prepare(frame(image,99_000_001)),malformed))
    }
}
