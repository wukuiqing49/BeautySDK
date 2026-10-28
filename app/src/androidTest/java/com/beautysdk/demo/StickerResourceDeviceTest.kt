package com.beautysdk.demo

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 使用公开内置素材及临时缓存测试资源闭环，不采集或保存人脸。 */
class StickerResourceDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun entries():LinkedHashMap<String,ByteArray> {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(app.assets.open("sticker_packs/round_glasses.zip")).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; entries[entry.name]=zip.readBytes(); zip.closeEntry() }
        }
        return entries
    }
    private fun zip(entries:Map<String,ByteArray>):ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name,bytes) ->
            zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun sixSamplesRoundTripEditedLayoutAndReusePixels() {
        for(id in StickerWorkshopViewModel.SAMPLE_IDS) {
            val resource=StickerResourcePack.loadZip(app.assets.open("sticker_packs/$id.zip"))
            assertEquals(id,resource.manifest.id)
            val old=resource.toStickerPack().layers.single()
            val layer=resource.manifest.layers.single().copy(widthInAnchorSpans=3.1f,offsetX=.2f,offsetY=-.3f,rotationDegrees=15f)
            val changed=resource.withManifest(StickerResourceManifest(id,resource.manifest.name,listOf(layer),resource.manifest.preview))
            assertSame(old.image,changed.toStickerPack().layers.single().image)
            val output=ByteArrayOutputStream();changed.writeZip(output)
            val loaded=StickerResourcePack.loadZip(ByteArrayInputStream(output.toByteArray()))
            assertEquals(layer,loaded.manifest.layers.single())
            assertArrayEquals(resource.previewPng(),loaded.previewPng())
            assertEquals(changed.manifest.toJson(),StickerResourceManifest.parse(changed.manifest.toJson()).toJson())
        }
    }

    @Test fun invalidPathsMissingImagesVersionsAndOversizedEntriesAreRejected() {
        val original=entries()
        fun rejects(changed:Map<String,ByteArray>) {
            try { StickerResourcePack.loadZip(ByteArrayInputStream(zip(changed)));fail("invalid resource accepted") }
            catch(expected:Exception) { assertNotNull(expected.message) }
        }
        rejects(original+ ("../escape.png" to byteArrayOf(1)))
        rejects(original-"sticker.png")
        rejects(original+ ("unused.png" to original.getValue("sticker.png")))
        rejects(original+ ("sticker.png" to ByteArray(4*1024*1024+1)))
        rejects(original+ ("sticker.png" to byteArrayOf(1,2,3)))
        val corrupted=original.getValue("preview.png").copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() }
        rejects(original+("preview.png" to corrupted))
        // 插入带正确CRC的APNG控制块，v1必须明确拒绝动画。
        val png=original.getValue("sticker.png")
        val data=byteArrayOf(0,0,0,1,0,0,0,0)
        val type="acTL".toByteArray()
        val crc=java.util.zip.CRC32().apply { update(type);update(data) }.value
        val chunk=java.nio.ByteBuffer.allocate(20).putInt(8).put(type).put(data).putInt(crc.toInt()).array()
        val animated=png.copyOfRange(0,33)+chunk+png.copyOfRange(33,png.size)
        rejects(original+("sticker.png" to animated))
        fun manifest(change:(JSONObject)->Unit):Map<String,ByteArray> {
            val root=JSONObject(original.getValue("manifest.json").toString(Charsets.UTF_8));change(root)
            return original+("manifest.json" to root.toString().toByteArray(Charsets.UTF_8))
        }
        rejects(manifest { it.put("schemaVersion",2) })
        rejects(manifest { it.put("unknownField",true) })
        rejects(manifest { it.getJSONArray("layers").getJSONObject(0).put("anchor","NOSE") })
        rejects(manifest { it.getJSONArray("layers").getJSONObject(0).put("widthInAnchorSpans","2.5") })
        rejects(manifest { it.getJSONArray("layers").getJSONObject(0).put("rotationDegrees",181) })
        // ZIP重复条目：改写同长度根文件名，保留两条本地头，读取器必须拒绝。
        val duplicate=zip(linkedMapOf("manifest.json" to original.getValue("manifest.json"),
            "sticker.png" to original.getValue("sticker.png"),"another.png" to original.getValue("sticker.png")))
        val from="another.png".toByteArray();val to="sticker.png".toByteArray()
        for(index in 0..duplicate.size-from.size) if(from.indices.all { duplicate[index+it]==from[it] })
            to.copyInto(duplicate,index)
        try { StickerResourcePack.loadZip(ByteArrayInputStream(duplicate));fail("duplicate accepted") }
        catch(expected:Exception) { assertNotNull(expected.message) }
    }

    @Test fun workshopUiEditsExportsImportsAndRetainsStateOnRecreation() {
        val draft=File(app.filesDir,"sticker_workshop/draft.zip")
        val oldDraft=if(draft.exists()) draft.readBytes() else null
        var activity=instrumentation.startActivitySync(android.content.Intent(app,CameraTestActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraTestActivity
        lateinit var model:StickerWorkshopViewModel
        lateinit var panel:View
        val source=File(app.cacheDir,"workshop-test-source.zip")
        val exported=File(app.cacheDir,"workshop-test-export.zip")
        val json=File(app.cacheDir,"workshop-test-export.json")
        fun await(check:(StickerWorkshopState)->Boolean):StickerWorkshopState {
            val deadline=System.nanoTime()+8_000_000_000L
            do {
                var state:StickerWorkshopState?=null
                instrumentation.runOnMainSync { state=model.state.value }
                state?.let { if(check(it)) return it }
                Thread.sleep(20)
            } while(System.nanoTime()<deadline)
            error("Workshop state timeout")
        }
        try {
            instrumentation.runOnMainSync {
                model=ViewModelProvider(activity)[StickerWorkshopViewModel::class.java]
                // 设置内容会移入BottomSheet的独立Window，保留内容引用后再打开。
                panel=activity.findViewById(R.id.controls_scroll)
                activity.findViewById<View>(R.id.camera_workshop).performClick()
            }
            await { !it.busy }
            instrumentation.runOnMainSync { panel.findViewById<View>(R.id.workshop_load_sample).performClick() }
            val initial=await { it.resource!=null && !it.busy }
            assertNull(initial.error);assertTrue(initial.enabled)
            instrumentation.runOnMainSync {
                panel.findViewById<View>(R.id.workshop_tab_layout).performClick()
                assertEquals(View.VISIBLE,panel.findViewById<View>(R.id.workshop_editor).visibility)
                assertEquals(View.GONE,panel.findViewById<View>(R.id.workshop_material_section).visibility)
                assertEquals(View.GONE,panel.findViewById<View>(R.id.workshop_parameter_rotation).visibility)
                val width=panel.findViewById<SeekBar>(R.id.workshop_width)
                assertTrue(width.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,310f) }))
            }
            val edited=await { it.resource?.manifest?.layers?.first()?.widthInAnchorSpans==3.2f }
            assertSame(initial.renderPack!!.layers[0].image,edited.renderPack!!.layers[0].image)
            instrumentation.runOnMainSync {
                model.prepareExport(false);model.export(Uri.fromFile(exported))
            }
            await { !it.busy && it.exported }
            val reloaded=StickerResourcePack.loadZip(exported.inputStream())
            assertEquals(3.2f,reloaded.manifest.layers.single().widthInAnchorSpans,0f)
            instrumentation.runOnMainSync { model.prepareExport(true);model.export(Uri.fromFile(json)) }
            await { !it.busy && it.exported }
            assertEquals(3.2f,StickerResourceManifest.parse(json.readText()).layers.single().widthInAnchorSpans,0f)
            app.assets.open("sticker_packs/blue_top_hat.zip").use { input -> source.outputStream().use(input::copyTo) }
            instrumentation.runOnMainSync { model.importPack(Uri.fromFile(source)) }
            assertEquals("blue_top_hat",await { !it.busy && it.resource?.manifest?.id=="blue_top_hat" }.resource!!.manifest.id)
            instrumentation.runOnMainSync {
                panel.findViewById<CheckBox>(R.id.workshop_enabled).isChecked=false
            }
            assertNull(await { !it.enabled }.renderPack)
            // 无效导入不能丢弃当前已加载资源。
            source.writeText("invalid zip")
            instrumentation.runOnMainSync { model.importPack(Uri.fromFile(source)) }
            val invalid=await { !it.busy && it.error!=null }
            assertEquals("blue_top_hat",invalid.resource!!.manifest.id)
            // 真正重建Activity，确认编辑状态通过ViewModelStore保留。
            val monitor=instrumentation.addMonitor(CameraTestActivity::class.java.name,null,false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity=instrumentation.waitForMonitorWithTimeout(monitor,8000) as CameraTestActivity
                instrumentation.runOnMainSync {
                    assertSame(model,ViewModelProvider(activity)[StickerWorkshopViewModel::class.java])
                    assertEquals("blue_top_hat",model.state.value!!.resource!!.manifest.id)
                    assertFalse(model.state.value!!.enabled)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            Thread.sleep(1000) // 本轮小素材的防抖写入完成后恢复原草稿，不覆盖用户数据。
            if(oldDraft!=null) draft.writeBytes(oldDraft) else draft.delete()
            source.delete();exported.delete();json.delete()
        }
    }

    @Test fun privateDraftRestoresDisabledAndLateLoadCannotUndoClear() {
        val file=File(app.filesDir,"sticker_workshop/regression_draft.zip")
        val stores=mutableListOf<androidx.lifecycle.ViewModelStore>()
        fun model():StickerWorkshopViewModel {
            lateinit var result:StickerWorkshopViewModel
            instrumentation.runOnMainSync {
                val store=androidx.lifecycle.ViewModelStore();stores.add(store)
                result=StickerWorkshopViewModel(app.applicationContext as android.app.Application,"regression_draft.zip")
                store.put("isolated",result)
            }
            return result
        }
        fun await(check:()->Boolean) {
            val deadline=System.nanoTime()+8_000_000_000L
            while(System.nanoTime()<deadline) { if(check()) return;Thread.sleep(20) }
            error("Draft timeout")
        }
        try {
            val first=model()
            instrumentation.runOnMainSync { first.loadSample(0);first.setEnabled(false) }
            await { first.state.value?.let { !it.busy && it.resource!=null }==true }
            instrumentation.runOnMainSync {
                assertFalse(first.state.value!!.enabled)
                first.updateLayer(first.state.value!!.resource!!.manifest.layers.single().copy(widthInAnchorSpans=3.75f))
                first.flushDraft()
            }
            await { runCatching { StickerResourcePack.loadZip(file.inputStream()).manifest.layers.single().widthInAnchorSpans==3.75f }.getOrDefault(false) }
            val second=model()
            instrumentation.runOnMainSync { second.restoreDraft() }
            await { second.state.value?.let { !it.busy && it.resource!=null }==true }
            instrumentation.runOnMainSync {
                assertEquals(3.75f,second.state.value!!.resource!!.manifest.layers.single().widthInAnchorSpans,0f)
                assertFalse(second.state.value!!.enabled);assertNull(second.state.value!!.renderPack)
                second.setEnabled(true)
            }
            // 新建ViewModel模拟退出页面后的再次进入，素材与开启状态都应恢复。
            val third=model()
            instrumentation.runOnMainSync { third.restoreDraft() }
            await { third.state.value?.let { !it.busy && it.resource!=null }==true }
            instrumentation.runOnMainSync {
                assertTrue(third.state.value!!.enabled);assertNotNull(third.state.value!!.renderPack)
            }
            val fourth=model()
            instrumentation.runOnMainSync { fourth.restoreDraft();fourth.setEnabled(false) }
            await { fourth.state.value?.let { !it.busy && it.resource!=null }==true }
            instrumentation.runOnMainSync { assertFalse(fourth.state.value!!.enabled) }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            app.getSharedPreferences("sticker_workshop_state",0).edit().remove("regression_draft.zip.enabled").commit()
            file.delete()
        }
    }
    @Test fun saveReloadUsesPersistedImagesAndEditedManifest() {
        val file=File(app.filesDir,"sticker_workshop/save_reload_regression.zip")
        val stores=mutableListOf<androidx.lifecycle.ViewModelStore>()
        fun model():StickerWorkshopViewModel {
            lateinit var result:StickerWorkshopViewModel
            instrumentation.runOnMainSync {
                val store=androidx.lifecycle.ViewModelStore();stores.add(store)
                result=StickerWorkshopViewModel(app.applicationContext as android.app.Application,"save_reload_regression.zip")
                store.put("isolated",result)
            }
            return result
        }
        fun await(check:()->Boolean) {
            val deadline=System.nanoTime()+8_000_000_000L
            while(System.nanoTime()<deadline) { if(check()) return;Thread.sleep(20) }
            error("Save and load timeout")
        }
        try {
            val first=model()
            instrumentation.runOnMainSync { first.loadSample(0) }
            await { first.state.value?.let { !it.busy && it.resource!=null }==true }
            val before=first.state.value!!.resource!!
            val png=before.previewPng()
            instrumentation.runOnMainSync {
                first.updateLayer(before.manifest.layers.single().copy(widthInAnchorSpans=3.2f,offsetY=-.4f))
                first.saveAndReload()
                // 保存期间退出不得把旧的内存快照重新排到保存后写回。
                first.flushDraft()
            }
            await { first.state.value?.savedAndLoaded==true }
            instrumentation.runOnMainSync {
                val state=first.state.value!!
                assertNotSame(before,state.resource)
                assertTrue(state.enabled);assertNotNull(state.renderPack)
                assertArrayEquals(png,state.resource!!.previewPng())
                assertEquals(3.2f,state.resource!!.manifest.layers.single().widthInAnchorSpans,0f)
            }
            val disk=StickerResourcePack.loadZip(file.inputStream())
            assertEquals(-.4f,disk.manifest.layers.single().offsetY,0f)
            val second=model()
            instrumentation.runOnMainSync { second.restoreDraft() }
            await { second.state.value?.let { !it.busy && it.resource!=null }==true }
            instrumentation.runOnMainSync {
                assertTrue(second.state.value!!.enabled)
                assertEquals(3.2f,second.state.value!!.resource!!.manifest.layers.single().widthInAnchorSpans,0f)
                first.saveAndReload();first.setEnabled(false)
            }
            await { first.state.value?.let { !it.busy && it.savedAndLoaded }==true }
            instrumentation.runOnMainSync { assertFalse(first.state.value!!.enabled) }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            app.getSharedPreferences("sticker_workshop_state",0).edit().remove("save_reload_regression.zip.enabled").commit()
            file.delete()
        }
    }
}
