package com.beautysdk.demo

import android.opengl.EGL14
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.gpupixel.GpuPixelBeautyRenderer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** 需设备 ES3 的合成回归；仅测试读取生成纹理，不保存实时相机图像。 */
class GpuPixelDeviceTest {
    /** 同一连续合成纹理按三个分辨率采样，比较同视场探针；不是相机/真人画质评价。 */
    @Test fun smoothingMaintainsRelativeScaleAcrossResolutions() = withEgl {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val probes=mutableListOf<DoubleArray>()
        for((w,h) in listOf(640 to 360,1280 to 720,1920 to 1080)) {
            val ids=IntArray(1);GL.glGenTextures(1,ids,0)
            try {
                GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                val data=ByteBuffer.allocateDirect(w*h*4)
                for(y in 0 until h) for(x in 0 until w) {
                    val wave=7*kotlin.math.sin((x+.5)/w*32*Math.PI)+7*kotlin.math.cos((y+.5)/h*24*Math.PI)
                    data.put((170+wave).toInt().toByte()).put((120+wave).toInt().toByte())
                        .put((100+wave).toInt().toByte()).put(173.toByte())
                }
                data.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
                val input=TextureFrame(FrameInfo(w,h,1),ids[0],TextureTarget.TEXTURE_2D,
                    floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
                val original=read(input)
                val output=GpuPixelBeautyRenderer(context).use {renderer ->
                    read(renderer.render(input,BeautySettings(smoothing=1f),null) as TextureFrame)
                }
                var change=0.0
                for(i in output.indices step 4) {
                    change+=kotlin.math.abs((output[i].toInt() and 255)-(original[i].toInt() and 255))
                    assertEquals(173,output[i+3].toInt() and 255)
                }
                assertTrue("Smoothing must actually affect pixels",change/(w*h)>.5)
                // 在共同归一化位置双线性读取，避免直接按不同像素中心比较引入相位误差。
                val sample=DoubleArray(48*32*3)
                for(py in 0 until 32) for(px in 0 until 48) {
                    val x=(.1+.8*(px+.5)/48)*w-.5;val y=(.1+.8*(py+.5)/32)*h-.5
                    val ix=x.toInt();val iy=y.toInt();val fx=x-ix;val fy=y-iy
                    for(c in 0..2) {
                        fun at(dx:Int,dy:Int)=(output[((iy+dy)*w+ix+dx)*4+c].toInt() and 255).toDouble()
                        sample[(py*48+px)*3+c]=(at(0,0)*(1-fx)+at(1,0)*fx)*(1-fy)+
                            (at(0,1)*(1-fx)+at(1,1)*fx)*fy
                    }
                }
                probes.add(sample)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            } finally {GL.glDeleteTextures(1,ids,0)}
        }
        for(i in 1 until probes.size) {
            val error=probes[0].indices.sumOf {kotlin.math.abs(probes[0][it]-probes[i][it])}/probes[0].size
            android.util.Log.i("BeautyResolution","smoothing cross-resolution MAE[$i]=$error")
            assertTrue("Cross-resolution mean RGB error must stay <=1.5/255; got $error",error<=1.5)
        }
    }

    @Test fun lazyTargetsResizeAndTimerPreservesHostQuery() = withEgl {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=IntArray(1);GL.glGenTextures(1,id,0)
        try {
            GpuPixelBeautyRenderer(context).use { renderer ->
                assertEquals(0L,renderer.intermediateTextureBytes())
                val supported=renderer.setGpuTimingEnabled(true)
                for(size in listOf(64,128,32)) {
                    GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
                    GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                    val pixels=ByteBuffer.allocateDirect(size*size*4)
                    repeat(size*size) { pixels.put(90).put(80).put(70).put(173.toByte()) };pixels.flip()
                    GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,size,size,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
                    val input=TextureFrame(FrameInfo(size,size,1),id[0],TextureTarget.TEXTURE_2D,
                        floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
                    val effects=BeautySettings(smoothing=.6f,whitening=.3f)
                    val output=renderer.render(input,effects,null) as TextureFrame
                    assertEquals(size.toLong()*size*4*4,renderer.intermediateTextureBytes())
                    val actual=read(output)
                    for(i in 3 until actual.size step 4) assertEquals(173,actual[i].toInt() and 255)
                    if(supported) {
                        val query=IntArray(1);GL.glGenQueries(1,query,0)
                        try {
                            GL.glBeginQuery(0x88BF,query[0]) // GL_TIME_ELAPSED_EXT
                            renderer.render(input,effects,null)
                            val current=IntArray(1);GL.glGetQueryiv(0x88BF,GL.GL_CURRENT_QUERY,current,0)
                            assertEquals("Host query must remain active",query[0],current[0])
                        } finally { GL.glEndQuery(0x88BF);GL.glDeleteQueries(1,query,0) }
                    }
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
            }
        } finally { GL.glDeleteTextures(1,id,0) }
    }

    /** 固定合成纹理：记录输出摘要及完成GPU工作的墙钟耗时；glFinish仅用于此测试。 */
    @Test fun reproducibleRenderBenchmark() = withEgl {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val results=org.json.JSONArray()
        for(size in listOf(64 to 64,1280 to 720,1920 to 1080)) {
            val (w,h)=size
            val id=IntArray(1);GL.glGenTextures(1,id,0)
            try {
                GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                val data=ByteBuffer.allocateDirect(w*h*4)
                for(y in 0 until h) for(x in 0 until w) data.put((70+x%150).toByte())
                    .put((40+y%180).toByte()).put((20+(x+y)%120).toByte()).put(173.toByte())
                data.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
                val input=TextureFrame(FrameInfo(w,h,1),id[0],TextureTarget.TEXTURE_2D,
                    floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
                val cases=listOf(BeautySettings(whitening=.5f),
                    BeautySettings(adjustments=BeautyAdjustments(brightness=.2f)),
                    BeautySettings(smoothing=.6f,whitening=.3f),
                    BeautySettings(smoothing=.6f,whitening=.3f,adjustments=BeautyAdjustments(saturation=.7f)))
                cases.forEachIndexed { index,settings ->
                    GpuPixelBeautyRenderer(context).use { renderer ->
                        var output=input
                        val times=ArrayList<Long>()
                        repeat(25) { n ->
                            val start=System.nanoTime()
                            output=renderer.render(input,settings,null) as TextureFrame
                            GL.glFinish()
                            if(n>=5) times.add(System.nanoTime()-start)
                        }
                        val pixels=read(output)
                        for(i in 3 until pixels.size step 4) assertEquals(173,pixels[i].toInt() and 255)
                        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(pixels)
                            .joinToString("") { "%02x".format(it) }
                        val sorted=times.sorted()
                        val bytes=renderer.intermediateTextureBytes()
                        assertEquals(w.toLong()*h*4*(if(index<2) 2 else 4),bytes)
                        val timingSupported=renderer.setGpuTimingEnabled(true)
                        repeat(6) { renderer.render(input,settings,null);GL.glFinish() }
                        val gpuNs=renderer.latestGpuExecutionNs()
                        val disjoint=renderer.gpuTimingDisjointCount()
                        if(timingSupported) assertTrue("Completed query or explicit disjoint rejection",gpuNs!=null || disjoint>0)
                        else assertNull(gpuNs)
                        assertTrue(renderer.setGpuTimingEnabled(false))
                        assertNull(renderer.latestGpuExecutionNs())
                        results.put(org.json.JSONObject().put("width",w).put("height",h).put("case",index)
                            .put("sha256",hash).put("completedWallMeanNs",times.average())
                            .put("completedWallP95Ns",sorted[18]).put("intermediateTextureBytes",bytes)
                            .put("gpuTimerSupported",timingSupported).put("gpuDisjointCount",disjoint)
                            .put("gpuExecutionNs",gpuNs ?: org.json.JSONObject.NULL))
                        assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                    }
                }
            } finally { GL.glDeleteTextures(1,id,0) }
        }
        for(i in 0 until results.length()) android.util.Log.i("BeautyRenderBenchmark",results.getJSONObject(i).toString())
    }

    @Test fun texturePipelinePreservesTransformAlphaAndHostState() = withEgl {
        val ids = IntArray(2); GL.glGenTextures(2,ids,0)
        val pixels = ByteArray(64*64*4) { index ->
            val pixel=index/4; val x=pixel%64; val y=pixel/64
            when(index%4) { 0 -> 70+x*2; 1 -> 50+y*2; 2 -> 90+(x+y)/2; else -> 173 }.toByte()
        }
        fun upload(texture: Int, data: ByteArray) {
            GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            val buffer=ByteBuffer.allocateDirect(data.size).apply { put(data); flip() }
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,buffer)
        }
        upload(ids[0],pixels)
        val reversed=ByteArray(pixels.size)
        for (y in 0..63) for (x in 0..63) for (c in 0..3)
            reversed[(y*64+x)*4+c]=pixels[(y*64+63-x)*4+c]
        upload(ids[1],reversed)
        val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        val flip=identity.copyOf().apply { this[0]=-1f; this[12]=1f }
        val info=FrameInfo(64,64,100,90,true)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            GpuPixelBeautyRenderer(app).use { renderer ->
                val input=TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,identity)
                assertSame(input,renderer.render(input,BeautySettings(),null))
                val effects=BeautySettings(smoothing=.6f,whitening=.3f)
                for (unit in 0..6) {
                    GL.glActiveTexture(GL.GL_TEXTURE0+unit); GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
                }
                GL.glViewport(2,3,17,19); GL.glEnable(GL.GL_SCISSOR_TEST)
                GL.glColorMask(false,true,false,true); GL.glActiveTexture(GL.GL_TEXTURE6)
                val output=renderer.render(input,effects,null) as TextureFrame
                assertEquals(info,output.info); assertArrayEquals(identity,output.copyTransform(),0f)
                val viewport=IntArray(4); GL.glGetIntegerv(GL.GL_VIEWPORT,viewport,0)
                assertArrayEquals(intArrayOf(2,3,17,19),viewport)
                assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST))
                val value=IntArray(1); GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE,value,0)
                assertEquals(GL.GL_TEXTURE6,value[0])
                for (unit in 0..6) {
                    GL.glActiveTexture(GL.GL_TEXTURE0+unit)
                    GL.glGetIntegerv(GL.GL_TEXTURE_BINDING_2D,value,0); assertEquals(ids[0],value[0])
                }
                val mask=BooleanArray(4); GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK,mask,0)
                assertArrayEquals(booleanArrayOf(false,true,false,true),mask)
                GL.glDisable(GL.GL_SCISSOR_TEST); GL.glColorMask(true,true,true,true)
                val first=read(output)
                assertTrue("Beauty must modify RGB",pixels.indices.any { it%4!=3 && pixels[it]!=first[it] })
                for (i in 0 until 64*64) assertEquals(173,first[i*4+3].toInt() and 255)
                val transformed=renderer.render(TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,flip),effects,null) as TextureFrame
                assertEquals(output.textureId,transformed.textureId)
                val actual=read(transformed)
                val expected=read(renderer.render(TextureFrame(info,ids[1],TextureTarget.TEXTURE_2D,identity),effects,null) as TextureFrame)
                actual.indices.forEach { assertTrue("Transform channel $it",
                    kotlin.math.abs((actual[it].toInt() and 255)-(expected[it].toInt() and 255))<=2) }
                // 美白单独执行也必须出图，验证跳过 blur 的分支。
                read(renderer.render(input,BeautySettings(whitening=.5f),null) as TextureFrame)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally { GL.glDeleteTextures(2,ids,0) }
    }

    /** 固定纹理逐个验证真实像素变化；不使用相机帧，也不把fixture当画质验收。 */
    @Test fun extendedColorAndReshapeChangePixelsAndRespectMissingFace() = withEgl {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=IntArray(1);GL.glGenTextures(1,id,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            val pixels=ByteBuffer.allocateDirect(64*64*4)
            for(y in 0 until 64) for(x in 0 until 64) pixels.put((70+x*2).toByte()).put((40+y*2).toByte())
                .put((20+(x+y)%5*12).toByte()).put(173.toByte())
            pixels.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixels)
            val info=FrameInfo(64,64,1,90,true)
            val input=TextureFrame(info,id[0],TextureTarget.TEXTURE_2D,floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
            val original=read(input)
            val landmarks=MutableList(478) { FacePoint(.5f,.5f) }
            listOf(234,132,172,136).forEach { landmarks[it]=FacePoint(.22f,.58f) }
            listOf(454,361,397,365).forEach { landmarks[it]=FacePoint(.78f,.58f) }
            landmarks[152]=FacePoint(.5f,.85f)
            landmarks[33]=FacePoint(.25f,.35f);landmarks[133]=FacePoint(.40f,.35f)
            landmarks[362]=FacePoint(.60f,.35f);landmarks[263]=FacePoint(.75f,.35f)
            val face=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),landmarks=landmarks)
            GpuPixelBeautyRenderer(context).use { renderer ->
                assertSame(input,renderer.render(input,BeautySettings(),null))
                val cases=listOf(BeautyAdjustments(rosy=1f),BeautyAdjustments(brightness=.5f),
                    BeautyAdjustments(contrast=1.5f),BeautyAdjustments(saturation=0f),BeautyAdjustments(sharpening=1f),
                    BeautyAdjustments(slimFace=1f),BeautyAdjustments(bigEyes=1f))
                cases.forEachIndexed { index,adjustments ->
                    val output=renderer.render(input,BeautySettings(adjustments=adjustments),face) as TextureFrame
                    assertEquals(info,output.info)
                    val actual=read(output)
                    assertTrue("Effect $index must change RGB",original.indices.any { it%4!=3 && original[it]!=actual[it] })
                    for(pixel in 0 until 64*64) assertEquals(173,actual[pixel*4+3].toInt() and 255)
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
                val reshape=BeautySettings(adjustments=BeautyAdjustments(slimFace=1f,bigEyes=1f))
                assertSame(input,renderer.render(input,reshape,null))
                assertSame(input,renderer.render(input,reshape,FaceObservation(face.bounds)))
                val combined=renderer.render(input,BeautySettings(smoothing=.3f,whitening=.2f,
                    adjustments=BeautyAdjustments(brightness=.1f,slimFace=.5f,bigEyes=.5f)),face) as TextureFrame
                assertFalse(read(combined).contentEquals(original))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally { GL.glDeleteTextures(1,id,0) }
    }

    /** 直接OES美型及调色入口，覆盖未来直播引擎外部纹理输入，不依赖CameraX。 */
    @Test fun oesReshapeAndColorUseSharedTextureContract() = withEgl {
        val id=IntArray(1);GL.glGenTextures(1,id,0);GL.glBindTexture(0x8D65,id[0])
        val producer=android.graphics.SurfaceTexture(id[0]);producer.setDefaultBufferSize(64,32)
        val surface=android.view.Surface(producer)
        try {
            val canvas=surface.lockCanvas(null)
            val paint=android.graphics.Paint()
            for(y in 0 until 4) for(x in 0 until 8) {
                paint.color=if((x+y)%2==0) android.graphics.Color.RED else android.graphics.Color.BLUE
                canvas.drawRect(x*8f,y*8f,(x+1)*8f,(y+1)*8f,paint)
            }
            surface.unlockCanvasAndPost(canvas);producer.updateTexImage()
            val matrix=FloatArray(16);producer.getTransformMatrix(matrix)
            val info=FrameInfo(64,32,20,270,true)
            val input=TextureFrame(info,id[0],TextureTarget.EXTERNAL_OES,matrix)
            val points=MutableList(478) { FacePoint(.5f,.5f) }
            points[33]=FacePoint(.25f,.35f);points[133]=FacePoint(.4f,.35f)
            points[362]=FacePoint(.6f,.35f);points[263]=FacePoint(.75f,.35f)
            points[50]=FacePoint(.3f,.55f);points[280]=FacePoint(.7f,.55f)
            points[1]=FacePoint(.5f,.5f);points[152]=FacePoint(.5f,.9f)
            points[234]=FacePoint(.1f,.55f);points[454]=FacePoint(.9f,.55f)
            val face=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),landmarks=points)
            GpuPixelBeautyRenderer(InstrumentationRegistry.getInstrumentation().targetContext).use { renderer ->
                for(adjustments in listOf(BeautyAdjustments(bigEyes=1f),BeautyAdjustments(brightness=.3f),BeautyAdjustments(blusher=1f))) {
                    GL.glViewport(3,4,15,16)
                    val output=renderer.render(input,BeautySettings(adjustments=adjustments),face) as TextureFrame
                    assertEquals(TextureTarget.TEXTURE_2D,output.target);assertEquals(info,output.info)
                    val viewport=IntArray(4);GL.glGetIntegerv(GL.GL_VIEWPORT,viewport,0)
                    assertArrayEquals(intArrayOf(3,4,15,16),viewport)
                    val bytes=read(output)
                    assertTrue(bytes.indices.any { it%4!=3 && bytes[it].toInt()!=0 })
                    for(pixel in 0 until 64*32) assertEquals(255,bytes[pixel*4+3].toInt() and 255)
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
            }
        } finally { surface.release();producer.release();GL.glDeleteTextures(1,id,0) }
    }

    /** 验证真实局部贴妆像素、口腔留空、强度、缺点旁路、变换及状态/纹理复用。 */
    @Test fun localMakeupPreservesMouthInteriorAlphaAndHostState() = withEgl {
        val id=IntArray(2);GL.glGenTextures(2,id,0)
        val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        val info=FrameInfo(96,128,50,270,true)
        val pixels=ByteArray(96*128*4) { i -> when(i%4){0->150+(i/4)%96/2;1->125;2->100;else->173}.toByte() }
        fun upload(texture:Int, bytes:ByteArray) {
            GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,96,128,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(bytes.size).apply {put(bytes);flip()})
        }
        val indices=intArrayOf(61,40,37,0,267,270,291,321,314,17,84,91,78,81,13,311,308,402,14,178)
        val uv=floatArrayOf(.426186f,.593516f,.453348f,.586128f,.481258f,.582594f,.5f,.584476f,
            .518742f,.582594f,.546652f,.586128f,.573814f,.593516f,.556544f,.620391f,.53132f,.639672f,
            .5f,.644911f,.46868f,.639672f,.443456f,.620391f,.433718f,.595595f,.466898f,.597025f,
            .5f,.599883f,.533102f,.597025f,.566282f,.595595f,.534634f,.61072f,.5f,.616173f,.465366f,.61072f)
        val points=MutableList(478){FacePoint(.5f,.5f)}
        for(i in indices.indices) points[indices[i]]=FacePoint((uv[i*2]-.5f)*3f+.5f,(uv[i*2+1]-.6f)*3f+.65f)
        val contour=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
        for((base,known) in listOf(0 to intArrayOf(0,2,4,5,6,8,10,12,14,15,16,18),20 to intArrayOf(0,3,5,7,10,13,15,17))) {
            for(k in known.indices) {
                val from=known[k];val to=if(k==known.lastIndex)20 else known[k+1]
                val a=points[contour[base+from]];val b=points[contour[base+to%20]]
                for(j in from+1 until to) {val t=(j-from).toFloat()/(to-from);points[contour[base+j]]=FacePoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)}
            }
        }
        points[33]=FacePoint(.2f,.25f);points[263]=FacePoint(.8f,.25f)
        points[50]=FacePoint(.25f,.45f);points[280]=FacePoint(.75f,.45f);points[152]=FacePoint(.5f,.95f)
        val face=FaceObservation(FaceBounds(.05f,.1f,.95f,.98f),landmarks=points)
        try {
            upload(id[0],pixels)
            val reversed=ByteArray(pixels.size)
            for(y in 0 until 128) for(x in 0 until 96) for(c in 0..3)
                reversed[(y*96+x)*4+c]=pixels[(y*96+95-x)*4+c]
            upload(id[1],reversed)
            val input=TextureFrame(info,id[0],TextureTarget.TEXTURE_2D,identity)
            GpuPixelBeautyRenderer(InstrumentationRegistry.getInstrumentation().targetContext).use { renderer ->
                fun colored(lip:MakeupColor,cheek:MakeupColor)=read(renderer.render(input,BeautySettings(adjustments=BeautyAdjustments(
                    lipstick=1f,blusher=1f,makeupColors=MakeupColors(lip,cheek))),face) as TextureFrame)
                val defaults=MakeupColors()
                val normal=colored(defaults.lipstick,defaults.blusher)
                val lipChanged=colored(MakeupColor(.2f,.3f,.8f),defaults.blusher)
                val cheekChanged=colored(defaults.lipstick,MakeupColor(.2f,.3f,.8f))
                assertFalse(normal.contentEquals(lipChanged));assertFalse(normal.contentEquals(cheekChanged))
                var lipPixels=0;var cheekPixels=0
                for(i in 0 until 96*128) {
                    val k=i*4
                    val lipDiff=(0..2).any {normal[k+it]!=lipChanged[k+it]}
                    val cheekDiff=(0..2).any {normal[k+it]!=cheekChanged[k+it]}
                    if(lipDiff)lipPixels++;if(cheekDiff)cheekPixels++
                    assertFalse("Changing colors must not cross independent regions",lipDiff&&cheekDiff)
                    assertEquals(173,lipChanged[k+3].toInt() and 255);assertEquals(173,cheekChanged[k+3].toInt() and 255)
                }
                assertTrue(lipPixels>20);assertTrue(cheekPixels>20)
                for(a in listOf(BeautyAdjustments(lipstick=1f),BeautyAdjustments(blusher=1f))) {
                    val settings=BeautySettings(adjustments=a)
                    assertSame(input,renderer.render(input,settings,null))
                    assertSame(input,renderer.render(input,settings,FaceObservation(face.bounds)))
                    GL.glEnable(GL.GL_BLEND)
                    GL.glBlendFuncSeparate(GL.GL_ONE,GL.GL_ZERO,GL.GL_DST_ALPHA,GL.GL_ONE_MINUS_DST_ALPHA)
                    GL.glBlendEquationSeparate(GL.GL_FUNC_REVERSE_SUBTRACT,GL.GL_FUNC_SUBTRACT)
                    GL.glViewport(2,3,15,16);GL.glEnable(GL.GL_SCISSOR_TEST)
                    GL.glColorMask(false,true,false,true);GL.glActiveTexture(GL.GL_TEXTURE6)
                    val output=renderer.render(input,settings,face) as TextureFrame
                    assertEquals(info,output.info)
                    assertTrue(GL.glIsEnabled(GL.GL_BLEND))
                    val state=IntArray(1)
                    for((key,value) in listOf(GL.GL_BLEND_SRC_RGB to GL.GL_ONE,GL.GL_BLEND_DST_RGB to GL.GL_ZERO,
                        GL.GL_BLEND_SRC_ALPHA to GL.GL_DST_ALPHA,GL.GL_BLEND_DST_ALPHA to GL.GL_ONE_MINUS_DST_ALPHA,
                        GL.GL_BLEND_EQUATION_RGB to GL.GL_FUNC_REVERSE_SUBTRACT,GL.GL_BLEND_EQUATION_ALPHA to GL.GL_FUNC_SUBTRACT)) {
                        GL.glGetIntegerv(key,state,0);assertEquals(value,state[0])
                    }
                    GL.glDisable(GL.GL_BLEND)
                    val viewport=IntArray(4);GL.glGetIntegerv(GL.GL_VIEWPORT,viewport,0)
                    assertArrayEquals(intArrayOf(2,3,15,16),viewport)
                    assertTrue(GL.glIsEnabled(GL.GL_SCISSOR_TEST))
                    val active=IntArray(1);GL.glGetIntegerv(GL.GL_ACTIVE_TEXTURE,active,0);assertEquals(GL.GL_TEXTURE6,active[0])
                    val mask=BooleanArray(4);GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK,mask,0)
                    assertArrayEquals(booleanArrayOf(false,true,false,true),mask)
                    GL.glDisable(GL.GL_SCISSOR_TEST);GL.glColorMask(true,true,true,true)
                    val bytes=read(output)
                    assertTrue("Makeup must alter RGB",bytes.indices.any {it%4!=3 && bytes[it]!=pixels[it]})
                    if(a.lipstick>0) {
                        // 染唇不能再像旧乘色一样整体压暗；检查实际Shader输出，不镜像实现公式。
                        var before=0.0;var after=0.0;var changed=0
                        for(pixel in 0 until 96*128) {
                            val k=pixel*4
                            if((0..2).any {bytes[k+it]!=pixels[k+it]}) {
                                fun luma(data:ByteArray)=.2126*(data[k].toInt() and 255)+
                                    .7152*(data[k+1].toInt() and 255)+.0722*(data[k+2].toInt() and 255)
                                before+=luma(pixels);after+=luma(bytes);changed++
                            }
                        }
                        assertTrue("Lip tint must cover valid mesh pixels",changed>0)
                        assertTrue("Lip tint must preserve source luminance",after>=before*.90)
                    }
                    if(a.blusher>0) {
                        var error=0.0;var changed=0;var warmth=0L
                        for(pixel in 0 until 96*128) {
                            val k=pixel*4
                            val dr=(bytes[k].toInt() and 255)-(pixels[k].toInt() and 255)
                            val dg=(bytes[k+1].toInt() and 255)-(pixels[k+1].toInt() and 255)
                            val db=(bytes[k+2].toInt() and 255)-(pixels[k+2].toInt() and 255)
                            if(dr!=0||dg!=0||db!=0){error+=kotlin.math.abs(.2126*dr+.7152*dg+.0722*db);warmth+=dr-dg;changed++}
                        }
                        assertTrue("Blush must visibly tint the cheeks",changed>20&&warmth>0)
                        assertTrue("Blush must preserve skin luminance instead of whitening it: mean error=${error/changed}",error/changed<1.5)
                    }
                    for(i in 0 until 96*128) assertEquals(173,bytes[i*4+3].toInt() and 255)
                    // 嘴唇内部中心与画面边角不能被口红/腮红染色。
                    for(pixel in listOf(0,86*96+48)) for(c in 0..3) assertEquals(pixels[pixel*4+c],bytes[pixel*4+c])
                    val again=renderer.render(input,settings,face) as TextureFrame
                    assertEquals(output.textureId,again.textureId)
                    val half=read(renderer.render(input,BeautySettings(adjustments=if(a.lipstick>0) a.copy(lipstick=.5f) else a.copy(blusher=.5f)),face) as TextureFrame)
                    fun difference(data:ByteArray)=data.indices.sumOf {kotlin.math.abs((data[it].toInt() and 255)-(pixels[it].toInt() and 255)).toLong()}
                    assertTrue(difference(half)>0);assertTrue(difference(half)<difference(bytes))
                    val flip=identity.copyOf().apply {this[0]=-1f;this[12]=1f}
                    val actual=read(renderer.render(TextureFrame(info,id[0],TextureTarget.TEXTURE_2D,flip),settings,face) as TextureFrame)
                    val expected=read(renderer.render(TextureFrame(info,id[1],TextureTarget.TEXTURE_2D,identity),settings,face) as TextureFrame)
                    actual.indices.forEach {assertTrue(kotlin.math.abs((actual[it].toInt() and 255)-(expected[it].toInt() and 255))<=2)}
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
                val invalid=points.toMutableList().apply {this[61]=FacePoint(-.1f,.5f)}
                assertSame(input,renderer.render(input,BeautySettings(adjustments=BeautyAdjustments(lipstick=1f)),FaceObservation(face.bounds,landmarks=invalid)))
                read(renderer.render(input,BeautySettings(smoothing=.3f,whitening=.2f,
                    adjustments=BeautyAdjustments(lipstick=.7f,blusher=.7f,bigEyes=.5f)),face) as TextureFrame)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally {GL.glDeleteTextures(2,id,0)}
    }

    /** 独立Canvas偶奇填充作为像素真值，检查非模板唇形、嘴角与高清覆盖。 */
    @Test fun fullLipContoursCoverInteriorWithoutLeakingAcrossEdges() = withEgl {
        // 真正从交付包移除口红PNG后验证完整着色链，防止只改Shader仍隐式依赖图片。
        try {
            InstrumentationRegistry.getInstrumentation().targetContext.assets.open("gpupixel/res/mouth.png").use {}
            fail("Procedural lip coloring must not package the legacy mouth texture")
        } catch (_: java.io.FileNotFoundException) { }
        val indices=intArrayOf(61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146,
            78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95)
        for((w,h) in listOf(96 to 128,640 to 480,1280 to 720)) for(closed in listOf(false,true)) {
            val points=MutableList(478){FacePoint(.5f,.5f)}
            val path=android.graphics.Path().apply {fillType=android.graphics.Path.FillType.EVEN_ODD}
            for(ring in 0..1) {
                for(i in 0 until 20) {
                    val angle=Math.PI+i*Math.PI/10
                    val x=.51+kotlin.math.cos(angle)*(if(ring==0).26 else .19)
                    val y=.62+kotlin.math.sin(angle)*(if(ring==0).125 else if(closed).001 else .044)+.05*(x-.51)
                    points[indices[ring*20+i]]=FacePoint(x.toFloat(),y.toFloat())
                    if(i==0)path.moveTo((x*w).toFloat(),(y*h).toFloat()) else path.lineTo((x*w).toFloat(),(y*h).toFloat())
                };path.close()
            }
            val mask=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(mask).drawPath(path,android.graphics.Paint().apply {color=android.graphics.Color.WHITE;isAntiAlias=false})
            val truth=IntArray(w*h);mask.getPixels(truth,0,w,0,0,w,h);mask.recycle()
            val ids=IntArray(1);GL.glGenTextures(1,ids,0)
            try {
                GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0])
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                val data=ByteBuffer.allocateDirect(w*h*4)
                repeat(w*h){data.put(150.toByte()).put(125.toByte()).put(100.toByte()).put(173.toByte())};data.flip()
                GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
                val input=TextureFrame(FrameInfo(w,h,1),ids[0],TextureTarget.TEXTURE_2D,
                    floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
                val face=FaceObservation(FaceBounds(.1f,.1f,.9f,.9f),landmarks=points)
                val pixels=GpuPixelBeautyRenderer(InstrumentationRegistry.getInstrumentation().targetContext).use { renderer ->
                    val settings=BeautySettings(adjustments=BeautyAdjustments(lipstick=1f))
                    val result=read(renderer.render(input,settings,face) as TextureFrame)
                    if(w==1280 && !closed) {
                        // 只在合成夹具测量已完成绘制；glFinish绝不加入生产路径。
                        val supported=renderer.setGpuTimingEnabled(true)
                        repeat(8){renderer.render(input,settings,face);GL.glFinish()}
                        val elapsed=mutableListOf<Double>();val gpu=mutableListOf<Double>()
                        repeat(40){
                            val start=System.nanoTime();renderer.render(input,settings,face);GL.glFinish()
                            elapsed+=(System.nanoTime()-start)/1e6
                            renderer.latestGpuExecutionNs()?.let {gpu+=it/1e6}
                        }
                        val sorted=elapsed.sorted()
                        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                            putString("stream","\nLIP_RENDER_720P samples=${elapsed.size} completedMedianMs=${sorted[sorted.size/2]} completedP95Ms=${sorted[(sorted.size*.95).toInt()]} gpuSupported=$supported gpuMedianMs=${gpu.sorted().let {if(it.isEmpty())null else it[it.size/2]}} disjoint=${renderer.gpuTimingDisjointCount()}\n")
                        })
                    }
                    result
                }
                var inside=0;var covered=0;var outside=0;var leaked=0
                for(y in 2 until h-2)for(x in 2 until w-2) {
                    val index=y*w+x;val expected=truth[index]!=0
                    // 排除两像素栅格边界，独立于Shader的一像素内侧羽化。
                    if((-2..2).any {dy->(-2..2).any {dx->(truth[(y+dy)*w+x+dx]!=0)!=expected}})continue
                    val changed=kotlin.math.abs((pixels[index*4].toInt() and 255)-150)>2 ||
                        kotlin.math.abs((pixels[index*4+1].toInt() and 255)-125)>2
                    if(expected){inside++;if(changed)covered++} else {outside++;if(changed)leaked++}
                    assertEquals(173,pixels[index*4+3].toInt() and 255)
                }
                assertTrue("interior $covered/$inside at ${w}x$h closed=$closed",inside>20 && covered.toDouble()/inside>.99)
                assertEquals("outer/inner leakage at ${w}x$h closed=$closed",0,leaked)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                    putString("stream","\nLIP_CONTOUR ${w}x$h closed=$closed interior=$covered/$inside outside=$leaked/$outside\n")
                })
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            } finally {GL.glDeleteTextures(1,ids,0)}
        }
    }

    @Test fun blushPreservesSkinToneLuminanceAndStrengthOrder() = withEgl {
        val points=MutableList(478){FacePoint(.5f,.5f)}.apply {
            this[33]=FacePoint(.2f,.25f);this[263]=FacePoint(.8f,.25f)
            this[50]=FacePoint(.25f,.45f);this[280]=FacePoint(.75f,.45f)
            this[152]=FacePoint(.5f,.95f);this[1]=FacePoint(.5f,.45f)
            this[234]=FacePoint(.06f,.5f);this[454]=FacePoint(.94f,.5f)
        }
        val face=FaceObservation(FaceBounds(.05f,.1f,.95f,.98f),landmarks=points)
        val id=IntArray(1);GL.glGenTextures(1,id,0)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            val input=TextureFrame(FrameInfo(96,128,1),id[0],TextureTarget.TEXTURE_2D,
                floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
            GpuPixelBeautyRenderer(InstrumentationRegistry.getInstrumentation().targetContext).use {renderer->
                for(rgb in listOf(intArrayOf(65,43,32),intArrayOf(150,125,100),intArrayOf(235,213,192),intArrayOf(250,248,245),intArrayOf(0,0,0),intArrayOf(255,255,255))){
                    val pixels=ByteArray(96*128*4){if(it%4==3)173.toByte()else rgb[it%4].toByte()}
                    GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
                    GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,96,128,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(pixels.size).apply{put(pixels);flip()})
                    var previous=-1L
                    for(strength in listOf(.25f,.6f,1f)){
                        val bytes=read(renderer.render(input,BeautySettings(adjustments=BeautyAdjustments(blusher=strength)),face) as TextureFrame)
                        var difference=0L;var changed=0;var error=0.0
                        for(k in bytes.indices step 4){
                            val dr=(bytes[k].toInt() and 255)-rgb[0];val dg=(bytes[k+1].toInt() and 255)-rgb[1];val db=(bytes[k+2].toInt() and 255)-rgb[2]
                            assertEquals(173,bytes[k+3].toInt() and 255)
                            if(dr!=0||dg!=0||db!=0){
                                assertTrue("Blush must add warmth without whitening all channels",dr>=0&&dg<=0&&db<=0)
                                error+=kotlin.math.abs(.2126*dr+.7152*dg+.0722*db);changed++
                            }
                            difference+=kotlin.math.abs(dr)+kotlin.math.abs(dg)+kotlin.math.abs(db)
                        }
                        if(rgb[0] in 1..254){assertTrue(changed>20);assertTrue(error/changed<1.5);assertTrue(difference>previous)}
                        else assertArrayEquals("Black and white must not gain false cheek patches",pixels,bytes)
                        if(rgb.contentEquals(intArrayOf(150,125,100))&&strength==1f){
                            // 对实际染色分布取主轴，圆形斑点不能靠降低强度通过。
                            var mass=0.0;var sx=0.0;var sy=0.0;var sxx=0.0;var syy=0.0;var sxy=0.0
                            for(y in 0 until 128)for(x in 0 until 48){
                                val k=(y*96+x)*4
                                val weight=(0..2).sumOf{kotlin.math.abs((bytes[k+it].toInt() and 255)-rgb[it])}.toDouble()
                                mass+=weight;sx+=x*weight;sy+=y*weight;sxx+=x*x*weight;syy+=y*y*weight;sxy+=x*y*weight
                            }
                            assertTrue(mass>0)
                            val xx=sxx/mass-sx*sx/(mass*mass);val yy=syy/mass-sy*sy/(mass*mass);val xy=sxy/mass-sx*sy/(mass*mass)
                            val spread=kotlin.math.sqrt((xx-yy)*(xx-yy)+4*xy*xy)
                            val aspect=kotlin.math.sqrt((xx+yy+spread)/(xx+yy-spread))
                            assertTrue("Blush must spread along the cheekbone, not form a circular spot: aspect=$aspect",aspect>1.8)
                            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","\nBLUSH_SHAPE principalAspect=$aspect\n")})
                        }
                        previous=difference
                        for(c in 0..3)assertEquals(pixels[c],bytes[c])
                        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","\nBLUSH rgb=${rgb.joinToString()} strength=$strength changed=$changed meanLumaError=${if(changed==0)0.0 else error/changed}\n")})
                    }
                }
            }
            assertEquals(GL.GL_NO_ERROR,GL.glGetError())
        } finally {GL.glDeleteTextures(1,id,0)}
    }

    /** 公开人像fixture经真实478点检测后贴妆；仅保存该fixture对照，绝不保存相机帧。 */
    @Test fun actualPortraitMakeupUsesDetectedLipAndCheekLandmarks() = withEgl {
        val inst=InstrumentationRegistry.getInstrumentation()
        val original=inst.context.assets.open("portrait.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }!!
        val bitmap=android.graphics.Bitmap.createScaledBitmap(original,320,400,true)
        if(bitmap!==original) original.recycle()
        val pixels=IntArray(320*400);bitmap.getPixels(pixels,0,320,0,0,320,400)
        val rgb=ByteArray(pixels.size*3);val rgba=ByteBuffer.allocateDirect(pixels.size*4)
        pixels.forEachIndexed {i,c ->
            rgb[i*3]=(c ushr 16).toByte();rgb[i*3+1]=(c ushr 8).toByte();rgb[i*3+2]=c.toByte()
            rgba.put(rgb[i*3]).put(rgb[i*3+1]).put(rgb[i*3+2]).put(255.toByte())
        };rgba.flip()
        val info=FrameInfo(320,400,33_000_000)
        val face=com.beautysdk.mediapipe.MediaPipeFaceAnalyzer(inst.targetContext).use {
            it.analyze(AnalysisImage(info,320,400,rgb)).single()
        }
        val id=IntArray(1);GL.glGenTextures(1,id,0)
        val compare=android.graphics.Bitmap.createBitmap(3200,400,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(compare);canvas.drawBitmap(bitmap,0f,0f,null)
        try {
            GL.glBindTexture(GL.GL_TEXTURE_2D,id[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,320,400,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,rgba)
            val input=TextureFrame(info,id[0],TextureTarget.TEXTURE_2D,floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f))
            GpuPixelBeautyRenderer(inst.targetContext).use {renderer ->
                val delivery=java.util.zip.ZipInputStream(inst.targetContext.assets.open("resource_delivery.zip"))
                val deliveredLashes=delivery.use { zip ->
                    val result=mutableListOf<BeautySettings>()
                    while(true) {
                        val entry=zip.nextEntry?:break
                        if(entry.name.contains("/makeup/lash_")&&entry.name.endsWith("/package.zip")) {
                            result+=com.beautysdk.resources.ResourceArchive.load(zip.readBytes()).applyTo(BeautySettings(),com.beautysdk.resources.ResourceCapabilities.androidGpuPixel())
                        }
                    }
                    result
                }
                assertEquals(3,deliveredLashes.size)
                val cases=listOf(BeautyAdjustments(lipstick=1f),BeautyAdjustments(blusher=1f),
                    BeautyAdjustments(slimNose=1f),BeautyAdjustments(chinLength=1f),BeautyAdjustments(mouthSize=1f),BeautyAdjustments(eyelashes=1f)).map {BeautySettings(adjustments=it)} +
                    listOf("natural","dense","cat").map {EffectResourcePack.loadZip(inst.context.assets.open("effect_packs/lash_$it.zip")).applyTo(BeautySettings())} + deliveredLashes
                // 同一输入先单开再叠加、再关闭；眼区/唇区应分别保持，不能残留上次uniform或材质。
                val lips=BeautySettings(adjustments=BeautyAdjustments(lipstick=.8f))
                val lipOnly=read(renderer.render(input,lips,face) as TextureFrame)
                for(lash in deliveredLashes) {
                    val eyeOnly=read(renderer.render(input,lash,face) as TextureFrame)
                    val together=read(renderer.render(input,lash.copy(adjustments=lash.adjustments.copy(lipstick=.8f)),face) as TextureFrame)
                    for(i in pixels.indices) {
                        val y=i/320
                        val expected=if(y>face.landmarks[1].y*400)lipOnly else eyeOnly
                        for(c in 0..3)assertTrue("Independent makeup region pixel=$i",kotlin.math.abs((expected[i*4+c].toInt() and 255)-(together[i*4+c].toInt() and 255))<=2)
                    }
                    assertArrayEquals(lipOnly,read(renderer.render(input,lips,face) as TextureFrame))
                }
                // 连续亚像素位移：静态可见不代表逐帧采样稳定，不允许整副图片睫毛掉到无效果。
                for(lash in deliveredLashes) for(step in 0..15) {
                    val dx=(step-7.5f)/320f
                    val moving=FaceObservation(face.bounds,landmarks=face.landmarks.map{FacePoint(it.x+dx,it.y)})
                    val bytes=read(renderer.render(input,lash,moving) as TextureFrame)
                    val changed=pixels.indices.count {i ->
                        val base=pixels[i]
                        kotlin.math.abs((bytes[i*4].toInt() and 255)-(base shr 16 and 255))+
                            kotlin.math.abs((bytes[i*4+1].toInt() and 255)-(base shr 8 and 255))+
                            kotlin.math.abs((bytes[i*4+2].toInt() and 255)-(base and 255))>24
                    }
                    assertTrue("Eyelashes must remain visible through subpixel motion step=$step changed=$changed",changed>12)
                }
                // 人工重叠夹具：把透明眼睑网格移到唇上，透明素材不能擦掉既有染唇。
                val overlap=face.landmarks.toMutableList()
                for(ids in listOf(intArrayOf(33,246,161,160,159,158,157,173,133,105,145),intArrayOf(263,466,388,387,386,385,384,398,362,334,374))) {
                    val center=overlap[ids[4]];val mouth=face.landmarks[14]
                    for(id in ids) {val v=overlap[id];overlap[id]=FacePoint(v.x+mouth.x-center.x,v.y+mouth.y-center.y)}
                }
                val transparent=EyeMakeup(StickerImage(2,2,IntArray(4)),(0..8).map{FacePoint(it/8f,.8f)},.5f)
                val overlappingFace=FaceObservation(face.bounds,landmarks=overlap)
                val expected=read(renderer.render(input,lips,overlappingFace) as TextureFrame)
                val actual=read(renderer.render(input,lips.copy(eyeMakeup=transparent,adjustments=lips.adjustments.copy(eyelashes=1f)),overlappingFace) as TextureFrame)
                assertArrayEquals("Transparent overlapping eyelashes must preserve lipstick",expected,actual)
                for((index,settings) in cases.withIndex()) {
                    val a=settings.adjustments
                    val output=renderer.render(input,settings,face) as TextureFrame
                    if(a.eyelashes>0f) {
                        assertSame(input,renderer.render(input,settings,null))
                        val invalid=face.landmarks.map {FacePoint(-1f,-1f)}
                        assertSame(input,renderer.render(input,settings,FaceObservation(face.bounds,landmarks=invalid)))
                        // 一侧缺点仍渲染另一侧，闭眼不产生无穷坐标或GL错误。
                        val partial=face.landmarks.toMutableList().apply {this[33]=FacePoint(-1f,-1f)}
                        assertNotSame(input,renderer.render(input,settings,FaceObservation(face.bounds,landmarks=partial)))
                        val closed=face.landmarks.toMutableList().apply {this[145]=this[159];this[374]=this[386]}
                        assertNotSame(input,renderer.render(input,settings,FaceObservation(face.bounds,landmarks=closed)))
                        assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                        // FBO复用，读回前重新渲染原始观测。
                        renderer.render(input,settings,face)
                    }
                    val bytes=read(output)
                    val colors=IntArray(pixels.size) {i -> android.graphics.Color.argb(255,bytes[i*4].toInt() and 255,
                        bytes[i*4+1].toInt() and 255,bytes[i*4+2].toInt() and 255)}
                    assertTrue("Actual portrait effect $index",colors.indices.count { colors[it]!=pixels[it] }>if(a.eyelashes>0f) 8 else 30)
                    if(a.eyelashes>0f) {
                        val visible=colors.indices.count { i ->
                            val x=i%320;val y=i/320
                            val eyeRegion=listOf(159,386).any { eye ->
                                val p=face.landmarks[eye]
                                kotlin.math.abs(x-p.x*320)<35 && y>p.y*400-30 && y<p.y*400+12
                            }
                            val c=colors[i];val b=pixels[i]
                            eyeRegion && kotlin.math.abs((c shr 16 and 255)-(b shr 16 and 255))+
                                kotlin.math.abs((c shr 8 and 255)-(b shr 8 and 255))+kotlin.math.abs((c and 255)-(b and 255))>24
                        }
                        inst.sendStatus(0,android.os.Bundle().apply{putString("stream","\nLASH_VISIBLE case=$index pixels=$visible material=${settings.eyeMakeup!=null}\n")})
                        if(settings.eyeMakeup!=null)assertTrue("Textured eyelashes must visibly change the eye region, case=$index count=$visible",visible>20)
                    }
                    assertEquals(pixels[0],colors[0])
                    val rendered=android.graphics.Bitmap.createBitmap(colors,320,400,android.graphics.Bitmap.Config.ARGB_8888)
                    canvas.drawBitmap(rendered,(index+1)*320f,0f,null);rendered.recycle()
                }
            }
            java.io.File(inst.targetContext.cacheDir,"gpupixel-makeup-fixture.png").outputStream().use {
                assertTrue(compare.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
            assertEquals(GL.GL_NO_ERROR,GL.glGetError())
        } finally {bitmap.recycle();compare.recycle();GL.glDeleteTextures(1,id,0)}
    }

    /** 自定义形变必须验证方向、局部旁路与90度等价性，不能只检查输出非空。 */
    @Test fun customShapesHaveSignedDirectionAndRotateWithRawFrames() = withEgl {
        val ids=IntArray(2);GL.glGenTextures(2,ids,0)
        val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        val info=FrameInfo(96,128,123,0,false)
        val pixels=ByteArray(96*128*4){i -> val x=i/4%96;val y=i/4/96
            when(i%4){0->30+x*2;1->15+y;2->80;else->173}.toByte()}
        fun upload(id:Int,w:Int,h:Int,data:ByteArray) {
            GL.glBindTexture(GL.GL_TEXTURE_2D,id)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
            GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(data.size).apply {put(data);flip()})
        }
        val points=MutableList(478){FacePoint(.5f,.5f)}
        points[168]=FacePoint(.5f,.2f);points[152]=FacePoint(.5f,.82f)
        points[1]=FacePoint(.5f,.42f);points[98]=FacePoint(.42f,.42f);points[327]=FacePoint(.58f,.42f)
        points[61]=FacePoint(.35f,.62f);points[291]=FacePoint(.65f,.62f)
        points[13]=FacePoint(.5f,.60f);points[14]=FacePoint(.5f,.64f)
        val face=FaceObservation(FaceBounds(.1f,.1f,.9f,.95f),landmarks=points)
        val rotated=ByteArray(pixels.size)
        for(y in 0 until 128) for(x in 0 until 96) for(c in 0..3)
            rotated[(x*128+127-y)*4+c]=pixels[(y*96+x)*4+c]
        try {
            upload(ids[0],96,128,pixels);upload(ids[1],128,96,rotated)
            val input=TextureFrame(info,ids[0],TextureTarget.TEXTURE_2D,identity)
            val rotatedInfo=FrameInfo(128,96,123,270,true)
            val rotatedFace=FaceObservation(face.bounds,landmarks=points.map {FacePoint(1f-it.y,it.x)})
            GpuPixelBeautyRenderer(InstrumentationRegistry.getInstrumentation().targetContext).use {renderer ->
                val cases=listOf(BeautyAdjustments(slimNose=1f),BeautyAdjustments(chinLength=1f),
                    BeautyAdjustments(chinLength=-1f),BeautyAdjustments(mouthSize=1f),BeautyAdjustments(mouthSize=-1f))
                for((index,a) in cases.withIndex()) {
                    val settings=BeautySettings(adjustments=a)
                    assertSame(input,renderer.render(input,settings,null))
                    assertSame(input,renderer.render(input,settings,FaceObservation(face.bounds)))
                    val output=renderer.render(input,settings,face) as TextureFrame
                    assertEquals(info,output.info)
                    val actual=read(output)
                    assertTrue("Shape $index must change RGB",actual.indices.any{it%4!=3 && actual[it]!=pixels[it]})
                    for(i in 0 until 96*128) assertEquals(173,actual[i*4+3].toInt() and 255)
                    for(c in 0..3) assertEquals(pixels[c],actual[c])
                    val probe=when(index){0->(54*96+52)*4;1,2->(105*96+48)*4+1;else->(79*96+55)*4}
                    val delta=(actual[probe].toInt() and 255)-(pixels[probe].toInt() and 255)
                    if(index==0 || index==2 || index==4) assertTrue("Expected positive sample shift $index: $delta",delta>0)
                    else assertTrue("Expected negative sample shift $index: $delta",delta<0)
                    // 实际旋转纹理与关键点，再比较逆旋转后的每个像素；覆盖非方形帧。
                    val turned=read(renderer.render(TextureFrame(rotatedInfo,ids[1],TextureTarget.TEXTURE_2D,identity),settings,rotatedFace) as TextureFrame)
                    for(y in 0 until 128) for(x in 0 until 96) for(c in 0..3) {
                        val expected=actual[(y*96+x)*4+c].toInt() and 255
                        val observed=turned[(x*128+127-y)*4+c].toInt() and 255
                        assertTrue("Rotation shape $index ($x,$y,$c): $expected/$observed",kotlin.math.abs(expected-observed)<=2)
                    }
                    assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                }
                // 鼻部退化仅跳过鼻部；下巴仍可独立工作，不能整张脸一起禁用。
                val invalid=points.toMutableList().apply {this[98]=this[327]}
                val invalidFace=FaceObservation(face.bounds,landmarks=invalid)
                assertSame(input,renderer.render(input,BeautySettings(adjustments=BeautyAdjustments(slimNose=1f)),invalidFace))
                assertNotSame(input,renderer.render(input,BeautySettings(adjustments=BeautyAdjustments(slimNose=1f,chinLength=.5f)),invalidFace))
                read(renderer.render(input,BeautySettings(smoothing=.3f,adjustments=BeautyAdjustments(
                    slimNose=.5f,chinLength=-.5f,mouthSize=.5f,lipstick=.3f,blusher=.3f)),face) as TextureFrame)
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
            }
        } finally {GL.glDeleteTextures(2,ids,0)}
    }

    /** 仅测试中读回像素断言，生产实时管线不读回。 */
    private fun read(frame: TextureFrame): ByteArray {
        val ids=IntArray(1); GL.glGenFramebuffers(1,ids,0)
        try {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,ids[0])
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,frame.textureId,0)
            assertEquals(GL.GL_FRAMEBUFFER_COMPLETE,GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))
            val data=ByteBuffer.allocateDirect(frame.info.width*frame.info.height*4)
            GL.glReadPixels(0,0,frame.info.width,frame.info.height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
            return ByteArray(data.capacity()).also { data.get(it) }
        } finally { GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0); GL.glDeleteFramebuffers(1,ids,0) }
    }

    /** 独立离屏上下文，不启动相机、不使用用户图片。 */
    private fun withEgl(block: () -> Unit) {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,0x40,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,
            EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,64,EGL14.EGL_HEIGHT,64,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,surface,surface,context))
        try { block() } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}
