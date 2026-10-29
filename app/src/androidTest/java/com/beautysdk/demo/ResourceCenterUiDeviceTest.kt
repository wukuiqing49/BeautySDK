package com.beautysdk.demo

import android.content.Intent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** 独立资源页不创建相机；仅保存本页截图，恢复用户选择。 */
class ResourceCenterUiDeviceTest {
    @get:org.junit.Rule val sceneBackup=LabSceneBackup()
    /** 连续调参不重建卡片、不重复更换封面；末次输入与跨字段输入均能保存。 */
    @Test fun rapidParametersKeepResourceCardsAndCallerInputStable() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val file=File(context.filesDir,"beauty_resources/user-state.json");val previous=file.takeIf{it.exists()}?.readBytes()
        val activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity
        lateinit var model:ResourceCenterModel
        fun ready():ResourceCenterModel.Ui {
            val deadline=System.nanoTime()+15_000_000_000L
            while(System.nanoTime()<deadline){var value:ResourceCenterModel.Ui?=null;inst.runOnMainSync{value=model.ui.value};value?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);return it}};Thread.sleep(25)}
            error("Resource edits did not settle")
        }
        try {
            inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java]}
            ready()
            // 先展开可能已选中的主题，后续调参不应再产生资源目录变化。
            inst.runOnMainSync{model.parameters(mapOf("beauty.whitening" to .2f))};ready()
            lateinit var card:View;lateinit var before:ResourceCenterModel.Ui
            inst.runOnMainSync {
                before=model.ui.value!!;card=activity.findViewById<GridLayout>(R.id.resource_grid).getChildAt(0)
                repeat(40){model.parameters(mapOf("beauty.whitening" to it/100f))}
                val callerInput=mutableMapOf("reshape.slimFace" to .37f)
                model.parameters(callerInput);callerInput["reshape.slimFace"]=.91f
                model.enabled(false);model.enabled(true)
            }
            val after=ready()
            assertEquals(.39f,after.settings!!.whitening,0f)
            assertEquals(.37f,after.settings.adjustments.slimFace,0f)
            assertTrue(after.enabled)
            assertSame(before.installed,after.installed)
            before.previews.forEach{(hash,bytes)->assertSame("Cover must remain stable: $hash",bytes,after.previews[hash])}
            inst.runOnMainSync{assertSame("Slider edits must not rebuild resource cards",card,activity.findViewById<GridLayout>(R.id.resource_grid).getChildAt(0))}
            val saved=requireNotNull(com.beautysdk.resources.BeautyResourceStateStore(file).read())
            assertEquals(.39f,saved.parameters["beauty.whitening"]!!,0f)
            assertEquals(.37f,saved.parameters["reshape.slimFace"]!!,0f)
            // 模拟另一资源页删除/重新安装，主动刷新必须看到目录变化，而不是永久缓存。
            val resource=after.installed.first{it.descriptor.category==com.beautysdk.resources.ResourceCategory.BEAUTY&&it.hash !in after.selection.values}
            val root=File(context.filesDir,"beauty_resources")
            val bytes=File(root,"installed/${resource.hash}/package.zip").readBytes()
            val external=com.beautysdk.resources.BeautyResourceManager(root)
            try {
                external.uninstall(resource.hash)
                inst.runOnMainSync{model.refresh()}
                val removed=ready()
                assertTrue(removed.installed.none{it.hash==resource.hash})
                assertFalse(removed.previews.containsKey(resource.hash))
                inst.runOnMainSync{assertNull(activity.findViewById<GridLayout>(R.id.resource_grid).findViewWithTag<View>("resource_${resource.hash}"))}
            } finally {external.install(bytes.inputStream())}
            inst.runOnMainSync{model.refresh()}
            val restored=ready()
            assertTrue(restored.installed.any{it.hash==resource.hash})
            assertTrue(restored.previews.containsKey(resource.hash))
        } finally {
            inst.runOnMainSync{activity.finish()};inst.waitForIdleSync()
            if(previous==null)file.delete()else file.writeBytes(previous)
        }
    }
    @Test fun independentPageInstallsSelectsAndPersistsWithoutCamera() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val state=File(context.filesDir,"beauty_resources/user-state.json");val previous=state.takeIf{it.exists()}?.readBytes()
        var activity:ResourceCenterActivity?=null
        try {
            activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity
            val a=activity
            fun waitReady() {
                val end=System.nanoTime()+15_000_000_000L
                while(System.nanoTime()<end) {
                    var ready=false
                    inst.runOnMainSync {val ui=ViewModelProvider(a)[ResourceCenterModel::class.java].ui.value;ready=ui?.settings!=null && !ui.busy && ui.error==null}
                    if(ready)return
                    Thread.sleep(25)
                }
                fail("Resource page did not settle")
            }
            waitReady()
            val imported=File(context.cacheDir,"resource-import-test.zip")
            context.assets.open("resource_center/beauty_daily_1.zip").use {input->imported.outputStream().use(input::copyTo)}
            var selection=emptyMap<String,String?>()
            inst.runOnMainSync {val m=ViewModelProvider(a)[ResourceCenterModel::class.java];selection=m.ui.value!!.selection;m.import(android.net.Uri.fromFile(imported))}
            waitReady()
            inst.runOnMainSync {assertEquals("Import must not auto-enable a resource",selection,ViewModelProvider(a)[ResourceCenterModel::class.java].ui.value!!.selection)}
            imported.delete()
            var selectedHash=""
            inst.runOnMainSync {
                assertNull(a.findViewById<View>(R.id.camera_gpu_preview))
                assertEquals(View.VISIBLE,a.findViewById<View>(R.id.resource_management).visibility)
                val tabs=a.findViewById<LinearLayout>(R.id.resource_categories);assertEquals(9,tabs.childCount)
                tabs.getChildAt(2).performClick()
                val grid=a.findViewById<GridLayout>(R.id.resource_grid);assertTrue(grid.childCount>=3)
                selectedHash=grid.getChildAt(0).tag.toString().removePrefix("resource_")
                grid.getChildAt(0).performClick()
            }
            waitReady();assertTrue(state.readText().contains(selectedHash))
            inst.uiAutomation.takeScreenshot()?.let {screen ->
                File(context.cacheDir,"resource-center-page.png").outputStream().use {screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
            }
        } finally {
            inst.runOnMainSync {activity?.finish()};inst.waitForIdleSync()
            if(previous==null)state.delete()else state.writeBytes(previous)
        }
    }
    @Test fun deletingBuiltinDoesNotReinstallItOnReopen() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        var activity:ResourceCenterActivity?=null;lateinit var model:ResourceCenterModel
        var removed:com.beautysdk.resources.InstalledResource?=null
        val file=File(context.filesDir,"beauty_resources/user-state.json");val previous=file.takeIf{it.exists()}?.readBytes()
        fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
        fun waitFor(check:(ResourceCenterModel.Ui)->Boolean):ResourceCenterModel.Ui {val deadline=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<deadline){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&check(it))return it};Thread.sleep(25)};error("Resource page did not settle")}
        fun open(){activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity;inst.runOnMainSync{model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java]};waitFor{it.settings!=null}}
        try {
            open();val resource=waitFor{it.settings!=null}.installed.first{it.descriptor.id=="beauty_soft"};removed=resource
            inst.runOnMainSync{model.uninstall(resource)};waitFor{it.installed.none{r->r.hash==resource.hash}}
            close();open();assertTrue(waitFor{it.settings!=null}.installed.none{it.hash==resource.hash})
        } finally {
            close()
            removed?.let {r->com.beautysdk.resources.BeautyResourceManager(File(context.filesDir,"beauty_resources")).install(context.assets.open("resource_center/"+r.descriptor.id+"_"+r.descriptor.revision+".zip"))}
            if(previous==null)file.delete()else file.writeBytes(previous)
        }
    }

}
