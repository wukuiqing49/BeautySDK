package com.beautysdk

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 静态目录条目。版本用于展示，SHA-256是缓存身份；网络包不可覆盖已验证的旧包。 */
data class BeautyResourceEntry(val id:String,val name:String,val version:String,val type:String,val url:String,val bytes:Long,val sha256:String)

/** 可选资源下载组件，与相机、算法、厂商帧无关。所有方法必须由宿主IO线程调用。
 * 仅主动调用时联网；HTTPS、不跟随重定向、有界读取、摘要校验及原子写入。不上传视频。
 * validate必须执行对应资源包的完整校验；返回文件只是缓存，不会自动切换渲染效果。
 */
fun interface BeautyResourceTransport {
    /** 宿主可注入已有HTTP客户端；实现必须支持超时和取消，不得返回超过maxBytes的数据。 */
    fun read(url:String,maxBytes:Long):ByteArray
}
/** 资源仓库在宿主指定的私有目录保存已校验的目录和ZIP；宿主负责该目录的存储预算。 */
class BeautyResourceStore @JvmOverloads constructor(private val folder:File,private val transport:BeautyResourceTransport?=null) {
    fun catalog(url:String):List<BeautyResourceEntry> {
        val key=digest(url.toByteArray())
        val cached=File(folder,"catalog_$key.json")
        val bytes=try { request(url,256*1024).also { parseCatalog(it);atomic(cached,it) } }
            catch(e:Exception) { if(cached.exists()) cached.readBytes() else throw e }
        return parseCatalog(bytes)
    }
    fun cached(entry:BeautyResourceEntry):File? {
        require(entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.bytes in 1..33554432)
        return File(folder,entry.sha256+".zip").takeIf {
        it.isFile && it.length()==entry.bytes && digest(it.readBytes())==entry.sha256
        }
    }
    /** 缓存命中也重新校验内容；失败不会改变宿主当前应用的资源。 */
    fun obtain(entry:BeautyResourceEntry,validate:(InputStream)->Unit):File {
        require(entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.bytes in 1..33554432)
        cached(entry)?.let { it.inputStream().use(validate);return it }
        val bytes=request(entry.url,entry.bytes)
        require(bytes.size.toLong()==entry.bytes && digest(bytes)==entry.sha256) { "Resource digest or size mismatch" }
        bytes.inputStream().use(validate)
        return File(folder,entry.sha256+".zip").also { atomic(it,bytes) }
    }
    private fun request(address:String,limit:Long):ByteArray {
        val url=URL(address);require(url.protocol=="https" && url.userInfo==null && url.ref==null) { "HTTPS URL required" }
        transport?.let { return it.read(address,limit).also { data -> require(data.size.toLong()<=limit) } }
        val connection=url.openConnection() as HttpURLConnection
        connection.connectTimeout=10_000;connection.readTimeout=15_000;connection.instanceFollowRedirects=false
        try {
            require(connection.responseCode==200) { "HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong<=limit) { "Resource too large" }
            return connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val block=ByteArray(8192)
                while(true) { check(!Thread.currentThread().isInterrupted) { "Download cancelled" };val n=input.read(block);if(n<0) break;require(out.size().toLong()+n<=limit) { "Resource too large" };out.write(block,0,n) }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    private fun atomic(file:File,bytes:ByteArray) {
        check(folder.isDirectory || folder.mkdirs())
        val temp=File.createTempFile("resource_",".part",folder)
        try { temp.outputStream().use { it.write(bytes);it.fd.sync() };check(temp.renameTo(file)) { "Cache commit failed" } } finally { temp.delete() }
    }
    companion object {
        /** 目录严格限量，路径不作为本地文件名；禁止未知字段、重复ID、伪数字及未知类别。 */
        @JvmStatic fun parseCatalog(bytes:ByteArray):List<BeautyResourceEntry> {
            require(bytes.size<=256*1024)
            val root=JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.keys().asSequence().toSet()==setOf("catalogVersion","resources") && root.get("catalogVersion") is Number && root.getDouble("catalogVersion")==1.0)
            val list=root.getJSONArray("resources");require(list.length()<=100)
            return (0 until list.length()).map { i ->
                val o=list.getJSONObject(i)
                require(o.keys().asSequence().toSet()==setOf("id","name","version","type","url","bytes","sha256"))
                fun text(k:String)=o.get(k).also { require(it is String) }.toString()
                val id=text("id");val name=text("name");val version=text("version");val type=text("type");val url=text("url");val hash=text("sha256")
                require(id.matches(Regex("[a-z][a-z0-9_]{0,47}")) && name.isNotBlank() && name.length<=80 && name.none { it.isISOControl() } && version.matches(Regex("[A-Za-z0-9_.-]{1,32}")))
                require(type in setOf("STICKER","BEAUTY","BACKGROUND","MOTION","THEME") && hash.matches(Regex("[0-9a-f]{64}")))
                val address=URL(url);require(address.protocol=="https" && address.userInfo==null && address.ref==null)
                require(o.get("bytes") is Number);val size=o.getDouble("bytes");require(size.isFinite() && size%1==0.0 && size in 1.0..33554432.0)
                BeautyResourceEntry(id,name,version,type,url,size.toLong(),hash)
            }.also { require(it.map { e->e.id }.toSet().size==it.size) }
        }
        private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
