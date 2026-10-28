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
                for(a in listOf(BeautyAdjustments(lipstick=1f),BeautyAdjustments(blusher=1f))) {
                    val settings=BeautySettings(adjustments=a)
                    assertSame(input,renderer.render(input,settings,null))
                    assertSame(input,renderer.render(input,settings,FaceObservation(face.bounds)))
                    GL.glViewport(2,3,15,16);GL.glEnable(GL.GL_SCISSOR_TEST)
                    GL.glColorMask(false,true,false,true);GL.glActiveTexture(GL.GL_TEXTURE6)
                    val output=renderer.render(input,settings,face) as TextureFrame
                    assertEquals(info,output.info)
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
                val cases=listOf(BeautyAdjustments(lipstick=1f),BeautyAdjustments(blusher=1f),
                    BeautyAdjustments(slimNose=1f),BeautyAdjustments(chinLength=1f),BeautyAdjustments(mouthSize=1f),BeautyAdjustments(eyelashes=1f)).map {BeautySettings(adjustments=it)} +
                    listOf("natural","dense","cat").map {EffectResourcePack.loadZip(inst.context.assets.open("effect_packs/lash_$it.zip")).applyTo(BeautySettings())}
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
