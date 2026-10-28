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
data class InstalledResource(val hash:String,val descriptor:ResourceDescriptor)
data class CatalogResource(val manifest:ResourceDescriptor,val url:String,val previewUrl:String,val bytes:Int,val sha256:String)
/** HTTP流和连接由close负责释放；宿主可注入已有客户端，避免强依赖网络框架。 */
interface ResourceDownload:AutoCloseable {val input:InputStream;val length:Long?;override fun close()}
fun interface ResourceTransport {fun open(url:String):ResourceDownload}
/** 统一内置/本地/网络安装器。目录私有、内容不可变、切包不覆盖；不持有相机或Activity。 */
class BeautyResourceManager(private val folder:File,private val transport:ResourceTransport?=null) {
    private val installed=File(folder,"installed");private val staging=File(folder,"staging");private val catalogs=File(folder,"catalogs")
    private val prepared=mutableMapOf<String,PreparedResource>();private val references=mutableMapOf<String,Int>()
    init {listOf(folder,installed,staging,catalogs).forEach {check(it.isDirectory||it.mkdirs())}}
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
        val added=mutableListOf<InstalledResource>()
        try {
            for(item in pack.resources){cancel.check();val r=install(item.bytes.inputStream(),item.entry,cancel);if(before.none{it.hash==r.hash})added+=r}
            return ResourceDeliveryResult(pack.resources.map {InstalledResource(it.entry.sha256,it.entry.manifest)},pack.defaults,pack.defaultsJson)
        } catch(t:Throwable){added.asReversed().forEach {uninstall(it.hash)};throw t}
    }
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
    fun catalog(url:String):List<CatalogResource> {
        requireHttps(url);val cache=File(catalogs,sha256(url.toByteArray())+".json")
        return try {val bytes=open(url).use {bounded(it.input,1048576)};parseCatalog(bytes).also {atomic(cache,bytes)}}catch(e:Exception){if(cache.isFile)parseCatalog(cache.readBytes())else throw e}
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
        fun parseCatalog(bytes:ByteArray):List<CatalogResource> {
            require(bytes.size<=1048576);val root=JSONObject(bytes.toString(Charsets.UTF_8));root.fields("resourceCatalogVersion","resources");root.integer("resourceCatalogVersion",1,1)
            val a=root.getJSONArray("resources");require(a.length()<=500)
            return (0 until a.length()).map {i->val e=a.getJSONObject(i);e.fields("manifest","url","previewUrl","bytes","sha256");val d=ResourceArchive.descriptor(e.getJSONObject("manifest"));val url=e.text("url");val preview=e.text("previewUrl");requireHttps(url);requireHttps(preview);val hash=e.text("sha256");require(hash.matches(Regex("[0-9a-f]{64}")));CatalogResource(d,url,preview,e.integer("bytes",1,33554432),hash)}.also {require(it.map{e->e.manifest.id to e.manifest.revision}.toSet().size==it.size)}
        }
        private fun requireHttps(url:String){val u=URL(url);require(u.protocol=="https"&&u.host.isNotBlank()&&u.userInfo==null&&u.ref==null)}
    }
}
class ResourceLease internal constructor(val hash:String,val resource:PreparedResource,private val release:()->Unit):AutoCloseable {
    private val closed=AtomicBoolean();override fun close(){if(closed.compareAndSet(false,true))release()}
}
