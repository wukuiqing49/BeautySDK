package com.beautysdk.resources

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 可取消安装；宿主后台线程调用，UI不会等待网络或解析。 */
class ResourceCancellation {private val flag=AtomicBoolean();fun cancel(){flag.set(true)};fun check(){check(!flag.get()&&!Thread.currentThread().isInterrupted){"CANCELLED"}}}
enum class ResourceInstallStage { COPYING, DOWNLOADING, VERIFYING, INSTALLING, INSTALLED }
data class ResourceProgress(val stage:ResourceInstallStage,val completed:Long=0,val total:Long?=null)
data class InstalledGestureResource(val hash:String,val descriptor:LibraryGestureResource)
data class InstalledResource(val hash:String,val descriptor:ResourceDescriptor)
data class CatalogGestureResource(val descriptor:LibraryGestureResource,val url:String,val previewUrl:String)
data class UnifiedResourceCatalog(val resources:List<CatalogResource>,val gestures:List<CatalogGestureResource> = emptyList())
data class CatalogResource(val manifest:ResourceDescriptor,val url:String,val previewUrl:String,val bytes:Int,val sha256:String)
/** HTTP流和连接由close负责释放；宿主可注入已有客户端，避免强依赖网络框架。 */
interface ResourceDownload:AutoCloseable {val input:InputStream;val length:Long?;override fun close()}
fun interface ResourceTransport {fun open(url:String):ResourceDownload}
/** 统一内置/本地/网络安装器。目录私有、内容不可变、切包不覆盖；不持有相机或Activity。 */
class BeautyResourceManager(private val folder:File,private val transport:ResourceTransport?=null) {
    private val gestureInstalled=File(folder,"gesture-installed");private val installed=File(folder,"installed");private val staging=File(folder,"staging");private val catalogs=File(folder,"catalogs")
    private val prepared=mutableMapOf<String,PreparedResource>();private val references=mutableMapOf<String,Int>()
    init {listOf(folder,installed,staging,catalogs,gestureInstalled).forEach {check(it.isDirectory||it.mkdirs())}}
    /** 统一复制输入、校验、提交；同一管理器串行化安装，重复内容直接复用。来源不参与文件名。 */
    @Synchronized fun install(input:InputStream,expected:CatalogResource?=null,cancel:ResourceCancellation=ResourceCancellation(),progress:(ResourceProgress)->Unit={}):InstalledResource {
        progress(ResourceProgress(if(expected==null)ResourceInstallStage.COPYING else ResourceInstallStage.DOWNLOADING))
        val source=input.use {bounded(it,33554432){cancel.check()}}
        val bytes=if(expected==null)LegacyResourceAdapter.upgrade(source)else source
        if(expected!=null)require(bytes.size==expected.bytes&&sha256(bytes)==expected.sha256){"DIGEST_MISMATCH"}
        cancel.check();progress(ResourceProgress(ResourceInstallStage.VERIFYING,bytes.size.toLong(),bytes.size.toLong()))
        val resource=ResourceArchive.load(bytes);val hash=sha256(bytes)
        expected?.let {require(it.manifest==resource.descriptor){"CATALOG_MISMATCH"}}
        val target=File(installed,hash)
        if(target.isDirectory) {require(File(target,"package.zip").readBytes().contentEquals(bytes));return InstalledResource(hash,resource.descriptor)}
        list().firstOrNull {it.descriptor.id==resource.descriptor.id&&it.descriptor.revision==resource.descriptor.revision&&it.hash!=hash}?.let {error("REVISION_CONFLICT")}
        val temp=File(staging,UUID.randomUUID().toString());check(temp.mkdir())
        try {
            progress(ResourceProgress(ResourceInstallStage.INSTALLING));cancel.check()
            val files=ResourceArchive.read(bytes)
            // 仅协议白名单中的根文件，统一校验后展开；不接收任意系统路径。
            files.forEach {(name,data)->cancel.check();File(temp,name).outputStream().use {it.write(data);it.fd.sync()}}
            File(temp,"package.zip").outputStream().use {it.write(bytes);it.fd.sync()}
            cancel.check();check(temp.renameTo(target)){"INSTALL_COMMIT_FAILED"}
            progress(ResourceProgress(ResourceInstallStage.INSTALLED,bytes.size.toLong(),bytes.size.toLong()))
            return InstalledResource(hash,resource.descriptor)
        } finally {if(temp.exists())temp.deleteRecursively()}
    }
    /** 批量导入Web完整资源库。先验证全部文件/默认引用/身份冲突，安装失败撤销本批新增项；不启用任何效果。 */
    @Synchronized fun installDelivery(input:InputStream,cancel:ResourceCancellation=ResourceCancellation()):ResourceDeliveryResult {
        val pack=ResourceDeliveryPack.read(input.use {bounded(it,33554432){cancel.check()}})
        val before=list();val known=before.associateBy {it.descriptor.id to it.descriptor.revision}
        for(item in pack.resources)known[item.entry.manifest.id to item.entry.manifest.revision]?.let {require(it.hash==item.entry.sha256){"REVISION_CONFLICT"}}
        val gestureBefore=listGestureResources()
        for(item in pack.gestures)gestureBefore.firstOrNull{it.descriptor.id==item.descriptor.id&&it.descriptor.revision==item.descriptor.revision}?.let{require(it.hash==item.descriptor.sha256){"GESTURE_REVISION_CONFLICT"}}
        val added=mutableListOf<InstalledResource>();val addedGestures=mutableListOf<String>()
        try {
            for(item in pack.resources){cancel.check();val r=install(item.bytes.inputStream(),item.entry,cancel);if(before.none{it.hash==r.hash})added+=r}
            for(item in pack.gestures){val r=installGesture(item.descriptor,item.bytes,item.preview,cancel);if(gestureBefore.none{it.hash==r.hash})addedGestures+=r.hash}
            return ResourceDeliveryResult(pack.resources.map {InstalledResource(it.entry.sha256,it.entry.manifest)},pack.defaults,pack.defaultsJson,pack.library,pack.gestures)
        } catch(t:Throwable){addedGestures.forEach{File(gestureInstalled,it).deleteRecursively()};added.asReversed().forEach {uninstall(it.hash)};throw t}
    }
    /** 从APK assets加载完整ZIP；后台调用，不联网、不改变用户选择。 */
    @JvmOverloads @Synchronized fun installDeliveryFromAssets(
        assets:android.content.res.AssetManager,assetPath:String="resource_delivery.zip",
        cancel:ResourceCancellation=ResourceCancellation()
    ):ResourceDeliveryResult {
        require(safeLibraryPath(assetPath));cancel.check()
        return installDelivery(assets.open(assetPath),cancel)
    }
    /** 从APK assets读取解压目录；有界收集全部文件后复用完整包校验和回滚。 */
    @JvmOverloads @Synchronized fun installDeliveryDirectoryFromAssets(
        assets:android.content.res.AssetManager,directory:String="beauty_resources",
        cancel:ResourceCancellation=ResourceCancellation()
    ):ResourceDeliveryResult {
        require(safeLibraryPath(directory+"/x")&&!directory.endsWith('/'))
        val files=linkedMapOf<String,ByteArray>();var total=0L
        fun visit(path:String,relative:String,depth:Int){
            cancel.check();require(depth<=8){"ASSET_DIRECTORY_DEPTH"}
            val children=requireNotNull(assets.list(path))
            if(children.isNotEmpty()){
                for(name in children){require(safeLibraryPath(name)&&!name.contains('/'));visit("$path/$name",if(relative.isEmpty())name else "$relative/$name",depth+1)}
            }else{
                require(relative.isNotEmpty()&&safeLibraryPath(relative)&&files.size<2000){"ASSET_FILE_LIMIT"}
                val data=assets.open(path).use{bounded(it,(33554432L-total).toInt()){cancel.check()}}
                total+=data.size;require(total<=33554432);files[relative]=data
            }
        }
        visit(directory,"",0);cancel.check()
        return installDelivery(ResourceArchive.write(files).inputStream(),cancel)
    }
    @Synchronized fun listGestureResources():List<InstalledGestureResource> = gestureInstalled.listFiles().orEmpty().filter{it.isDirectory&&it.name.matches(Regex("[0-9a-f]{64}"))}.map {dir->
        val descriptor=LibraryGestureResource.parse(File(dir,"resource.json").readBytes());require(descriptor.sha256==dir.name);InstalledGestureResource(dir.name,descriptor)
    }
    @Synchronized fun gesturePreview(hash:String):ByteArray {val descriptor=listGestureResources().single{it.hash==hash}.descriptor;return File(gestureInstalled,"$hash/preview.png").readBytes().also{require(sha256(it)==descriptor.previewSha256)}}
    /** IO配置阶段准备选择，拒绝重复手势和合计像素超限；从不自动开启检测。 */
    @Synchronized fun prepareGestureSelection(hashes:Collection<String>):com.beautysdk.GestureEffectPack? {
        require(hashes.size<=8&&hashes.toSet().size==hashes.size);if(hashes.isEmpty())return null
        com.beautysdk.license.BeautyLicensing.requireFeatures(setOf(com.beautysdk.license.LicensedFeature.GESTURE))
        val available=listGestureResources().associateBy{it.hash}
        var remainingPixels=8388608L
        val rules=hashes.flatMap {hash->
            val r=requireNotNull(available[hash])
            val data=File(gestureInstalled,"$hash/package.zip").readBytes();require(sha256(data)==r.hash)
            val next=com.beautysdk.GestureEffectArchive.loadZip(data.inputStream(),remainingPixels).rules
            remainingPixels-=next.sumOf{it.image.width.toLong()*it.image.height}
            require(remainingPixels>=0){"GESTURE_PIXEL_BUDGET"}
            next
        }
        return com.beautysdk.GestureEffectPack(rules)
    }
    @Synchronized fun deleteGestureResource(hash:String){require(hash.matches(Regex("[0-9a-f]{64}")));check(File(gestureInstalled,hash).deleteRecursively())}
    fun download(entry:CatalogResource,cancel:ResourceCancellation=ResourceCancellation(),progress:(ResourceProgress)->Unit={}):InstalledResource {
        requireHttps(entry.url)
        return open(entry.url).use {connection->
            require(connection.length==null||connection.length!!<=entry.bytes)
            var count=0L
            val tracked=object:java.io.FilterInputStream(connection.input){override fun read(b:ByteArray,off:Int,len:Int):Int {cancel.check();val n=super.read(b,off,len);if(n>0){count+=n;require(count<=entry.bytes);progress(ResourceProgress(ResourceInstallStage.DOWNLOADING,count,entry.bytes.toLong()))};return n}}
            install(tracked,entry,cancel,progress)
        }
    }
    @Synchronized fun list():List<InstalledResource> = installed.listFiles().orEmpty().filter {it.isDirectory&&it.name.matches(Regex("[0-9a-f]{64}"))}.mapNotNull {dir->runCatching {
        val files=ResourceArchive.read(File(dir,"package.zip").readBytes());InstalledResource(dir.name,ResourceArchive.descriptor(JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))))
    }.getOrNull()}
    /** 句柄固定资源版本；宿主在旧GPU工作完成后close，不允许提前卸载活动资源。 */
    /** 自包含主题拆分成单项，保持其他槽位配置后再编辑。 */
    @Synchronized fun materializeTheme(hash:String):List<InstalledResource> {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val data=File(installed,"$hash/package.zip").readBytes()
        val files=ResourceArchive.read(data)
        require(ResourceArchive.load(data).descriptor.category==ResourceCategory.THEME)
        val refs=JSONObject(files.getValue("effect.json").toString(Charsets.UTF_8)).getJSONObject("payload").getJSONArray("resources")
        return (0 until refs.length()).map {i->install(files.getValue(refs.getJSONObject(i).getString("file")).inputStream())}
    }
    @Synchronized fun acquire(hash:String):ResourceLease {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val resource=prepared.getOrPut(hash){val bytes=File(installed,"$hash/package.zip").readBytes();require(sha256(bytes)==hash){"CORRUPT_INSTALL"};ResourceArchive.load(bytes)}
        references[hash]=(references[hash]?:0)+1
        return ResourceLease(hash,resource){synchronized(this){val remaining=((references[hash]?:1)-1).coerceAtLeast(0);if(remaining==0){references.remove(hash);prepared.remove(hash)}else references[hash]=remaining}}
    }
    @Synchronized fun uninstall(hash:String) {require(hash.matches(Regex("[0-9a-f]{64}")));check((references[hash]?:0)==0){"RESOURCE_IN_USE"};val target=File(installed,hash);check(!target.exists()||target.deleteRecursively());prepared.remove(hash)}
    /** 只清理临时安装，绝不清用户状态；不与安装并发。 */
    @Synchronized fun cleanTemporary(){staging.listFiles().orEmpty().forEach {it.deleteRecursively()}}
    /** V1/V2共用入口；旧catalog调用只返回普通资源，新宿主使用完整目录。 */
    fun catalog(url:String):List<CatalogResource> = unifiedCatalog(url).resources
    fun unifiedCatalog(url:String):UnifiedResourceCatalog {
        requireHttps(url);val cache=File(catalogs,sha256(url.toByteArray())+".json")
        return try {val bytes=open(url).use {bounded(it.input,1048576)};parseUnifiedCatalog(bytes).also {atomic(cache,bytes)}}catch(e:Exception){if(cache.isFile)parseUnifiedCatalog(cache.readBytes())else throw e}
    }
    /** 网络与完整ZIP复用手势校验/安装，不自动启用动画。 */
    fun downloadGesture(entry:CatalogGestureResource,cancel:ResourceCancellation=ResourceCancellation()):InstalledGestureResource {
        cancel.check()
        val bytes=open(entry.url).use{bounded(it.input,entry.descriptor.bytes){cancel.check()}}
        val preview=gesturePreview(entry)
        return installGesture(entry.descriptor,bytes,preview,cancel)
    }
    fun gesturePreview(entry:CatalogGestureResource):ByteArray {
        val hash=entry.descriptor.previewSha256;val cache=File(catalogs,"gesture-$hash.png")
        fun validate(bytes:ByteArray):ByteArray {require(sha256(bytes)==hash){"GESTURE_PREVIEW_MISMATCH"};ResourceArchive.validatePreview(bytes);return bytes}
        return try {validate(open(entry.previewUrl).use{bounded(it.input,4194304)}).also{atomic(cache,it)}}catch(e:Exception){if(cache.isFile)validate(cache.readBytes())else throw e}
    }
    @Synchronized fun installGesture(descriptor:LibraryGestureResource,bytes:ByteArray,preview:ByteArray,cancel:ResourceCancellation=ResourceCancellation()):InstalledGestureResource {
        cancel.check();descriptor.validatePack(bytes);require(sha256(preview)==descriptor.previewSha256){"GESTURE_PREVIEW_MISMATCH"};ResourceArchive.validatePreview(preview)
        listGestureResources().firstOrNull{it.descriptor.id==descriptor.id&&it.descriptor.revision==descriptor.revision&&it.hash!=descriptor.sha256}?.let{error("GESTURE_REVISION_CONFLICT")}
        val target=File(gestureInstalled,descriptor.sha256)
        if(target.isDirectory){require(File(target,"package.zip").readBytes().contentEquals(bytes)){"CORRUPT_INSTALL"};return InstalledGestureResource(descriptor.sha256,LibraryGestureResource.parse(File(target,"resource.json").readBytes()))}
        val temp=File(staging,UUID.randomUUID().toString());check(temp.mkdir())
        try {File(temp,"package.zip").writeBytes(bytes);File(temp,"preview.png").writeBytes(preview);File(temp,"resource.json").writeBytes(descriptor.json);cancel.check();check(temp.renameTo(target)){"INSTALL_COMMIT_FAILED"}}finally{temp.deleteRecursively()}
        return InstalledGestureResource(descriptor.sha256,descriptor)
    }
    /** 独立封面读取，不要求先下载整包；宿主在IO线程调用并控制预取数量。 */
    fun preview(entry:CatalogResource):ByteArray {
        requireHttps(entry.previewUrl)
        val file=File(catalogs,sha256(entry.previewUrl.toByteArray())+".png")
        val bytes=try {open(entry.previewUrl).use {bounded(it.input,4194304)}.also {
            ResourceArchive.validatePreview(it);atomic(file,it)
        }} catch(e:Exception){if(file.isFile)file.readBytes()else throw e}
        ResourceArchive.validatePreview(bytes)
        return bytes
    }
    fun preview(hash:String):ByteArray {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return File(installed,"$hash/preview.png").inputStream().use {bounded(it,4194304)}
    }
    private fun open(url:String):ResourceDownload {
        requireHttps(url);transport?.let {return it.open(url)}
        val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=15000;c.instanceFollowRedirects=false
        try {require(c.responseCode==200){"HTTP_${c.responseCode}"};val stream=c.inputStream;return object:ResourceDownload {override val input=stream;override val length=c.contentLengthLong.takeIf {it>=0};override fun close(){try{stream.close()}finally{c.disconnect()}}}}catch(t:Throwable){c.disconnect();throw t}
    }
    companion object {
        internal fun atomic(file:File,bytes:ByteArray) {check(file.parentFile!!.isDirectory||file.parentFile!!.mkdirs());val tmp=File.createTempFile("pending_",".part",file.parentFile);try{tmp.outputStream().use{it.write(bytes);it.fd.sync()};check(tmp.renameTo(file))}finally{tmp.delete()}}
        fun parseCatalog(bytes:ByteArray):List<CatalogResource> = parseUnifiedCatalog(bytes).resources
        private fun parseOrdinaryCatalog(bytes:ByteArray):List<CatalogResource> {
            require(bytes.size<=1048576);val root=JSONObject(bytes.toString(Charsets.UTF_8));val version=root.integer("resourceCatalogVersion",1,2);if(version==1)root.fields("resourceCatalogVersion","resources")else root.fields("resourceCatalogVersion","resources","gestures")
            val a=root.getJSONArray("resources");require(a.length()<=500)
            return (0 until a.length()).map {i->val e=a.getJSONObject(i);e.fields("manifest","url","previewUrl","bytes","sha256");val d=ResourceArchive.descriptor(e.getJSONObject("manifest"));val url=e.text("url");val preview=e.text("previewUrl");requireHttps(url);requireHttps(preview);val hash=e.text("sha256");require(hash.matches(Regex("[0-9a-f]{64}")));CatalogResource(d,url,preview,e.integer("bytes",1,33554432),hash)}.also {require(it.map{e->e.manifest.id to e.manifest.revision}.toSet().size==it.size)}
        }
        fun parseUnifiedCatalog(bytes:ByteArray):UnifiedResourceCatalog {
            val resources=parseOrdinaryCatalog(bytes);val root=JSONObject(bytes.toString(Charsets.UTF_8))
            val gestures=if(root.getInt("resourceCatalogVersion")==2){val a=root.getJSONArray("gestures");require(a.length()<=8)
                (0 until a.length()).map{i->val e=a.getJSONObject(i);e.fields("descriptor","url","previewUrl");val d=LibraryGestureResource.parse(e.getJSONObject("descriptor"));val url=e.text("url");val preview=e.text("previewUrl");requireHttps(url);requireHttps(preview);CatalogGestureResource(d,url,preview)}
            }else emptyList()
            val identities=resources.map{it.manifest.id to it.manifest.revision}+gestures.map{it.descriptor.id to it.descriptor.revision}
            require(identities.toSet().size==identities.size){"CATALOG_DUPLICATE"}
            return UnifiedResourceCatalog(resources,gestures)
        }
        private fun requireHttps(url:String){val u=URL(url);require(u.protocol=="https"&&u.host.isNotBlank()&&u.userInfo==null&&u.ref==null)}
    }
}
class ResourceLease internal constructor(val hash:String,val resource:PreparedResource,private val release:()->Unit):AutoCloseable {
    private val closed=AtomicBoolean();override fun close(){if(closed.compareAndSet(false,true))release()}
}
