package com.beautysdk.demo

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GestureResourceLibraryDeviceTest {
    @get:org.junit.Rule val backup=LabSceneBackup()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private fun bytes()=inst.context.assets.open("gesture_library_delivery.zip").use{it.readBytes()}
    @Test fun englishWrapperAndLegacyRootBothLoadButMixedRootsReject(){
        val raw=linkedMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(bytes().inputStream()).use{z->while(true){val e=z.nextEntry?:break;raw[e.name]=z.readBytes()}}
        assertTrue(raw.keys.all{it.startsWith("beauty_resources/")&&it.matches(Regex("[A-Za-z0-9_./-]+"))})
        assertTrue(raw.containsKey("beauty_resources/library.json"))
        val plain=raw.entries.associate{it.key.removePrefix("beauty_resources/") to it.value}
        assertEquals(4,ResourceDeliveryPack.read(bytes()).gestures.size)
        assertEquals(4,ResourceDeliveryPack.read(ResourceArchive.write(plain)).gestures.size)
        raw["catalog.json"]=plain.getValue("catalog.json")
        try{ResourceDeliveryPack.read(ResourceArchive.write(raw));fail("Mixed root accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun installsNineCategoryLibraryAndCombinesFourValidatedAnimations(){
        val root=File(context.cacheDir,"gesture-library-${System.nanoTime()}")
        try{
            val manager=BeautyResourceManager(root);val result=manager.installDelivery(bytes().inputStream());val library=requireNotNull(result.library)
            assertEquals(8,library.categories.size);assertEquals("Gesture Effects",library.text(requireNotNull(library.gestureCategory).nameKey,"en-US"))
            assertEquals(4,result.gestures.size);assertEquals(4,manager.listGestureResources().size)
            val all=manager.listGestureResources();assertTrue(all.all{manager.gesturePreview(it.hash).isNotEmpty()})
            assertEquals(4,manager.prepareGestureSelection(all.map{it.hash})!!.rules.size)
            assertEquals(1,library.gestureResources.count{it.defaultEnabled})
            assertEquals("Victory Sparkles",library.gestureResources.first().localizedName("en-US"))
            manager.deleteGestureResource(all.first().hash);assertEquals(3,manager.listGestureResources().size)
        }finally{root.deleteRecursively()}
    }
    @Test fun tamperedPreviewExpandedAtlasAndRulesRejectBeforeAnyInstallation(){
        for(kind in listOf("preview","atlas","rules")){
            val root=File(context.cacheDir,"gesture-reject-${System.nanoTime()}")
            try{
                val files=linkedMapOf<String,ByteArray>().also{out->java.util.zip.ZipInputStream(bytes().inputStream()).use{z->while(true){val e=z.nextEntry?:break;out[e.name.removePrefix("beauty_resources/")]=z.readBytes()}}};val lib=JSONObject(files.getValue("library.json").toString(Charsets.UTF_8));val ref=lib.getJSONArray("gestureResources").getJSONObject(0)
                when(kind){
                    "preview"->files[ref.getString("preview")]=byteArrayOf(0)
                    "atlas"->files[ref.getString("directory")+"asset_0.png"]=byteArrayOf(0)
                    else->{ref.getJSONArray("rules").getJSONObject(0).put("durationMs",777);files["library.json"]=lib.toString().toByteArray()}
                }
                val manager=BeautyResourceManager(root)
                try{manager.installDelivery(ResourceArchive.write(files).inputStream());fail("Accepted $kind")}catch(_:IllegalArgumentException){}
                assertTrue(manager.list().isEmpty());assertTrue(manager.listGestureResources().isEmpty())
            }finally{root.deleteRecursively()}
        }
    }
    @Test fun resourceCardsSelectRestoreAndApplyExplicitDefaults(){
        val root=File(context.filesDir,"beauty_resources");val manager=BeautyResourceManager(root)
        val previous=manager.listGestureResources().map{it.hash}.toSet();val ordinary=manager.list().map{it.hash}.toSet()
        val paths=listOf("active-defaults.json","pending-defaults.json","gesture-effects.zip","pending-gesture-effects.zip").map{File(root,it)}
        val saved=paths.map{it.takeIf(File::isFile)?.readBytes()}
        val input=File(context.cacheDir,"gesture-library-ui.zip").apply{writeBytes(bytes())}
        var activity:ResourceCenterActivity?=null;lateinit var model:ResourceCenterModel
        fun ready(check:(ResourceCenterModel.Ui)->Boolean={true}):ResourceCenterModel.Ui {
            val until=System.nanoTime()+20_000_000_000L
            while(System.nanoTime()<until){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&it.settings!=null){assertNull(it.error);if(check(it))return it}};Thread.sleep(25)}
            error("Gesture library UI timeout")
        }
        fun open(){activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity;inst.runOnMainSync{model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java];activity!!.findViewById<View>(R.id.resource_local_tab).performClick()};ready()}
        fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
        try{
            open();inst.runOnMainSync{model.clearGestureEffects()};ready{it.gestureEffects==null}
            inst.runOnMainSync{model.import(Uri.fromFile(input))};val imported=ready{it.gestureResources.size>=4&&it.library?.gestureCategory!=null}
            assertNull(imported.gestureEffects);assertTrue(imported.gestureSelection.isEmpty())
            val hashes=imported.library!!.gestureResources.take(2).map{it.sha256}
            inst.runOnMainSync{activity!!.findViewById<View>(android.R.id.content).findViewWithTag<View>("gesture_category").performClick()};inst.waitForIdleSync()
            for(hash in hashes){
                val until=System.nanoTime()+5_000_000_000L
                var card:View?=null
                while(card==null&&System.nanoTime()<until){inst.runOnMainSync{card=activity!!.findViewById<View>(android.R.id.content).findViewWithTag<View>("resource_$hash")};if(card==null)Thread.sleep(25)}
                inst.runOnMainSync{
                    assertNotNull("Installed gesture card must be reachable",card);card!!.performClick()
                    val center=ResourceCenterActivity::class.java.getDeclaredField("center").apply{isAccessible=true}.get(activity)
                    val dialog=ResourceCenterController::class.java.getDeclaredField("detail").apply{isAccessible=true}.get(center) as androidx.appcompat.app.AlertDialog
                    assertTrue(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isShown)
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                };ready{hash in it.gestureSelection}
            }
            assertEquals(2,ready().gestureEffects!!.rules.size)
            close();open();assertEquals(hashes.toSet(),ready{it.gestureSelection.size==2}.gestureSelection)
            inst.runOnMainSync{model.applyDeliveryDefaults()};val defaults=ready{it.gestureSelection.size==1};assertEquals(setOf(hashes.first()),defaults.gestureSelection)
            val active=defaults.gestureResources.single{it.hash==hashes.first()};inst.runOnMainSync{model.deleteGesture(active)};ready{it.gestureSelection.isEmpty()&&it.gestureEffects==null}
        }finally{
            close();val q=ResourceCenterModel::class.java.getDeclaredField("resourceIo").apply{isAccessible=true}.get(null) as java.util.concurrent.ExecutorService;q.submit{}.get()
            paths.zip(saved).forEach{(f,data)->if(data==null)f.delete()else f.writeBytes(data)}
            manager.listGestureResources().filter{it.hash !in previous}.forEach{manager.deleteGestureResource(it.hash)}
            manager.list().filter{it.hash !in ordinary}.forEach{manager.uninstall(it.hash)};input.delete()
        }
    }
}

