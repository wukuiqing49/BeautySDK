package com.beautysdk

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

/** 单层静态贴纸的制作参数；偏移按锚点跨度计量，不依赖视频分辨率。 */
data class StickerLayerSpec @JvmOverloads constructor(
    val id: String,
    val image: String,
    val anchor: StickerAnchor,
    val widthInAnchorSpans: Float = 2.5f,
    val pivotX: Float = .5f,
    val pivotY: Float = .5f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Invalid layer id" }
        requirePngName(image)
        require(widthInAnchorSpans.isFinite() && widthInAnchorSpans in .1f..10f) { "Invalid width" }
        require(listOf(pivotX,pivotY,offsetX,offsetY).all { it.isFinite() && it in -4f..4f }) { "Invalid placement" }
        require(rotationDegrees.isFinite() && rotationDegrees in -180f..180f) { "Invalid rotation" }
    }
}

/** v1资源包说明；只接受静态PNG，列表顺序就是合成覆盖顺序，不执行包内代码。 */
class StickerResourceManifest(
    val id: String,
    val name: String,
    layers: List<StickerLayerSpec>,
    val preview: String = "preview.png",
) {
    val layers: List<StickerLayerSpec> = Collections.unmodifiableList(layers.toList())
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,47}"))) { "Invalid pack id" }
        require(name.isNotBlank() && name.length<=80 && name.none { it.isISOControl() }) { "Invalid pack name" }
        require(layers.size in 1..3 && layers.map { it.id }.distinct().size==layers.size) { "Expected 1 to 3 unique layers" }
        requirePngName(preview)
    }

    /** 输出完整配置；不包含图像或人脸数据，可由制作工具编辑后重新打包。 */
    fun toJson(): String = JSONObject().apply {
        put("schemaVersion",1); put("id",id); put("name",name); put("preview",preview)
        put("layers",JSONArray().apply { layers.forEach { layer -> put(JSONObject().apply {
            put("id",layer.id); put("image",layer.image); put("anchor",layer.anchor.name)
            put("widthInAnchorSpans",layer.widthInAnchorSpans.toDouble())
            put("pivot",JSONArray(listOf(layer.pivotX.toDouble(),layer.pivotY.toDouble())))
            put("offset",JSONArray(listOf(layer.offsetX.toDouble(),layer.offsetY.toDouble())))
            put("rotationDegrees",layer.rotationDegrees.toDouble())
        }) } })
    }.toString(2)

    companion object {
        /** 严格检查字段和版本；拼写错误或不支持的锚点明确失败，不悄悄使用默认位置。 */
        @JvmStatic fun parse(text: String): StickerResourceManifest {
            require(text.toByteArray(Charsets.UTF_8).size<=64*1024) { "Manifest too large" }
            val root=JSONObject(text)
            root.requireKeys(setOf("schemaVersion","id","name","preview","layers"))
            require(root.get("schemaVersion") is Number && root.getDouble("schemaVersion")==1.0) { "Unsupported schemaVersion" }
            val array=root.getJSONArray("layers")
            require(array.length() in 1..3) { "Expected 1 to 3 layers" }
            val layers=(0 until array.length()).map { index ->
                val layer=array.getJSONObject(index)
                layer.requireKeys(setOf("id","image","anchor","widthInAnchorSpans","pivot","offset","rotationDegrees"))
                val pivot=layer.getJSONArray("pivot"); val offset=layer.getJSONArray("offset")
                require(pivot.length()==2 && offset.length()==2) { "Expected two coordinates" }
                fun coordinate(values: JSONArray, i: Int): Float {
                    require(values.get(i) is Number) { "Expected numeric coordinate" }
                    return values.getDouble(i).toFloat()
                }
                require(layer.get("widthInAnchorSpans") is Number && layer.get("rotationDegrees") is Number) { "Expected numeric placement" }
                StickerLayerSpec(layer.strictString("id"),layer.strictString("image"),
                    StickerAnchor.valueOf(layer.strictString("anchor")),layer.getDouble("widthInAnchorSpans").toFloat(),
                    coordinate(pivot,0),coordinate(pivot,1),coordinate(offset,0),coordinate(offset,1),
                    layer.getDouble("rotationDegrees").toFloat())
            }
            return StickerResourceManifest(root.strictString("id"),root.strictString("name"),layers,root.strictString("preview"))
        }
    }
}

/** 一次性读取、校验并解码的资源包。必须在IO线程加载；配置调整复用图片，不逐帧解码。
 * ZIP只读入有界内存，不解压到文件系统。保留原PNG供导出；不含用户相机帧。
 */
class StickerResourcePack private constructor(
    val manifest: StickerResourceManifest,
    private val encoded: Map<String,ByteArray>,
    private val images: Map<String,StickerImage>,
) {
    /** 生成SDK可直接使用的贴纸；重复调用只创建小参数对象，图片对象保持不变。 */
    fun toStickerPack(): StickerPack = StickerPack(manifest.layers.map { layer ->
        ImageSticker(images.getValue(layer.image),layer.anchor,layer.widthInAnchorSpans,
            layer.pivotX,layer.pivotY,layer.offsetX,layer.offsetY,layer.rotationDegrees)
    })

    /** 修改布局而不复制/重新解码图片；v1调参不能改变资源引用和层的身份。 */
    fun withManifest(value: StickerResourceManifest): StickerResourcePack {
        require(value.id==manifest.id && value.preview==manifest.preview &&
            value.layers.map { it.id to it.image }==manifest.layers.map { it.id to it.image }) { "Layout edits cannot replace assets" }
        return StickerResourcePack(value,encoded,images)
    }

    /** 预览PNG独占副本，仅供列表/UI按需解码，不得在视频帧回调里调用。 */
    fun previewPng(): ByteArray = encoded.getValue(manifest.preview).copyOf()

    /** 导出包含最新配置和原始PNG的完整ZIP，方法返回时关闭输出流。 */
    fun writeZip(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val entries=linkedMapOf("manifest.json" to manifest.toJson().toByteArray(Charsets.UTF_8))
            entries.putAll(encoded)
            entries.forEach { (name,bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    companion object {
        private const val MAX_BYTES=16*1024*1024
        private const val MAX_IMAGE_BYTES=4*1024*1024
        private const val MAX_PIXELS=4_194_304L

        /** 读取并关闭ZIP输入流。只接受根目录manifest及其引用PNG，拒绝目录、重复条目和路径穿越。 */
        @JvmStatic fun loadZip(input: InputStream): StickerResourcePack {
            val entries=readEntries(input)
            val description=entries.remove("manifest.json") ?: error("Missing manifest.json")
            val manifest=StickerResourceManifest.parse(description.toString(Charsets.UTF_8))
            val references=(manifest.layers.map { it.image }+manifest.preview).toSet()
            require(entries.keys==references) { "Missing or unreferenced PNG" }
            var pixels=0L
            // 先检查所有图片尺寸再解码，避免先分配大Bitmap才发现资源超限。
            entries.values.forEach { bytes ->
                validateStaticPng(bytes)
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outMimeType=="image/png" && bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "PNG dimensions must be 1 to 2048" }
                pixels+=bounds.outWidth.toLong()*bounds.outHeight
                require(pixels<=MAX_PIXELS) { "Decoded pixel budget exceeded" }
            }
            val images=manifest.layers.map { it.image }.distinct().associateWith { name ->
                val bytes=entries.getValue(name)
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                    BitmapFactory.Options().apply { inScaled=false; inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888 })) { "PNG decode failed" }
                try {
                    val colors=IntArray(bitmap.width*bitmap.height)
                    // getPixels输出非预乘ARGB，与现有CPU/GL source-over契约保持一致。
                    bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    StickerImage.fromOwnedPixels(bitmap.width,bitmap.height,colors)
                } finally { bitmap.recycle() }
            }
            return StickerResourcePack(manifest,entries.toMap(),images)
        }

        /** V1/V2共用有界ZIP读取，不向磁盘解压。 */
        internal fun readEntries(input: InputStream): LinkedHashMap<String,ByteArray> {
            val entries=linkedMapOf<String,ByteArray>()
            var total=0
            val limited=object:FilterInputStream(input) {
                private var count=0L
                override fun read(): Int = super.read().also { if(it>=0) checkCount(1) }
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int =
                    `in`.read(bytes,offset,length).also { if(it>0) checkCount(it) }
                private fun checkCount(amount:Int) { count+=amount; require(count<=MAX_BYTES) { "ZIP input too large" } }
            }
            ZipInputStream(limited).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(!entry.isDirectory && entries.size<5) { "Unexpected ZIP entry" }
                    val name=entry.name
                    require(name=="manifest.json" || isPngName(name)) { "Only root PNG files and manifest.json are allowed" }
                    require(!entries.containsKey(name)) { "Duplicate ZIP entry" }
                    val limit=if(name=="manifest.json") 64*1024 else MAX_IMAGE_BYTES
                    val bytes=readLimited(zip,limit)
                    total+=bytes.size; require(total<=MAX_BYTES) { "ZIP content too large" }
                    entries[name]=bytes; zip.closeEntry()
                }
            }
            return entries
        }

        /** 校验PNG块边界与CRC；v1不接受APNG，避免把动画悄悄显示成第一帧。 */
        fun validateStaticPng(bytes:ByteArray) {
            require(bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))) { "Expected PNG signature" }
            fun uint(index:Int):Long = (0..3).fold(0L) { value,offset -> (value shl 8) or (bytes[index+offset].toLong() and 255) }
            var offset=8
            while(offset.toLong()+12<=bytes.size) {
                val length=uint(offset)
                require(length<=bytes.size.toLong()-offset-12) { "Invalid PNG chunk" }
                val count=length.toInt()
                val type=String(bytes,offset+4,4,Charsets.US_ASCII)
                require(type !in setOf("acTL","fcTL","fdAT")) { "APNG is not supported by schemaVersion 1" }
                if(offset==8) require(type=="IHDR" && count==13) { "Missing PNG header" }
                val crc=CRC32().apply { update(bytes,offset+4,count+4) }.value
                require(crc==uint(offset+8+count)) { "PNG checksum mismatch" }
                offset+=count+12
                if(type=="IEND") {
                    require(count==0 && offset==bytes.size) { "Invalid PNG end" }
                    return
                }
            }
            error("Missing PNG end")
        }

        private fun readLimited(input:InputStream,limit:Int):ByteArray {
            val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                require(output.size().toLong()+count<=limit) { "ZIP entry too large" }
                output.write(buffer,0,count)
            }
            return output.toByteArray()
        }
    }
}

private fun isPngName(value:String)=value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\\.png")) && !value.contains("..")
private fun requirePngName(value:String) { require(isPngName(value)) { "Expected a root PNG filename" } }
private fun JSONObject.strictString(key:String):String { require(get(key) is String) { "Expected text: $key" }; return getString(key) }
private fun JSONObject.requireKeys(expected:Set<String>) {
    val actual=keys().asSequence().toSet()
    require(actual==expected) { "Unexpected or missing manifest fields" }
}
