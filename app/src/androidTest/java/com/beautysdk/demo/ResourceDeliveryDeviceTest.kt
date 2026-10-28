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

/** 夹具直接使用当前Web的buildDelivery输出，验证跨端交付链路及失败时保留原状态。 */
class ResourceDeliveryDeviceTest {
 @get:org.junit.Rule val sceneBackup=LabSceneBackup()
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private fun bytes()=inst.context.assets.open("resource_delivery.zip").use{it.readBytes()}
 @Test fun allTwentySevenResourcesAndFiveDefaultsInstallAndCompose(){
  val folder=File(inst.targetContext.cacheDir,"delivery_"+java.util.UUID.randomUUID())
  try {val manager=BeautyResourceManager(folder);val result=manager.installDelivery(bytes().inputStream())
   assertEquals(27,result.resources.size);assertEquals(27,manager.list().size);assertEquals("en",result.defaults.locale);assertFalse(result.defaults.followHost);assertEquals(5,result.defaults.state.selections.size)
   BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel()).prepare(result.defaults.state).use {s->assertEquals(.28f,s.settings.adjustments.slimFace);assertNotNull(s.settings.eyeMakeup);assertTrue(s.settings.adjustments.lipstick>0f);assertTrue(s.settings.adjustments.blusher>0f)}
   assertEquals(27,manager.installDelivery(bytes().inputStream()).resources.size);assertEquals(27,manager.list().size)
  }finally{folder.deleteRecursively()}
 }
 @Test fun corruptedDeliveryAndMissingDefaultNeverPartiallyInstall(){
  val folder=File(inst.targetContext.cacheDir,"delivery_bad_"+java.util.UUID.randomUUID())
  try {val manager=BeautyResourceManager(folder);val files=unzip(bytes());val zip=files.keys.first{it.endsWith(".zip")};files[zip]=files.getValue(zip).clone().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
   assertThrows(Exception::class.java){manager.installDelivery(ResourceArchive.write(files).inputStream())};assertTrue(manager.list().isEmpty())
   val valid=unzip(bytes());val defaults=JSONObject(String(valid.getValue("defaults.json")));defaults.getJSONObject("selections").put("beauty","f".repeat(64));valid["defaults.json"]=defaults.toString().toByteArray()
   assertThrows(Exception::class.java){manager.installDelivery(ResourceArchive.write(valid).inputStream())};assertTrue(manager.list().isEmpty())
  }finally{folder.deleteRecursively()}
 }
 private fun unzip(bytes:ByteArray):MutableMap<String,ByteArray>{val out=linkedMapOf<String,ByteArray>();java.util.zip.ZipInputStream(bytes.inputStream()).use{z->while(true){val e=z.nextEntry?:break;out[e.name]=z.readBytes()}};return out}
 @Test fun importOnlyInstallsExplicitApplyPersistsLanguageAndSelection(){
  val context=inst.targetContext;val folder=File(context.filesDir,"beauty_resources")
  val paths=listOf("user-state.json","active-defaults.json","pending-defaults.json").map{File(folder,it)};val backups=paths.map{it.takeIf(File::exists)?.readBytes()}
  val input=File(context.cacheDir,"delivery-ui.zip").apply{writeBytes(bytes())};var activity:ResourceCenterActivity?=null;lateinit var model:ResourceCenterModel
  fun close(){inst.runOnMainSync{activity?.finish()};inst.waitForIdleSync();activity=null}
  fun waitFor(check:(ResourceCenterModel.Ui)->Boolean):ResourceCenterModel.Ui {val deadline=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<deadline){var ui:ResourceCenterModel.Ui?=null;inst.runOnMainSync{ui=model.ui.value};ui?.let{if(!it.busy&&check(it))return it};Thread.sleep(25)};error("Delivery UI did not settle")}
  fun open(){activity=inst.startActivitySync(Intent(context,ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity;inst.runOnMainSync{model=ViewModelProvider(activity!!)[ResourceCenterModel::class.java]};waitFor{it.settings!=null}}
  try {open();val before=waitFor{it.settings!=null}.selection
   inst.runOnMainSync{model.import(Uri.fromFile(input))};assertEquals(before,waitFor{it.deliveryDefaultsAvailable}.selection)
   inst.runOnMainSync{val button=activity!!.findViewById<View>(R.id.resource_apply_delivery);assertTrue(button.isShown);button.performClick()}
   val applied=waitFor{it.locale=="en"&&it.selection.count{e->e.value!=null}==5};assertNotNull(applied.settings!!.eyeMakeup)
   close();open();assertEquals(applied.selection,waitFor{it.locale=="en"}.selection)
  }finally{close();paths.zip(backups).forEach{(file,backup)->if(backup==null)file.delete()else file.writeBytes(backup)};input.delete()}
 }
}
