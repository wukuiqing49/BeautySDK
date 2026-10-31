package com.beautysdk.demo
import android.opengl.EGL14
import android.opengl.GLES30 as GL
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.*

class GestureEffectsDeviceTest {
 @get:org.junit.Rule val sceneBackup=LabSceneBackup()
 private fun files():MutableMap<String,ByteArray> {val result=linkedMapOf<String,ByteArray>();InstrumentationRegistry.getInstrumentation().context.assets.open("gesture_effects.zip").use {input->ZipInputStream(input).use {z->while(true){val e=z.nextEntry?:break;result[e.name]=z.readBytes()}}};return result}
 private fun archive(files:Map<String,ByteArray>):ByteArray {val out=java.io.ByteArrayOutputStream();ZipOutputStream(out).use{z->files.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}};return out.toByteArray()}
 @Test fun actualWebExportParsesBothModesAndPreservesPlacement() {
  val p=GestureEffectArchive.loadZip(archive(files()).inputStream());assertEquals(2,p.rules.size)
  val animation=p.rules.single{it.animated};assertEquals(GestureKind.VICTORY,animation.gesture);assertEquals(36,animation.frameCount);assertEquals(1800,animation.durationMs)
  assertTrue(animation.image.copyArgb().any{it ushr 24>0})
  val sticker=p.rules.single{!it.animated};assertEquals(GestureKind.THUMB_UP,sticker.gesture);assertEquals(.18f,sticker.x);assertEquals(.25f,sticker.width)
 }
 @Test fun combinedPixelBudgetRejectsBeforeBitmapDecodeAndAcceptsBoundary() {
  val bytes=archive(files())
  val pack=GestureEffectArchive.loadZip(bytes.inputStream())
  val pixels=pack.rules.map{it.image}.distinct().sumOf{it.width.toLong()*it.height}
  assertEquals(pack.rules.size,GestureEffectArchive.loadZip(bytes.inputStream(),pixels).rules.size)
  for(budget in listOf(0L,pixels-1)) {
   val error=assertThrows(IllegalArgumentException::class.java){GestureEffectArchive.loadZip(bytes.inputStream(),budget)}
   assertEquals("GESTURE_PIXEL_BUDGET",error.message)
  }
  // 第一个包已占用预算时，第二个包必须使用剩余预算，不能先按单包上限解码。
  assertThrows(IllegalArgumentException::class.java){GestureEffectArchive.loadZip(bytes.inputStream(),pixels/2)}
 }
 @Test fun malformedManifestAndArchiveRejectWithoutFallback() {
  val original=files()
  val mutations:List<(JSONObject)->Unit> = listOf(
   {it.put("extra",true)}, {it.put("version","1")},
   {it.getJSONArray("rules").getJSONObject(0).put("gesture","WAVE")},
   {it.getJSONArray("rules").getJSONObject(0).put("durationMs",1799)},
   {it.getJSONArray("rules").getJSONObject(0).put("width","0.6")},
   {it.getJSONArray("rules").getJSONObject(0).put("image","../asset_0.png")},
   {it.getJSONArray("rules").getJSONObject(1).put("gesture","VICTORY")},
   {it.getJSONArray("rules").getJSONObject(0).put("frameWidth",193)})
  for(mutate in mutations){val f=original.toMutableMap();val json=JSONObject(String(f.getValue("gestures.json")));mutate(json);f["gestures.json"]=json.toString().toByteArray();assertThrows(Exception::class.java){GestureEffectArchive.loadZip(archive(f).inputStream())}}
  val extra=original.toMutableMap();extra["unused.png"]=byteArrayOf(1);assertThrows(Exception::class.java){GestureEffectArchive.loadZip(archive(extra).inputStream())}
 }
 private fun event(ms:Long)=GestureEvent(1,GestureKind.VICTORY,GestureEventPhase.TRIGGER,ms*1_000_000,emptyList(),FacePoint(.5f,.5f),GestureSource.MODEL,.9f)
 @Test fun realGpuAlphaFrameAdvanceExpiryAndUprightPositions()=withEgl {
  val ids=IntArray(1);GL.glGenTextures(1,ids,0)
  try {GL.glBindTexture(GL.GL_TEXTURE_2D,ids[0]);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR);GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
   val data=ByteBuffer.allocateDirect(64*64*4);repeat(64*64){data.put(0).put(0).put(255.toByte()).put(255.toByte())};data.flip();GL.glTexImage2D(GL.GL_TEXTURE_2D,0,GL.GL_RGBA,64,64,0,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,data)
   val identity=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
   val rule=GestureEffectRule("test",GestureKind.VICTORY,StickerImage(2,1,intArrayOf(0x80ff0000.toInt(),0xff00ff00.toInt())),true,1,1,2,2,2,1000,.25f,.25f,.25f)
   for(rotation in listOf(0,90,180,270))for(mirror in listOf(false,true)){
    val player=GestureEffectPlayer();player.configure(GestureEffectPack(listOf(rule)));player.accept(listOf(event(1000)),1_000_000_000)
    val delegate=object:BeautyRenderer{override fun render(frame:VideoFrame,settings:BeautySettings,face:FaceObservation?)=frame;override fun close()=Unit}
    GestureEffectRenderer(delegate,player).use {renderer->
     fun output(ms:Long)=renderer.render(TextureFrame(FrameInfo(64,64,ms*1_000_000,rotation,mirror),ids[0],TextureTarget.TEXTURE_2D,identity),BeautySettings(),null) as TextureFrame
     val first=read(output(1000));var red=0;var blue=0
     for(y in 0..63)for(x in 0..63){var ux=(x+.5f)/64;var uy=(y+.5f)/64;val ox=ux;when(rotation){90->{ux=1-uy;uy=ox};180->{ux=1-ux;uy=1-uy};270->{ux=uy;uy=1-ox}};if(mirror)ux=1-ux
      val i=(y*64+x)*4;val r=first[i].toInt() and 255;val b=first[i+2].toInt() and 255
      if(ux in .17f.. .33f&&uy in .17f.. .33f){assertTrue("alpha r=$r",r in 126..130);assertTrue(b in 125..129);red++}
      if(ux>.5f&&uy>.5f){assertEquals(0,r);assertEquals(255,b);blue++}}
     assertTrue(red>20&&blue>100)
     val second=read(output(1500));assertTrue(second.indices.filter{it%4==1}.any{(second[it].toInt() and 255)==255})
     assertEquals(ids[0],output(2000).textureId);assertEquals(GL.GL_NO_ERROR,GL.glGetError())
    }
   }
  }finally{GL.glDeleteTextures(1,ids,0)}
 }
 @Test fun importRequiresApplyAndRestoresAcrossReopening() {
  val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext;val folder=java.io.File(context.filesDir,"beauty_resources")
  val paths=listOf("gesture-effects.zip","pending-gesture-effects.zip").map{java.io.File(folder,it)};val backups=paths.map{it.takeIf(java.io.File::exists)?.readBytes()}
  val input=java.io.File(context.cacheDir,"gesture-import-test.zip").apply{writeBytes(archive(files()))}
  var activity:ResourceCenterActivity?=null;lateinit var model:ResourceCenterModel
  fun waitFor(check:(ResourceCenterModel.Ui)->Boolean):ResourceCenterModel.Ui {val end=System.nanoTime()+15_000_000_000;while(System.nanoTime()<end){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&check(it))return it};Thread.sleep(25)};error("Gesture UI did not settle")}
  fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
  fun open(){activity=inst.startActivitySync(android.content.Intent(context,ResourceCenterActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity;inst.runOnMainSync{model=androidx.lifecycle.ViewModelProvider(activity!!)[ResourceCenterModel::class.java]};waitFor{it.settings!=null}}
  try {open();inst.runOnMainSync{model.clearGestureEffects()};waitFor{it.gestureEffects==null&&!it.pendingGestureEffects}
   inst.runOnMainSync{model.import(android.net.Uri.fromFile(input))};assertNull(waitFor{it.pendingGestureEffects}.gestureEffects)
   inst.runOnMainSync{activity!!.findViewById<android.view.View>(R.id.resource_more).performClick()}
   inst.waitForIdleSync()
   var menuItem=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(context.getString(R.string.resources_gesture_apply)).single{it.text?.toString()==context.getString(R.string.resources_gesture_apply)}
   while(!menuItem.isClickable&&menuItem.parent!=null)menuItem=menuItem.parent
   assertTrue("Current apply menu item must be clickable",menuItem.isClickable)
   assertTrue(menuItem.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
   assertEquals(2,waitFor{it.gestureEffects!=null&&!it.pendingGestureEffects}.gestureEffects!!.rules.size)
   close();open();assertEquals(2,waitFor{it.gestureEffects!=null}.gestureEffects!!.rules.size)
   inst.runOnMainSync{model.clearGestureEffects()};waitFor{it.gestureEffects==null&&!it.pendingGestureEffects}
  }finally{close();paths.zip(backups).forEach{(file,bytes)->if(bytes==null)file.delete()else file.writeBytes(bytes)};input.delete()}
 }
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
