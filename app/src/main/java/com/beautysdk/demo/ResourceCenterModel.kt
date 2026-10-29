package com.beautysdk.demo

import android.app.Application
import androidx.lifecycle.*
import android.os.Handler
import android.os.Looper
import com.beautysdk.*
import com.beautysdk.resources.*
import java.io.File
import java.util.concurrent.Executors

/** 实验室仅编排公共资源API，包解析、安装和组合均位于lib_beauty_resources。 */
class ResourceCenterModel(app:Application):AndroidViewModel(app) {
    data class Ui(val installed:List<InstalledResource> = emptyList(),val online:List<CatalogResource> = emptyList(),val settings:BeautySettings?=null,
        val selection:Map<String,String?> = emptyMap(),val busy:Boolean=false,val progress:ResourceProgress?=null,val error:String?=null,val enabled:Boolean=true,val locale:String?=null,val order:List<ResourceCategory> = ResourceCategory.entries,val previews:Map<String,ByteArray> = emptyMap(),val deliveryDefaultsAvailable:Boolean=false,val gestureEffects:GestureEffectPack?=null,val pendingGestureEffects:Boolean=false,val library:BeautyResourceLibrary?=null,val gestureResources:List<InstalledGestureResource> = emptyList(),val gestureSelection:Set<String> = emptySet(),val gesturePreviews:Map<String,ByteArray> = emptyMap(),val onlineGestures:List<CatalogGestureResource> = emptyList(),val lastImportedDeliverySha256:String?=null)
    val ui=MutableLiveData(Ui())
    // 多页面/重开共用串行队列，旧页面已接受的保存完成后，新页面才读取。
    private val io=resourceIo;private val main=Handler(Looper.getMainLooper())
    private val manager=BeautyResourceManager(File(app.filesDir,"beauty_resources"))
    private val libraryFile=File(app.filesDir,"beauty_resources/library.json")
    private val builtinLibrary by lazy {BeautyResourceLibrary.parse(app.assets.open("resource_center/library.json").use{it.readBytes()})}
    private var library:BeautyResourceLibrary?=null
    private val activeDefaults=File(app.filesDir,"beauty_resources/active-defaults.json")
    private val importReceipt=File(app.filesDir,"beauty_resources/last-local-delivery.sha256")
    private val pendingDefaults=File(app.filesDir,"beauty_resources/pending-defaults.json")
    private val storage=BeautyResourceStateStore(File(app.filesDir,"beauty_resources/user-state.json"))
    private val sceneStorage=LabSceneStore(File(app.filesDir,"beauty_resources"))
    private val gestureFile=File(app.filesDir,"beauty_resources/gesture-effects.zip")
    private val pendingGestureFile=File(app.filesDir,"beauty_resources/pending-gesture-effects.zip")
    private var gesturePack:GestureEffectPack?=null
    private var gestureStamp:String?=null
    private val gestureSelectionFile=File(app.filesDir,"beauty_resources/gesture-selection.json")
    private var gestureResourcesSnapshot:List<InstalledGestureResource> = emptyList()
    private var gestureSelectionSnapshot:Set<String> = emptySet()
    private var gesturePreviewsSnapshot:Map<String,ByteArray> = emptyMap()
    // Demo明确装配478点、场景与分割；这是装配能力，不代表模型已就绪或视觉验收通过。
    private val composer=BeautyEffectComposer(manager,ResourceCapabilities.configuredGpuPixel(faceAnalysis=true,sceneRenderer=true,segmentation=true))
    private var backgroundOverride:BeautyScene?=null
    private var motionOverride:BeautyScene?=null
    private var sceneRestoreError:String?=null
    private var defaults:BeautyResourceDefaults?=null;private var state=BeautyResourceState();private var current:ComposedBeauty?=null
    @Volatile private var generation=0L;@Volatile private var closed=false
    private var cancellation:ResourceCancellation?=null
    // 仅由IO队列访问：滑杆/场景调节不改变资源目录，不应重复解包并读取全部封面。
    private var installedSnapshot:List<InstalledResource>?=null
    private var previewSnapshot:Map<String,ByteArray> = emptyMap()
    private fun refreshResources() {
        gestureResourcesSnapshot=manager.listGestureResources()
        val selectionJson=gestureSelectionFile.takeIf{it.isFile}?.readText()
        val stamp=selectionJson?.let{"selection:$it"}?:"archive:${gestureFile.lastModified()}:${gestureFile.length()}"
        if(stamp!=gestureStamp){
            gestureSelectionSnapshot=selectionJson?.let{json->val a=org.json.JSONArray(json);require(a.length()<=8);(0 until a.length()).map{a.getString(it)}.toSet()}?:emptySet()
            gesturePack=if(selectionJson!=null)manager.prepareGestureSelection(gestureSelectionSnapshot)else if(gestureFile.isFile)gestureFile.inputStream().use(GestureEffectArchive::loadZip)else null;gestureStamp=stamp
        }
        gesturePreviewsSnapshot=gestureResourcesSnapshot.associate{it.hash to (gesturePreviewsSnapshot[it.hash]?:manager.gesturePreview(it.hash))}
        val installed=manager.list()
        val previews=installed.associate {it.hash to (previewSnapshot[it.hash]?:manager.preview(it.hash))}
        installedSnapshot=installed
        previewSnapshot=previews
    }
    /** UI线程合并在线封面；本地已删除资源不再残留于页面快照。 */
    private fun mergePreviews(local:Map<String,ByteArray>):Map<String,ByteArray> {
        val onlineHashes=ui.value?.online.orEmpty().map{it.sha256}.toSet()
        return ui.value?.previews.orEmpty().filterKeys{it in onlineHashes}+local
    }
    init {task { token->
        manager.cleanTemporary()
        library=if(libraryFile.isFile)BeautyResourceLibrary.parse(libraryFile.readBytes())else builtinLibrary
        defaults=BeautyResourceDefaults.parse(if(activeDefaults.isFile)activeDefaults.readBytes()else app.assets.open("resource_center/defaults.json").use{it.readBytes()})
        val restore=runCatching {storage.read()}.getOrElse {e->main.post {ui.value=ui.value?.copy(error="STATE_RESTORE: ${e.message}")};null}
        // 完整内置包通过公共SDK安装一次；升级时保留已删除旧素材及用户配置。
        val marker=File(app.filesDir,"beauty_resources/builtin-installed.json")
        val previous=if(marker.isFile)org.json.JSONArray(marker.readText()).let{a->(0 until a.length()).map{a.getString(it)}.toSet()}else emptySet()
        val packageMarker=File(app.filesDir,"beauty_resources/builtin-library-installed.txt")
        val digest=app.assets.open("resource_delivery.zip").use{input->val md=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;md.update(buffer,0,n)};md.digest().joinToString(""){"%02x".format(it)}}
        if(packageMarker.takeIf{it.isFile}?.readText()!=digest){
            val before=manager.list().map{it.hash}.toSet()
            val deleted=previous-before
            val delivery=manager.installDeliveryFromAssets(app.assets)
            delivery.resources.filter{it.hash in deleted}.forEach{manager.uninstall(it.hash)}
            writeDefaults(marker,org.json.JSONArray((previous+delivery.resources.map{it.hash}).toList()).toString().toByteArray())
            writeDefaults(packageMarker,digest.toByteArray())
        }
        refreshResources()
        val installed=requireNotNull(installedSnapshot)
        val resolved=requireNotNull(defaults).merge(restore,installed=installed).resolveInstalled(installed)
        state=resolved.state
        restoreScenes(state,installed)
        publish(token,state,false,reloadResources=false)
        if(resolved.missingSlots.isNotEmpty())main.post {if(token==generation&&!closed)ui.value=ui.value?.copy(error="RESOURCE_MISSING: ${resolved.missingSlots.joinToString()}")}
    } }
    fun select(resource:InstalledResource,enableBeauty:Boolean=false)=task {token->
        when(resource.descriptor.category) {
            ResourceCategory.BACKGROUND->backgroundOverride=null
            ResourceCategory.MOTION->motionOverride=null
            ResourceCategory.THEME->{backgroundOverride=null;motionOverride=null}
            else->Unit
        }
        val selected=composer.select(state,resource)
        publish(token,if(enableBeauty)selected.copy(enabled=true)else selected,true)
    }
    fun refresh()=task {token->
        val app=getApplication<Application>()
        defaults=BeautyResourceDefaults.parse(if(activeDefaults.isFile)activeDefaults.readBytes()else app.assets.open("resource_center/defaults.json").use{it.readBytes()})
        library=if(libraryFile.isFile)BeautyResourceLibrary.parse(libraryFile.readBytes())else builtinLibrary
        val saved=storage.read()
        // 返回页面时重新扫描，兼容另一个宿主/资源页安装或删除资源。
        refreshResources()
        val installed=requireNotNull(installedSnapshot)
        val resolved=requireNotNull(defaults).merge(saved,installed=installed).resolveInstalled(installed)
        restoreScenes(resolved.state,installed)
        publish(token,resolved.state,false,reloadResources=false)
    }
    /** 手动场景与资源共同在同一IO队列生成最终快照，UI不再提交另一套渲染配置。 */
    fun scene(value:BeautyScene,background:Boolean,motion:Boolean)=task {token->if(background)backgroundOverride=value;if(motion)motionOverride=value;publish(token,state,true,reloadResources=false)}
    /** 场景恢复失败只旁路手动场景，其他资源/参数仍可加载，向宿主报告存档问题。 */
    private fun restoreScenes(next:BeautyResourceState,installed:List<InstalledResource>) {
        sceneRestoreError=null
        val restored=runCatching{sceneStorage.read(next,installed)}.getOrElse {error->
            sceneRestoreError="SCENE_RESTORE: ${error.message}";null to null}
        backgroundOverride=restored.first;motionOverride=restored.second
    }
    fun disable(slot:String)=task {token->
        // 取消整套主题不能先拆成子项，否则卡片取消后子效果仍会保留。
        val editable=if(slot=="theme")state else composer.materialize(state)
        publish(token,editable.copy(selections=editable.selections+(slot to null)),true)
    }
    /** 显式关闭当前类别，保留其他类别；与“恢复默认值”使用不同语义。 */
    fun disableCategory(category:ResourceCategory)=task {token->
        if(category==ResourceCategory.BACKGROUND)backgroundOverride=BeautyScene()
        if(category==ResourceCategory.MOTION)motionOverride=BeautyScene()
        if(category==ResourceCategory.THEME){
            publish(token,state.copy(selections=state.selections+("theme" to null)),true);return@task
        }
        val editable=composer.materialize(state)
        val neutral=BeautyParameters.neutral.filterKeys {BeautyParameters.category(it)==category}
        val slots=manager.list().filter {it.descriptor.category==category}.map {it.descriptor.slot}
        publish(token,editable.copy(parameters=editable.parameters+neutral,
            selections=editable.selections+slots.associateWith {null}),true)
    }
    fun resetCategory(category:ResourceCategory)=task {token->
        if(category==ResourceCategory.BACKGROUND)backgroundOverride=BeautyScene()
        if(category==ResourceCategory.MOTION)motionOverride=BeautyScene()
        if(category==ResourceCategory.THEME){backgroundOverride=null;motionOverride=null;publish(token,requireNotNull(defaults).state,true);return@task}
        val editable=composer.materialize(state)
        val keys=BeautyParameters.neutral.keys.filter{BeautyParameters.category(it)==category}
        val slots=manager.list().filter{it.descriptor.category==category}.map{it.descriptor.slot}
        publish(token,editable.copy(parameters=(editable.parameters-keys)+requireNotNull(defaults).state.parameters.filterKeys{it in keys},selections=(editable.selections-slots)+requireNotNull(defaults).state.selections.filterKeys{it in slots}),true)
    }
    fun resetDefaults()=task {token->backgroundOverride=null;motionOverride=null;publish(token,requireNotNull(defaults).state,true)}
    fun parameters(changes:Map<String,Float>) {
        if(changes.isEmpty())return
        // 防止调用方复用可变Map，导致排队中的历史输入被后续拖动改写。
        val input=changes.toMap()
        task {token->
            // 主题首次拆分会安装子资源；此时仍须刷新目录，不能只走参数快照。
            val materializing=state.selections["theme"]!=null
            val editable=composer.materialize(state)
            publish(token,library?.updateParameters(editable,input)?:editable.copy(parameters=editable.parameters+input),true,reloadResources=materializing)
        }
    }
    fun clearStickers()=task {token->
        val editable=composer.materialize(state)
        publish(token,editable.copy(selections=editable.selections.mapValues{(slot,hash)->if(slot.startsWith("sticker."))null else hash}),true)
    }
    fun enabled(value:Boolean)=task {token->publish(token,state.copy(enabled=value),true,reloadResources=false)}
    /** 单项、套装与完整交付包共用导入口；批量安装不改变当前搭配。 */
    fun import(uri:android.net.Uri)=task {token->
        val cancel=ResourceCancellation();cancellation=cancel
        val data=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){cancel.check();val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=33554432){"PACKAGE_TOO_LARGE"};out.write(buffer,0,n)};out.toByteArray()
        }
        val gesture=java.util.zip.ZipInputStream(data.inputStream()).use{zip->var found=false;while(true){val e=zip.nextEntry?:break;if(e.name=="gestures.json"){found=true;break}};found}
        if(gesture){GestureEffectArchive.loadZip(data.inputStream());cancel.check();writeDefaults(pendingGestureFile,data);publish(token,state,false);return@task}
        val delivery=java.util.zip.ZipInputStream(data.inputStream()).use {zip->var found=false;while(true){val entry=zip.nextEntry?:break;if(entry.name=="catalog.json"||entry.name=="beauty_resources/catalog.json"){found=true;break}};found}
        if(delivery){val result=manager.installDelivery(data.inputStream(),cancel);cancel.check();writeDefaults(pendingDefaults,result.defaultsJson);val importedLibrary=result.library;if(importedLibrary!=null){writeDefaults(libraryFile,importedLibrary.json);library=importedLibrary}else{libraryFile.delete();library=null};writeDefaults(importReceipt,java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it)}.toByteArray())}else manager.install(data.inputStream(),cancel=cancel)
        publish(token,state,false)
    }
    fun applyGestureEffects()=task {token->
        val bytes=pendingGestureFile.readBytes();val prepared=GestureEffectArchive.loadZip(bytes.inputStream())
        writeDefaults(gestureFile,bytes);gestureSelectionFile.delete();gestureSelectionSnapshot=emptySet();gesturePack=prepared;gestureStamp="archive:${gestureFile.lastModified()}:${gestureFile.length()}"
        pendingGestureFile.delete();publish(token,state,false,reloadResources=false)
    }
    /** 发行源仅允许随宿主打包配置；用户输入的普通目录不获得发行方信任。 */
    fun downloadOfficial()=task {token->
        val app=getApplication<Application>()
        require(app.assets.list("").orEmpty().contains("official-resources.json")){"OFFICIAL_SOURCE_NOT_CONFIGURED"}
        val bytes=app.assets.open("official-resources.json").use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=32768){"SOURCE_CONFIG_TOO_LARGE"};out.write(buffer,0,n)};out.toByteArray()
        }
        val config=org.json.JSONObject(String(bytes,Charsets.UTF_8))
        require(config.keys().asSequence().toSet()==setOf("version","packageUrl","envelopeUrl","releaseId","trustedKeys")){"INVALID_SOURCE_CONFIG"}
        require(config.get("version")==1){"UNSUPPORTED_SOURCE_CONFIG"}
        val keys=config.getJSONObject("trustedKeys")
        val source=SignedResourceSource(config.getString("packageUrl"),config.getString("envelopeUrl"),config.getString("releaseId"),
            keys.keys().asSequence().associateWith{android.util.Base64.decode(keys.getString(it),android.util.Base64.NO_WRAP)})
        val cancel=ResourceCancellation();cancellation=cancel
        val result=source.install(manager,cancel).result
        cancel.check();writeDefaults(pendingDefaults,result.defaultsJson)
        result.library?.let{writeDefaults(libraryFile,it.json);library=it}?:run{libraryFile.delete();library=null}
        publish(token,state,false)
    }
    fun clearGestureEffects()=task {token->gestureFile.delete();pendingGestureFile.delete();gestureSelectionFile.delete();gestureSelectionSnapshot=emptySet();gesturePack=null;gestureStamp=null;publish(token,state,false)}
    /** 卡片点击显式启用/关闭；同手势的整组配置互斥，其他手势保持。 */
    fun selectGesture(resource:InstalledGestureResource)=task {token->
        val next=gestureSelectionSnapshot.toMutableSet()
        if(!next.remove(resource.hash)){
            fun triggers(r:InstalledGestureResource):Set<String>{val a=org.json.JSONArray(r.descriptor.rulesJson);return (0 until a.length()).map{a.getJSONObject(it).getString("gesture")}.toSet()}
            val incoming=triggers(resource)
            val conflicting=gestureResourcesSnapshot.filter{it.hash in next&&triggers(it).any(incoming::contains)}.map{it.hash}.toSet()
            next.removeAll(conflicting);next.add(resource.hash)
        }
        activateGestureSelection(next);publish(token,state,false)
    }
    private fun activateGestureSelection(hashes:Collection<String>){
        val prepared=manager.prepareGestureSelection(hashes)
        val data=org.json.JSONArray(hashes.toList()).toString().toByteArray()
        writeDefaults(gestureSelectionFile,data);gestureSelectionSnapshot=hashes.toSet();gesturePack=prepared;gestureStamp="selection:${data.toString(Charsets.UTF_8)}"
    }
    fun deleteGesture(resource:InstalledGestureResource)=task {token->
        if(resource.hash in gestureSelectionSnapshot)activateGestureSelection(gestureSelectionSnapshot-resource.hash)
        manager.deleteGestureResource(resource.hash);publish(token,state,false)
    }
    /** 用户显式采用后才替换默认语言/搭配；下次启动仍沿用同一默认JSON。 */
    fun applyDeliveryDefaults()=task {token->
        val bytes=pendingDefaults.readBytes();val next=BeautyResourceDefaults.parse(bytes)
        require(next.state.resolveInstalled(manager.list()).missingSlots.isEmpty()){"DELIVERY_DEFAULT_MISSING"}
        composer.prepare(next.state).close()
        val defaultGestures=library?.gestureResources?.filter{it.defaultEnabled}?.map{it.sha256}
        if(library?.gestureCategory!=null){manager.prepareGestureSelection(defaultGestures.orEmpty());activateGestureSelection(defaultGestures.orEmpty())}
        writeDefaults(activeDefaults,bytes);defaults=next;backgroundOverride=null;motionOverride=null
        publish(token,next.state,true)
    }
    private fun writeDefaults(file:File,bytes:ByteArray){val temp=File(file.parentFile,file.name+".part");try{temp.outputStream().use{it.write(bytes);it.fd.sync()};check(temp.renameTo(file))}finally{temp.delete()}}
    fun catalog(url:String)=task {token->val catalog=manager.unifiedCatalog(url);val entries=catalog.resources
        val previews=ui.value?.previews.orEmpty().toMutableMap();val gesturePreviews=gesturePreviewsSnapshot.toMutableMap();var budget=0
        for(entry in entries.take(30)) {if(token!=generation)break;runCatching{manager.preview(entry)}.getOrNull()?.let{image->if(budget+image.size<=16777216){previews[entry.sha256]=image;budget+=image.size}}}
        for(entry in catalog.gestures) {if(token!=generation)break;runCatching{manager.gesturePreview(entry)}.getOrNull()?.let{image->if(budget+image.size<=16777216){gesturePreviews[entry.descriptor.sha256]=image;budget+=image.size}}}
        main.post {if(token==generation&&!closed)ui.value=ui.value?.copy(online=entries,onlineGestures=catalog.gestures,busy=false,previews=previews,gesturePreviews=gesturePreviews,deliveryDefaultsAvailable=pendingDefaults.isFile)}}
    fun downloadGesture(entry:CatalogGestureResource)=task {token->val cancel=ResourceCancellation();cancellation=cancel;manager.downloadGesture(entry,cancel);if(token==generation)publish(token,state,false)}
    fun download(entry:CatalogResource)=task {token->val cancel=ResourceCancellation();cancellation=cancel;val resource=manager.download(entry,cancel) {p->main.post {if(token==generation&&!closed)ui.value=ui.value?.copy(progress=p)}};if(token==generation)publish(token,state,false)}
    fun uninstall(resource:InstalledResource)=task {token->
        val next=state.copy(selections=state.selections.mapValues{if(it.value==resource.hash)null else it.value})
        // 已解码的图片由快照持有；Demo发布新状态后不再读取旧目录。
        publish(token,next,true);manager.uninstall(resource.hash)
        refreshResources()
        val installed=requireNotNull(installedSnapshot);val previews=previewSnapshot
        main.post {if(token==generation&&!closed)ui.value=ui.value?.copy(installed=installed,previews=mergePreviews(previews))}
    }
    fun cancel(){generation++;cancellation?.cancel();ui.value=ui.value?.copy(busy=false,progress=null)}
    private fun publish(token:Long,next:BeautyResourceState,save:Boolean,reloadResources:Boolean=true) {
        // 配置操作串行提交，不能因后续滑杆/开关输入到达而丢掉先前的不同字段。
        // 只抑制过时UI发布；网络任务另用generation拒绝过期完成。
        val composed=composer.prepare(next)
        var scene=composed.settings.scene
        backgroundOverride?.let {scene=scene.copy(background=it.background,backgroundColor=it.backgroundColor,backgroundImage=it.backgroundImage,blurRadius=it.blurRadius,backgroundFocusX=it.backgroundFocusX,backgroundFocusY=it.backgroundFocusY)}
        motionOverride?.let {scene=scene.copy(motion=it.motion,zoom=it.zoom,periodMs=it.periodMs,timeline=it.timeline)}
        val settings=composed.settings.copy(scene=scene)
        if(reloadResources||installedSnapshot==null)refreshResources()
        if(save)try{storage.write(next);sceneStorage.write(next,requireNotNull(installedSnapshot),backgroundOverride,motionOverride);sceneRestoreError=null}catch(t:Throwable){composed.close();throw t}
        val old=current;current=composed;state=next;old?.close()
        val list=requireNotNull(installedSnapshot);val previews=previewSnapshot
        val locale=defaults?.takeUnless{it.followHost}?.locale
        val order=defaults?.categoryOrder?:library?.categories?.map{it.id}?:ResourceCategory.entries
        val deliveryAvailable=pendingDefaults.isFile
        val importHash=importReceipt.takeIf{it.isFile&&it.length()==64L}?.readText()?.takeIf{it.matches(Regex("[0-9a-f]{64}"))}
        val restoreError=sceneRestoreError
        val appliedGestures=gesturePack;val pendingGestures=pendingGestureFile.isFile
        main.post {if(token==generation&&!closed)ui.value=Ui(list,ui.value?.online.orEmpty(),settings,next.selections,enabled=next.enabled,locale=locale,order=order,previews=mergePreviews(previews),deliveryDefaultsAvailable=deliveryAvailable,error=restoreError,gestureEffects=appliedGestures,pendingGestureEffects=pendingGestures,library=library,gestureResources=gestureResourcesSnapshot,gestureSelection=gestureSelectionSnapshot,gesturePreviews=ui.value?.gesturePreviews.orEmpty().filterKeys{hash->ui.value?.onlineGestures.orEmpty().any{it.descriptor.sha256==hash}}+gesturePreviewsSnapshot,onlineGestures=ui.value?.onlineGestures.orEmpty(),lastImportedDeliverySha256=importHash)}
    }
    private fun task(action:(Long)->Unit) {
        if(closed)return
        val token=++generation;cancellation?.cancel();ui.value=(ui.value?:Ui()).copy(busy=true,error=null)
        io.execute {try{action(token)}catch(e:Exception){main.post {if(token==generation&&!closed)ui.value=ui.value?.copy(busy=false,error=e.message?:e.javaClass.simpleName)}}}
    }
    override fun onCleared(){
        closed=true;generation++;cancellation?.cancel()
        // 快照的获取/替换/释放在同一队列，避免退出页面时与未结束的prepare并发释放。
        io.execute {current?.close();current=null;installedSnapshot=null;previewSnapshot=emptyMap()}
        main.removeCallbacksAndMessages(null);super.onCleared()
    }
    private companion object {val resourceIo=Executors.newSingleThreadExecutor()}
}
