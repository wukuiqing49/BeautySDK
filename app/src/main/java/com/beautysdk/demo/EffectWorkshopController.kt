package com.beautysdk.demo

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.beautysdk.*
import com.beautysdk.demo.databinding.ViewStickerWorkshopBinding
import java.io.File
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

/** 页面只负责选择文件；资源由ViewModel持有，旋转屏幕不重复解码或保存相机帧。 */
class EffectWorkshopController(activity: CameraTestActivity, binding: ViewStickerWorkshopBinding,
    changed: (List<EffectResourcePack>)->Unit) {
    private val model=ViewModelProvider(activity)[EffectResourcesModel::class.java]
    val theme: BeautyThemePack? get()=model.state.value?.theme
    val packs: List<EffectResourcePack> get()=model.state.value?.packs ?: emptyList()
    private val picker=activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::load) }
    init {
        binding.resourceNetwork.setOnClickListener {
            val input=android.widget.EditText(activity).apply { setText("https://beautystickerworkshop.pages.dev/catalog.json");inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;maxLines=3 }
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(R.string.resource_catalog_url).setView(input)
                .setMessage(R.string.resource_cache).setPositiveButton(android.R.string.ok) { _,_ -> model.catalog(input.text.toString().trim()) }.setNegativeButton(android.R.string.cancel,null).show()
        }
        model.online.observe(activity) { items ->
            if(items.isNotEmpty()) androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(R.string.resource_download)
                .setItems(items.map { "${it.type} · ${it.name} · ${it.version}" }.toTypedArray()) { _,i -> model.download(items[i]) }.show()
        }
        model.downloadedSticker.observe(activity) { file ->
            if(file!=null) { ViewModelProvider(activity)[StickerWorkshopViewModel::class.java].importFile(file);model.downloadedSticker.value=null }
        }
        binding.effectResourceSample.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(activity).setItems(R.array.effect_resource_samples) { _, index -> if(index<EffectResourceType.entries.size) model.loadSample(EffectResourceType.entries[index]) else if(index==3) model.loadThemeSample() else model.loadEyeSample(listOf("natural","dense","cat")[index-4]) }.show()
        }
        binding.effectResourceImport.setOnClickListener { picker.launch(arrayOf("application/zip","application/octet-stream")) }
        binding.effectResourceClear.setOnClickListener { clear() }
        model.state.observe(activity) { state ->
            binding.effectResourceSample.isEnabled=!state.busy
            binding.effectResourceImport.isEnabled=!state.busy
            binding.effectResourceClear.isEnabled=!state.busy
            binding.effectResourceStatus.text=when {
                state.error!=null -> activity.getString(R.string.effect_resource_error,state.error)
                state.busy -> activity.getString(R.string.effect_resource_loading)
                state.theme!=null -> activity.getString(R.string.effect_resource_theme_selected,state.theme.name)
                state.packs.isEmpty() -> activity.getString(R.string.effect_resource_empty)
                else -> state.packs.joinToString("\n") { "${it.type} · ${it.name}" }
            }
            changed(state.packs)
        }
        model.restore()
    }
    fun clear()=model.clear()
}

/** 单一IO队列、有界加载。先完整校验和原子保存，再发布；坏包不覆盖旧资源。 */
class EffectResourcesModel(app: Application): AndroidViewModel(app) {
    data class State(val packs:List<EffectResourcePack> = emptyList(),val busy:Boolean=false,val error:String?=null,val theme:BeautyThemePack?=null)
    val state=MutableLiveData(State())
    private val io=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    private var restored=false
    @Volatile private var generation=0L
    private var ioToken=0L
    private val folder=File(app.filesDir,"effect_resources")
    val online=MutableLiveData<List<BeautyResourceEntry>>(emptyList())
    val downloadedSticker=MutableLiveData<File?>()
    private val store=BeautyResourceStore(File(app.filesDir,"resource_cache"))
    /** 目录与下载同一有界IO队列；离线目录/包缓存可重用，UI不等待网络。 */
    fun catalog(url:String) = runTask {
        val token=ioToken
        val items=store.catalog(url)
        main.post { if(!closed && token==generation) { online.value=items;online.value=emptyList() } }
        (state.value ?: State()).copy(busy=false,error=null)
    }
    fun download(entry:BeautyResourceEntry) = runTask {
        val token=ioToken
        val file=store.obtain(entry) { input -> when(entry.type) {
            "THEME" -> BeautyThemePack.loadZip(input)
            "STICKER" -> StickerResourcePack.loadZip(input)
            else -> require(EffectResourcePack.loadZip(input).type.name==entry.type)
        } }
        if(entry.type=="STICKER") {
            main.post { if(!closed && token==generation) downloadedSticker.value=file }
            (state.value ?: State()).copy(busy=false,error=null)
        } else loadBytes(file.readBytes())
    }
    private val themeFile=AtomicFile(File(folder,"theme.zip"))
    private fun file(type:EffectResourceType)=AtomicFile(File(folder,"${type.name}.zip"))
    fun restore() {
        if(restored)return
        restored=true
        runTask {
            if(themeFile.baseFile.exists() || File(themeFile.baseFile.path+".bak").exists()) return@runTask State(theme=BeautyThemePack.loadZip(themeFile.openRead()))
            State(packs=EffectResourceType.entries.mapNotNull { type ->
                val disk=file(type)
                if(!disk.baseFile.exists() && !File(disk.baseFile.path+".bak").exists()) null
                else EffectResourcePack.loadZip(disk.openRead())
            })
        }
    }
    /** 同一个文件入口接受独立效果或完整主题；主题先完整校验，成功保存后才一次发布。 */
    fun load(uri: Uri) = runTask {
        val bytes=requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=32*1024*1024);out.write(buffer,0,n)};out.toByteArray()
        }
        loadBytes(bytes)
    }
    private fun loadBytes(bytes:ByteArray):State {
        val isTheme=java.util.zip.ZipInputStream(bytes.inputStream()).use { zip -> zip.nextEntry?.name=="bundle.json" }
        // 主题导出固定bundle.json在首项；其他顺序通过有界扫描识别，不解码PNG。
        val themeArchive=isTheme || java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var found=false;var count=0;var expanded=0L;val scratch=ByteArray(8192)
            while(count++<6){val entry=zip.nextEntry ?: break;if(entry.name=="bundle.json"){found=true;break};while(true){val n=zip.read(scratch);if(n<0)break;expanded+=n;require(expanded<=32*1024*1024)}};found
        }
        check(folder.isDirectory || folder.mkdirs())
        return if(themeArchive){val theme=BeautyThemePack.loadZip(bytes.inputStream());val stream=themeFile.startWrite();try{stream.write(bytes);themeFile.finishWrite(stream);EffectResourceType.entries.forEach { file(it).delete() }}catch(t:Throwable){themeFile.failWrite(stream);throw t};State(theme=theme)}
        else saveSingle(EffectResourcePack.loadZip(bytes.inputStream()))
    }
    fun loadThemeSample() = runTask { loadBytes(getApplication<Application>().assets.open("effects/full_theme.zip").use { it.readBytes() }) }
    fun loadSample(type:EffectResourceType) = loadPack {
        EffectResourcePack.loadZip(getApplication<Application>().assets.open("effects/${type.name.lowercase(java.util.Locale.ROOT)}_scene.zip"))
    }
    /** 内置图片睫毛也经过正式V4解析及原子保存，不走相机专属捷径。 */
    fun loadEyeSample(kind:String) { require(kind in listOf("natural","dense","cat"));loadPack {EffectResourcePack.loadZip(getApplication<Application>().assets.open("effects/lash_$kind.zip"))} }
    private fun loadPack(read:()->EffectResourcePack) { runTask { saveSingle(read()) } }
    private fun saveSingle(pack:EffectResourcePack):State {
        val old=state.value?.packs.orEmpty()
        check(folder.isDirectory || folder.mkdirs())
        val target=file(pack.type);val stream=target.startWrite()
        try { pack.writeZip(object:java.io.FilterOutputStream(stream) { override fun close() { flush() } });target.finishWrite(stream) }
        catch(t:Throwable) { target.failWrite(stream);throw t }
        themeFile.delete()
        return State(packs=old.filter { it.type!=pack.type }+pack)
    }
    fun clear() { runTask(force=true) { EffectResourceType.entries.forEach { file(it).delete() };themeFile.delete();State() } }
    private fun runTask(force:Boolean=false,action:()->State) {
        val old=state.value ?: State()
        if((old.busy && !force) || closed)return
        val token=++generation
        state.value=old.copy(busy=true,error=null)
        io.execute {
            if(token!=generation || closed) return@execute
            ioToken=token
            val result=runCatching(action)
            main.post { if(!closed && token==generation) state.value=result.fold({it},{old.copy(error=it.message ?: it.javaClass.simpleName)}) }
        }
    }
    override fun onCleared() { closed=true;io.shutdown();main.removeCallbacksAndMessages(null);super.onCleared() }
}
