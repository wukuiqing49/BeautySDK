package com.beautysdk.demo

import android.opengl.EGL14 as EGL
import android.opengl.EGLConfig
import android.opengl.GLES30 as GL
import com.beautysdk.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** 无相机图像的分辨率重建回归：同一renderer连续处理横竖屏及720p/1080p。 */
class ResolutionDeviceTest {
    @Test fun glReallocatesOutputAcrossResolutions() {
        val display=EGL.eglGetDisplay(EGL.EGL_DEFAULT_DISPLAY)
        check(EGL.eglInitialize(display,IntArray(2),0,IntArray(2),1))
        val configs=arrayOfNulls<EGLConfig>(1)
        check(EGL.eglChooseConfig(display,intArrayOf(EGL.EGL_RENDERABLE_TYPE,0x40,
            EGL.EGL_SURFACE_TYPE,EGL.EGL_PBUFFER_BIT,EGL.EGL_RED_SIZE,8,EGL.EGL_GREEN_SIZE,8,
            EGL.EGL_BLUE_SIZE,8,EGL.EGL_ALPHA_SIZE,8,EGL.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val context=EGL.eglCreateContext(display,configs[0],EGL.EGL_NO_CONTEXT,
            intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION,3,EGL.EGL_NONE),0)
        val surface=EGL.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL.EGL_WIDTH,1,EGL.EGL_HEIGHT,1,EGL.EGL_NONE),0)
        check(EGL.eglMakeCurrent(display,surface,surface,context))
        val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        try {
            GlBeautyRenderer().use { renderer ->
                listOf(640 to 480,1280 to 720,720 to 1280,1920 to 1080,640 to 480).forEachIndexed { i,(w,h) ->
                    val texture=IntArray(1);val fbo=IntArray(1)
                    GL.glGenTextures(1,texture,0);GL.glGenFramebuffers(1,fbo,0)
                    try {
                        GL.glBindTexture(GL.GL_TEXTURE_2D,texture[0])
                        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
                        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
                        GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,w,h,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,null)
                        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo[0])
                        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,texture[0],0)
                        assertEquals(GL.GL_FRAMEBUFFER_COMPLETE,GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))
                        GL.glViewport(0,0,w,h);GL.glClearColor(.2f,.4f,.6f,1f);GL.glClear(GL.GL_COLOR_BUFFER_BIT)
                        val info=FrameInfo(w,h,i.toLong())
                        val output=renderer.render(TextureFrame(info,texture[0],TextureTarget.TEXTURE_2D,identity),
                            BeautySettings(whitening=.2f),null) as TextureFrame
                        assertEquals(info,output.info)
                        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,fbo[0])
                        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,output.textureId,0)
                        assertEquals(GL.GL_FRAMEBUFFER_COMPLETE,GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER))
                        val pixel=ByteBuffer.allocateDirect(4)
                        // 读取新的右下角，能发现沿用旧尺寸FBO导致的越界/未绘制问题。
                        GL.glReadPixels(w-1,h-1,1,1,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,pixel)
                        assertEquals(255,pixel.get(3).toInt() and 255)
                        assertTrue((pixel.get(0).toInt() and 255)>51)
                        assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                    } finally {
                        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER,0)
                        GL.glDeleteFramebuffers(1,fbo,0);GL.glDeleteTextures(1,texture,0)
                    }
                }
            }
        } finally {
            EGL.eglMakeCurrent(display,EGL.EGL_NO_SURFACE,EGL.EGL_NO_SURFACE,EGL.EGL_NO_CONTEXT)
            EGL.eglDestroySurface(display,surface);EGL.eglDestroyContext(display,context);EGL.eglTerminate(display)
        }
    }
}
