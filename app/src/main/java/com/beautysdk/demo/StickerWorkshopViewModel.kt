package com.beautysdk.demo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.beautysdk.*
import java.util.concurrent.Executors

/** 工坊状态跨Activity重建保留；只保存素材，不保存摄像头图像。 */
data class StickerWorkshopState(
    val resource: StickerResourcePack? = null,
    val renderPack: StickerPack? = null,
    val preview: Bitmap? = null,
    val layerIndex: Int = 0,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val exported: Boolean = false,
    val savedAndLoaded: Boolean = false,
    val error: String? = null,
)

/** 素材读写在单独IO线程执行，调参只更新不可变布局；不持有Activity或相机。 */
class StickerWorkshopViewModel @JvmOverloads constructor(application: Application,draftName: String = "draft.zip"): AndroidViewModel(application) {
    init { require(draftName.matches(Regex("[a-zA-Z0-9_]+\\.zip"))) }
    /** 编辑器分区和参数选择跨 Activity 重建保留，不持有视图。 */
    var selectionVersion=0L
        private set
    var editorSection = 0
    var editorParameter = 0
    private val io=Executors.newSingleThreadScheduledExecutor()
    private var draftTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var restoreAttempted=false
    private var activationVersion=0L
    // 按草稿名称隔离开关，测试草稿不会污染用户正式素材。
    private val preferences=application.getSharedPreferences("sticker_workshop_state",Application.MODE_PRIVATE)
    private val enabledKey="$draftName.enabled"
    private val draftFile=java.io.File(application.filesDir,"sticker_workshop/$draftName")
    private val main=Handler(Looper.getMainLooper())
    private val mutable=MutableLiveData(StickerWorkshopState())
    val state: LiveData<StickerWorkshopState> = mutable
    private var original: StickerResourceManifest? = null
    @Volatile private var cleared=false
    private data class Export(val resource: StickerResourcePack,val jsonOnly: Boolean)
    private var pendingExport: Export? = null

    /** 默认样例只有在用户打开工坊后加载，不影响原贴纸选择。 */
    fun ensureSample() { if(mutable.value!!.resource==null && !mutable.value!!.busy) loadSample(0) }

    fun loadSample(index: Int) {
        require(index in SAMPLE_IDS.indices)
        load { StickerResourcePack.loadZip(getApplication<Application>().assets.open("sticker_packs/${SAMPLE_IDS[index]}.zip")) }
    }

    /** SAF授予的URI只在本次读取使用，不请求存储权限，不解压到公共目录。 */
    fun importFile(file:java.io.File) = load { StickerResourcePack.loadZip(file.inputStream()) }

    fun importPack(uri: Uri) = load {
        StickerResourcePack.loadZip(requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)))
    }

    private fun load(enableAfterLoad: Boolean = true,persist: Boolean = true,savedAndLoaded: Boolean = false,read: () -> StickerResourcePack) {
        val current=mutable.value!!
        if(current.busy) return
        val activation=activationVersion
        mutable.value=current.copy(busy=true,error=null,exported=false,savedAndLoaded=false)
        io.execute {
            val result=runCatching {
                val resource=read()
                val bytes=resource.previewPng()
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256) sample*=2
                val preview=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                    BitmapFactory.Options().apply { inSampleSize=sample; inScaled=false })) { "Invalid preview PNG" }
                resource to preview
            }
            main.post { if(!cleared) result.fold(onSuccess={ (resource,preview) ->
                original=resource.manifest
                if(persist) selectionVersion++
                val enabled=enableAfterLoad && activation==activationVersion
                mutable.value=StickerWorkshopState(resource,if(enabled) resource.toStickerPack() else null,preview,enabled=enabled,savedAndLoaded=savedAndLoaded)
                preferences.edit().putBoolean(enabledKey,enabled).apply()
                if(persist) scheduleDraft(resource)
            },onFailure={ error -> mutable.value=mutable.value!!.copy(busy=false,error=error.message ?: error.javaClass.simpleName) }) }
        }
    }

    fun selectLayer(index: Int) {
        val current=mutable.value!!
        if(current.busy || current.resource==null || index !in current.resource.manifest.layers.indices) return
        if(index!=current.layerIndex) mutable.value=current.copy(layerIndex=index)
    }

    fun setEnabled(value: Boolean) {
        // 即使当前尚无资源，清除动作也必须阻止在途导入稍后重新启用贴纸。
        activationVersion++
        preferences.edit().putBoolean(enabledKey,value).apply()
        val current=mutable.value!!
        if(value==current.enabled || current.resource==null) return
        mutable.value=current.copy(enabled=value,renderPack=if(value) current.resource.toStickerPack() else null)
    }

    /** 只替换选中层的布局。资源解码对象保持原引用，GL不会重新上传同一张PNG。 */
    fun updateLayer(layer: StickerLayerSpec) {
        val current=mutable.value!!
        val resource=current.resource ?: return
        if(current.busy || resource.manifest.layers[current.layerIndex]==layer) return
        val layers=resource.manifest.layers.toMutableList().apply { set(current.layerIndex,layer) }
        val changed=resource.withManifest(StickerResourceManifest(resource.manifest.id,resource.manifest.name,layers,resource.manifest.preview))
        mutable.value=current.copy(resource=changed,renderPack=if(current.enabled) changed.toStickerPack() else null,exported=false,savedAndLoaded=false,error=null)
        scheduleDraft(changed)
    }

    fun resetLayer() { original?.layers?.getOrNull(mutable.value!!.layerIndex)?.let(::updateLayer) }

    /** 文件选择器期间冻结导出快照，防止旋转或继续编辑后保存了另一份配置。 */
    fun prepareExport(jsonOnly: Boolean): String? {
        val resource=mutable.value!!.takeUnless { it.busy }?.resource ?: return null
        pendingExport=Export(resource,jsonOnly)
        return if(jsonOnly) "${resource.manifest.id}_manifest.json" else "${resource.manifest.id}.zip"
    }

    fun export(uri: Uri?) {
        val export=pendingExport ?: return
        pendingExport=null
        if(uri==null) return
        mutable.value=mutable.value!!.copy(busy=true,error=null,exported=false,savedAndLoaded=false)
        io.execute {
            val result=runCatching {
                val resolver=getApplication<Application>().contentResolver
                requireNotNull(resolver.openOutputStream(uri,"wt")).use { output ->
                    if(export.jsonOnly) output.write(export.resource.manifest.toJson().toByteArray(Charsets.UTF_8))
                    else export.resource.writeZip(output)
                }
            }
            main.post { if(!cleared) mutable.value=mutable.value!!.copy(busy=false,exported=result.isSuccess,
                error=result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }) }
        }
    }

    /** 恢复应用私有素材及上次开关；旧草稿无开关记录时保持关闭。清除可取消在途启用。 */
    fun restoreDraft() {
        if(restoreAttempted || mutable.value!!.resource!=null || mutable.value!!.busy) return
        restoreAttempted=true
        if(draftFile.exists() || java.io.File(draftFile.path+".bak").exists())
            load(enableAfterLoad=preferences.getBoolean(enabledKey,false),persist=false) {
                android.util.AtomicFile(draftFile).openRead().use(StickerResourcePack::loadZip)
            }
    }

    /** 拖动时合并磁盘写入；最多保留一份草稿，不保存人脸图像或URI授权。 */
    private fun scheduleDraft(resource: StickerResourcePack,delayMs: Long = 600) {
        draftTask?.cancel(false)
        draftTask=io.schedule({
            try { writeDraft(resource) }
            catch(error:Exception) {
                main.post { if(!cleared) mutable.value=mutable.value!!.copy(error=error.message ?: error.javaClass.simpleName) }
            }
        },delayMs,java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** 完整写入成功才提交；与读取共用单IO队列，禁止在视频帧回调中调用。 */
    private fun writeDraft(resource: StickerResourcePack) {
        draftFile.parentFile?.mkdirs()
        val atomic=android.util.AtomicFile(draftFile)
        var stream: java.io.FileOutputStream? = null
        try {
            stream=atomic.startWrite()
            resource.writeZip(object: java.io.FilterOutputStream(stream) {
                override fun write(bytes: ByteArray,offset: Int,length: Int) { out.write(bytes,offset,length) }
                // ZIP只关闭包装流，文件提交和fsync由AtomicFile负责。
                override fun close() { flush() }
            })
            atomic.finishWrite(stream)
        } catch(error:Exception) {
            if(stream!=null) atomic.failWrite(stream)
            throw error
        }
    }

    /** 冻结当前编辑结果，落盘后重新走SDK加载校验，成功才切换预览图片对象。 */
    fun saveAndReload() {
        val current=mutable.value!!
        val resource=current.resource ?: return
        if(current.busy) return
        draftTask?.cancel(false)
        load(persist=false,savedAndLoaded=true) {
            writeDraft(resource)
            android.util.AtomicFile(draftFile).openRead().use(StickerResourcePack::loadZip)
        }
    }

    /** 页面退到后台时立即安排最后一次参数快照。 */
    fun flushDraft() { mutable.value!!.takeUnless { it.busy }?.resource?.let { scheduleDraft(it,0) } }

    override fun onCleared() { cleared=true; io.shutdown(); super.onCleared() }

    companion object {
        val SAMPLE_IDS=listOf("round_glasses","heart_glasses","curled_moustache","blue_top_hat","gold_crown","cat_ears")
    }
}
