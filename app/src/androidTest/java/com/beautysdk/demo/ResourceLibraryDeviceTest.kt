package com.beautysdk.demo

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.databinding.ViewResourceCenterBinding
import com.beautysdk.resources.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** 新分类交付与旧包兼容；测试后恢复宿主设置，不采集摄像头图像。 */
class ResourceLibraryDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private fun bytes()=inst.context.assets.open("library_delivery.zip").use{it.readBytes()}
    private fun files():MutableMap<String,ByteArray> {val out=linkedMapOf<String,ByteArray>();ZipInputStream(bytes().inputStream()).use{z->while(true){val e=z.nextEntry?:break;out[e.name]=z.readBytes()}};return out}
    private fun rejected(action:()->Unit){try{action();fail("Invalid library accepted")}catch(e:IllegalArgumentException){}catch(e:java.util.zip.ZipException){}}
    @Test fun classifiedDeliveryInstallsAndComposesWithLanguageFallback(){
        val pack=ResourceDeliveryPack.read(bytes());val library=requireNotNull(pack.library)
        assertEquals("美颜",library.text("category.BEAUTY","fr-FR"))
        assertEquals("Beauty",library.text("category.BEAUTY","en-US"))
        assertEquals(15,library.categories.sumOf{it.effects.size})
        assertEquals(.47f,library.parameters(ResourceCategory.BEAUTY).first{it.id=="beauty.smoothing"}.default)
        assertTrue(library.resources.all{it.directory.contains('/')&&it.directory.contains("__")})
        val folder=File(context.cacheDir,"library-test-"+System.nanoTime())
        try{val manager=BeautyResourceManager(folder);val result=manager.installDelivery(bytes().inputStream());assertEquals(2,result.resources.size)
            BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel()).prepare(result.defaults.state).use{assertEquals(.47f,it.settings.smoothing)}
        }finally{folder.deleteRecursively()}
    }
    @Test fun rejectsMissingTranslationsUnknownParametersAndInvalidRanges(){
        fun edit(action:(JSONObject)->Unit){val json=JSONObject(requireNotNull(files()["library.json"]).toString(Charsets.UTF_8));action(json);rejected{BeautyResourceLibrary.parse(json.toString().toByteArray())}}
        edit{it.getJSONObject("localizations").getJSONObject("zh-CN").remove("category.BEAUTY")}
        edit{it.getJSONArray("categories").getJSONObject(0).getJSONArray("effects").getJSONObject(0).getJSONArray("parameters").getJSONObject(0).put("max",2)}
        edit{it.getJSONArray("categories").getJSONObject(0).getJSONArray("effects").getJSONObject(0).put("id","beauty.unknown")}
        edit{it.getJSONArray("categories").getJSONObject(0).getJSONArray("effects").getJSONObject(0).getJSONArray("parameters").getJSONObject(0).put("step",0)}
    }
    @Test fun rejectsTamperedExpandedFilesAndTraversalAndKeepsLegacyImport(){
        val files=files();val path=files.keys.first{it.endsWith("/effect.json")};files[path]="{}".toByteArray()
        rejected{ResourceDeliveryPack.read(ResourceArchive.write(files))}
        files["../bad.json"]="{}".toByteArray();rejected{ResourceDeliveryPack.read(ResourceArchive.write(files))}
        val old=inst.context.assets.open("resource_delivery.zip").use{it.readBytes()}
        assertNull(ResourceDeliveryPack.read(old).library)
    }
    @Test fun cameraUsesJsonNamesRangesAndParametersWithoutReplacingSceneControls(){
        val libraryFile=File(context.filesDir,"beauty_resources/library.json")
        val json=JSONObject(requireNotNull(files()["library.json"]).toString(Charsets.UTF_8))
        json.getJSONObject("localizations").getJSONObject("zh-CN").put("category.BEAUTY","自定义美颜").put("effect.beauty.smoothing","自定义磨皮")
        libraryFile.parentFile!!.mkdirs();libraryFile.writeText(json.toString())
        val activity=inst.startActivitySync(Intent(context,CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:ResourceCenterModel
        fun ready(check:(ResourceCenterModel.Ui)->Boolean){val end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end){var ok=false;inst.runOnMainSync{model.ui.value?.let{assertNull(it.error);ok=!it.busy&&it.settings!=null&&check(it)}};if(ok)return;Thread.sleep(25)};error("Camera JSON controls timeout")}
        try{
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java]};ready{it.library!=null}
            lateinit var panel:android.view.View
            inst.runOnMainSync{
                panel=activity.findViewById(R.id.camera_controls_panel)
                activity.findViewById<android.view.View>(R.id.camera_settings).performClick()
                panel.findViewById<android.view.View>(R.id.camera_adjust).performClick()
            };inst.waitForIdleSync()
            inst.runOnMainSync{
                val tabs=panel.findViewById<android.widget.LinearLayout>(R.id.effect_categories)
                assertEquals("自定义美颜",(tabs.getChildAt(0) as android.widget.TextView).text.toString())
                val bar=panel.findViewWithTag<SeekBar>("library_parameter_beauty.smoothing");assertTrue(bar.isShown)
                assertTrue(((bar.parent as android.view.ViewGroup).getChildAt(0) as android.widget.TextView).text.startsWith("自定义磨皮"))
                assertFalse(panel.findViewById<android.view.View>(R.id.beauty_section).isShown)
                ((bar.parent as android.view.ViewGroup).getChildAt(6) as android.widget.Button).performClick()
            };ready{it.settings!!.smoothing==.47f}
            inst.runOnMainSync{
                val preview=activity.findViewById<com.beautysdk.demo.feature.app.GpuBeautyPreview>(R.id.camera_gpu_preview)
                assertEquals(.47f,preview.options.effects.themeSettings!!.smoothing)
                panel.findViewById<android.widget.LinearLayout>(R.id.effect_categories).getChildAt(5).performClick()
                panel.findViewById<android.view.View>(R.id.camera_adjust).performClick()
                assertTrue(panel.findViewById<android.view.View>(R.id.background_controls).isShown)
            }
        }finally{inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()}
    }
    @Test fun jsonControlsApplyAndResetAndLibrarySurvivesReopen(){
        val folder=File(context.filesDir,"beauty_resources")
        val paths=listOf("library.json","pending-defaults.json","active-defaults.json","user-state.json").map{File(folder,it)}
        val backups=paths.map{it.takeIf(File::isFile)?.readBytes()}
        val input=File(context.cacheDir,"library-ui.zip").apply{writeBytes(bytes())}
        var activity:ResourceCenterActivity?=null;lateinit var model:ResourceCenterModel
        fun ready(predicate:(ResourceCenterModel.Ui)->Boolean={true}):ResourceCenterModel.Ui {val end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);if(predicate(it))return it}};Thread.sleep(25)};error("Library UI timeout")}
        fun open(){activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity;inst.runOnMainSync{model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java]};ready()}
        fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
        try{open();inst.runOnMainSync{model.import(Uri.fromFile(input))};ready{it.library!=null}
            inst.runOnMainSync{model.parameters(mapOf("beauty.smoothing" to .25f))};ready{it.settings!!.smoothing==.25f}
            lateinit var binding:ViewResourceCenterBinding;lateinit var bar:SeekBar
            inst.runOnMainSync{
                binding=ViewResourceCenterBinding.inflate(activity!!.layoutInflater);activity!!.setContentView(binding.root)
                ResourceCenterController(activity!!,binding,management=false){_,_->}.parameterMode(true)
                bar=binding.root.findViewWithTag("library_parameter_beauty.smoothing")
                assertEquals(100,bar.max);assertEquals(25,bar.progress)
                bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            ready{it.settings!!.smoothing>.25f}
            inst.runOnMainSync{assertSame(bar,binding.root.findViewWithTag<SeekBar>("library_parameter_beauty.smoothing"));val container=bar.parent as android.view.ViewGroup;(container.getChildAt(container.childCount-1) as android.widget.Button).performClick()}
            ready{it.settings!!.smoothing==.47f}
            inst.waitForIdleSync()
            inst.uiAutomation.waitForIdle(300,5000)
            inst.uiAutomation.takeScreenshot().also{bitmap->File(context.getExternalFilesDir(null),"library-controls.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            close();open();val restored=ready{it.library!=null};assertEquals(.47f,restored.settings!!.smoothing)
        }finally{close();paths.zip(backups).forEach{(file,data)->if(data==null)file.delete()else file.writeBytes(data)};input.delete()}
    }
}
