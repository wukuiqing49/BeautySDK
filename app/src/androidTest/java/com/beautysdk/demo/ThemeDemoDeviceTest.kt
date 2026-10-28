package com.beautysdk.demo

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.CheckBox
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview
import com.beautysdk.resources.ResourceCategory
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 旧主题经统一资源入口迁移后，实际交给同一相机配置，不再验证已退居兼容层的旧模型。 */
class ThemeDemoDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
 @Test fun importReopenRejectAndClearCompleteTheme() {
  val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
  val state=File(context.filesDir,"beauty_resources/user-state.json");val backup=state.takeIf{it.exists()}?.readBytes()
  val source=File(context.cacheDir,"theme-demo-regression.zip")
  var activity:CameraTestActivity?=null;lateinit var model:ResourceCenterModel
  fun close(){inst.runOnMainSync {activity?.finish()};inst.waitForIdleSync();activity=null}
  fun waitFor(check:(ResourceCenterModel.Ui)->Boolean):ResourceCenterModel.Ui {
   val deadline=System.nanoTime()+15_000_000_000L
   while(System.nanoTime()<deadline){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync {ui=model.ui.value};ui?.let{if(!it.busy&&check(it))return it};Thread.sleep(25)}
   error("Theme state did not settle")
  }
  fun open(){activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity;inst.runOnMainSync {model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java]};waitFor{it.settings!=null}}
  try {
   inst.context.assets.open("theme_packs/full_theme.zip").use{input->source.outputStream().use(input::copyTo)}
   val id=com.beautysdk.resources.ResourceArchive.load(com.beautysdk.resources.LegacyResourceAdapter.upgrade(source.readBytes())).descriptor.id
   open();inst.runOnMainSync {model.import(Uri.fromFile(source))}
   val loaded=waitFor{it.installed.any{r->r.descriptor.id==id}}
   val theme=loaded.installed.first{it.descriptor.id==id&&it.descriptor.category==ResourceCategory.THEME}
   inst.runOnMainSync {model.select(theme)}
   waitFor{it.selection["theme"]==theme.hash&&it.settings?.stickerPack?.layers?.isNotEmpty()==true&&it.settings?.scene?.isIdentity==false}
   val frames=java.util.concurrent.CountDownLatch(1)
   inst.runOnMainSync {val p=activity!!.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview);val old=p.onImage;p.onImage={old(it);if(p.options.effects.themeSettings?.stickerPack?.layers?.isNotEmpty()==true)frames.countDown()};activity!!.findViewById<CheckBox>(R.id.camera_beauty).isChecked=false}
   waitFor{!it.enabled&&it.settings?.smoothing==0f&&it.settings?.stickerPack?.layers?.isNotEmpty()==true}
   assertTrue("Selected theme must reach live render",frames.await(15,java.util.concurrent.TimeUnit.SECONDS))
   close();open();assertEquals(theme.hash,waitFor{it.settings!=null}.selection["theme"])
   source.writeText("not a theme zip");inst.runOnMainSync {model.import(Uri.fromFile(source))}
   assertEquals(theme.hash,waitFor{it.error!=null}.selection["theme"])
   inst.runOnMainSync {model.resetDefaults()};waitFor{it.selection["theme"]==null&&it.error==null}
   close();open();assertNull(waitFor{it.settings!=null}.selection["theme"])
  } finally {close();source.delete();if(backup==null)state.delete()else state.writeBytes(backup)}
 }
}
