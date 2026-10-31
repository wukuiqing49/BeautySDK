package com.beautysdk.demo

import android.content.Intent
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import com.beautysdk.camera.*
import com.beautysdk.demo.feature.app.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 仅在内存比较两路图像，日志只写相关性/几何数值，不保存任何相机像素。 */
class CameraContentAlignmentDeviceTest {
 @get:org.junit.Rule val backup=LabSceneBackup()
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private fun field(value:Any,name:String)=value.javaClass.getDeclaredField(name).apply{isAccessible=true}
 private data class Gray(val time:Long,val w:Int,val h:Int,val data:FloatArray)
 private fun gray(frame:RgbaFrame):Gray {
  val w=80;val h=(80.0*frame.info.height/frame.info.width).toInt()
  val input=frame.readOnlyPixels();val out=FloatArray(w*h)
  for(y in 0 until h) for(x in 0 until w){val offset=(y*frame.info.height/h)*frame.rowStrideBytes+(x*frame.info.width/w)*4
   out[y*w+x]=((input[offset].toInt() and 255)+(input[offset+1].toInt() and 255)+(input[offset+2].toInt() and 255))/3f}
  return Gray(frame.info.timestampNs,w,h,out)
 }
 private fun correlation(a:Gray,b:Gray,sx:Double,sy:Double):Double {
  var n=0;var sa=0.0;var sb=0.0;var saa=0.0;var sbb=0.0;var sab=0.0
  for(y in 3 until a.h-3 step 2)for(x in 3 until a.w-3 step 2){
   val xx=(.5+((x+.5)/a.w-.5)*sx)*b.w-.5;val yy=(.5+((y+.5)/a.h-.5)*sy)*b.h-.5
   if(xx<0||yy<0||xx>=b.w-1||yy>=b.h-1)continue
   val ix=xx.toInt();val iy=yy.toInt();val fx=xx-ix;val fy=yy-iy
   val av=a.data[y*a.w+x].toDouble()
   val bv=(b.data[iy*b.w+ix]*(1-fx)+b.data[iy*b.w+ix+1]*fx)*(1-fy)+(b.data[(iy+1)*b.w+ix]*(1-fx)+b.data[(iy+1)*b.w+ix+1]*fx)*fy
   n++;sa+=av;sb+=bv;saa+=av*av;sbb+=bv*bv;sab+=av*bv
  }
  val variance=(saa-sa*sa/n)*(sbb-sb*sb/n)
  return if(n<10||variance<1) -1.0 else (sab-sa*sb/n)/kotlin.math.sqrt(variance)
 }
 @Test fun actualAnalysisMatchesRenderedTextureAcrossResolutions() {
  val activity=inst.startActivitySync(Intent(inst.targetContext,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
  // 数值采样期间没有触摸事件，避免ColorOS息屏撤销GL上下文；只影响本次测试窗口。
  inst.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
  val preview=activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview)
  // 新提前投递会改变两路到达先后；只比较同一采样时间，避免运动/自动曝光造成跨帧误判。
  val cpu=java.util.concurrent.ConcurrentHashMap<Long,Gray>();val gpuResult=AtomicReference<Gray?>()
  val renderer=checkNotNull(field(preview,"gpu").get(preview))
  var bridge:GlTextureAnalysisBridge?=null
  fun gl(block:()->Unit){val done=CountDownLatch(1);val error=AtomicReference<Throwable?>();preview.queueEvent{try{block()}catch(t:Throwable){error.set(t)}finally{done.countDown()}};check(done.await(10,TimeUnit.SECONDS));error.get()?.let{throw it}}
  var restoreConsumer:(()->Unit)?=null
  try {
  val deadline=System.nanoTime()+15_000_000_000L
  var actualBridge:GlTextureAnalysisBridge?=null
  while(actualBridge==null&&System.nanoTime()<deadline){gl{actualBridge=field(renderer,"analysisBridge").get(renderer) as? GlTextureAnalysisBridge};Thread.sleep(40)}
  val activeBridge=checkNotNull(actualBridge)
  val consumerField=field(activeBridge,"onAnalysisFrame")
  @Suppress("UNCHECKED_CAST")
  val original=consumerField.get(activeBridge) as (RgbaFrame,FrameInfo)->Boolean
  restoreConsumer={consumerField.set(activeBridge,original)}
  gl {consumerField.set(activeBridge,{frame:RgbaFrame,info:FrameInfo ->
   val sample=gray(frame);cpu[sample.time]=sample
   if(cpu.size>12)cpu.keys.minOrNull()?.let(cpu::remove)
   original(frame,info)
  })}
   // 与真实输入使用相同采样尺寸，排除160/320双重降采样在高频纹理上的混叠误差。
   gl {bridge=GlTextureAnalysisBridge(maxSide=320,intervalNs=0,onAnalysisFrame={frame,_->gpuResult.set(gray(frame));true})}
   for(lens in CameraLens.entries){
    if(lens==CameraLens.BACK)inst.runOnMainSync{activity.findViewById<View>(R.id.camera_switch).performClick()}
    for((index,resolution) in DemoCaptureResolution.entries.withIndex()){
     inst.runOnMainSync{activity.findViewById<View>(R.id.camera_more).performClick();val popup=field(activity,"moreMenu").get(activity) as PopupMenu
      popup.menu.findItem(R.id.camera_resolution).subMenu!!.performIdentifierAction(listOf(R.id.camera_resolution_sd,R.id.camera_resolution_hd,R.id.camera_resolution_full_hd)[index],0);popup.dismiss()}
     Thread.sleep(1500);cpu.clear();gpuResult.set(null);gl{bridge!!.reset()}
     val end=System.nanoTime()+8_000_000_000L;var recorded=false
     while(System.nanoTime()<end && !recorded){
      gl {
       val source=field(renderer,"input").get(renderer) as? android.graphics.SurfaceTexture
       if(source!=null && source.timestamp>0){val crop=field(renderer,"crop").get(renderer) as android.graphics.Rect
        val info=FrameInfo(crop.width(),crop.height(),source.timestamp)
        bridge!!.submit(TextureFrame(info,field(renderer,"texture").getInt(renderer),TextureTarget.EXTERNAL_OES,(field(renderer,"normalize").get(renderer) as FloatArray).copyOf()),true)}
      }
      Thread.sleep(60)
      val a=gpuResult.get();val b=a?.let{cpu[it.time]}
      if(a!=null&&b!=null){
       val direct=correlation(a,b,1.0,1.0);var best=-2.0;var bx=0.0;var by=0.0
       for(ix in 8..24)for(iy in 8..24){val sx=ix/16.0;val sy=iy/16.0;val score=correlation(a,b,sx,sy);if(score>best){best=score;bx=sx;by=sy}}
       android.util.Log.i("BeautyContent","lens=$lens request=$resolution identity=$direct best=$best scaleX=$bx scaleY=$by timestampDeltaMs=${(a.time-b.time)/1e6}")
       assertTrue("Actual analysis must match visible content without rescaling: $lens/$resolution direct=$direct best=$best",direct>.985 && best-direct<.01)
       recorded=true
      }
     }
     assertTrue("No paired GPU/CPU frames",recorded)
    }
   }
  } finally {
   try {gl{bridge?.close();restoreConsumer?.invoke()}} finally {
    cpu.clear();gpuResult.set(null)
    // 即使GL清理断言失败，也先停止相机/模型再由Rule恢复私有文件。
    inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()
   }
  }
 }
}
