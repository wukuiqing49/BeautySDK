package com.beautysdk

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 宿主声明实际已初始化的算法/渲染能力；不会由资源包自动下载或开启。 */
data class ThemeCapabilities(val faceTracking: Boolean = false, val segmentation: Boolean = false, val extendedBeauty: Boolean = false)

/** 完整主题包：固定一层子ZIP，IO线程整体校验、预检累计像素，再解码。没有逐帧IO或厂商依赖。 */
class BeautyThemePack private constructor(
    val id: String, val name: String, private val encoded: Map<String,ByteArray>,
    private val resolved: BeautySettings,
) {
    val requirements: ThemeCapabilities get() = ThemeCapabilities(resolved.requiresFaceAnalysis,resolved.scene.needsSegmentation,!resolved.adjustments.isIdentity)
    fun previewPng(): ByteArray = encoded.getValue("preview.png").copyOf()
    /** 未包含类别恢复默认；先检查全部运行能力，失败不触碰现有Session。返回不可变设置，供宿主一次赋值。 */
    fun prepare(capabilities: ThemeCapabilities): BeautySettings {
        val needs=requirements
        require(!needs.faceTracking || capabilities.faceTracking) { "Theme requires face tracking" }
        require(!needs.segmentation || capabilities.segmentation) { "Theme requires foreground segmentation" }
        require(!needs.extendedBeauty || capabilities.extendedBeauty) { "Theme requires extended beauty backend" }
        return resolved
    }
    /** 一次volatile配置发布；后台加载完成后调用。宿主仍负责匹配渲染器与分割提供器。 */
    fun applyTo(session: BeautySession, capabilities: ThemeCapabilities) { session.settings=prepare(capabilities) }
    /** 保留制作端原始子资源，输出流由调用者提供、此方法关闭。 */
    fun writeZip(output: OutputStream) = ZipOutputStream(output).use { zip -> encoded.forEach { (name,bytes) ->
        zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
    } }
    companion object {
        private val kinds=listOf("sticker","background","motion","beauty")
        private val allowed=(kinds.map { "$it.zip" }+listOf("bundle.json","preview.png")).toSet()
        /** 不解压到文件系统。总输入与展开内容32MiB，累计像素8,388,608；子包仍受V1/V2独立限制。 */
        @JvmStatic fun loadZip(input: InputStream): BeautyThemePack {
            val source=input.use { bounded(it,32*1024*1024) }
            val files=linkedMapOf<String,ByteArray>();var total=0
            ZipInputStream(source.inputStream()).use { zip -> while(true) {
                val entry=zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in allowed && entry.name !in files && files.size<6) { "Invalid theme ZIP entry" }
                val limit=when(entry.name) { "bundle.json"->65536;"preview.png"->4*1024*1024;else->16*1024*1024 }
                val bytes=bounded(zip,limit);total+=bytes.size;require(total<=32*1024*1024) { "Theme content budget exceeded" }
                files[entry.name]=bytes;zip.closeEntry()
            } }
            val text=files["bundle.json"] ?: error("Missing bundle.json")
            val root=JSONObject(text.toString(Charsets.UTF_8))
            require(root.keys().asSequence().toSet()==setOf("bundleVersion","id","name","preview","resources")) { "Unknown theme fields" }
            require(root.get("bundleVersion") is Number && root.getDouble("bundleVersion")==1.0) { "Unsupported bundle version" }
            fun string(key:String):String { require(root.get(key) is String);return root.getString(key) }
            val id=string("id");val name=string("name")
            require(id.matches(Regex("[a-z][a-z0-9_]{0,47}")) && name.isNotBlank() && name.length<=80 && name.none { it.isISOControl() })
            require(string("preview")=="preview.png")
            val refs=root.getJSONObject("resources");require(refs.keys().asSequence().toSet()==kinds.toSet())
            val selected=kinds.filter { kind -> if(refs.isNull(kind)) false else { require(refs.get(kind) is String && refs.getString(kind)=="$kind.zip");true } }
            require(selected.isNotEmpty() && files.keys==setOf("bundle.json","preview.png")+selected.map { "$it.zip" }) { "Missing or unreferenced theme resource" }
            // 在任何大图解码前检查全部PNG；不能用嵌套ZIP绕过累计预算。
            var pixels=0L
            fun checkImage(bytes:ByteArray) {
                StickerResourcePack.validateStaticPng(bytes)
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outMimeType=="image/png")
                pixels+=bounds.outWidth.toLong()*bounds.outHeight;require(pixels<=8_388_608) { "Theme pixel budget exceeded" }
            }
            checkImage(files.getValue("preview.png"))
            for(kind in selected) StickerResourcePack.readEntries(files.getValue("$kind.zip").inputStream()).filterKeys { it.endsWith(".png") }.values.forEach(::checkImage)
            var settings=BeautySettings()
            for(kind in selected) {
                val bytes=files.getValue("$kind.zip")
                if(kind=="sticker")settings=settings.copy(stickerPack=StickerResourcePack.loadZip(bytes.inputStream()).toStickerPack())
                else { val pack=EffectResourcePack.loadZip(bytes.inputStream());require(pack.type.name.lowercase(java.util.Locale.ROOT)==kind) { "Theme resource category mismatch" };settings=pack.applyTo(settings) }
            }
            return BeautyThemePack(id,name,files.toMap(),settings)
        }
        private fun bounded(input:InputStream,limit:Int):ByteArray {
            val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=limit);out.write(buffer,0,n) }
            return out.toByteArray()
        }
    }
}
