package com.beautysdk

import android.graphics.BitmapFactory
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** V2独立效果类别；贴纸继续使用V1，不将背景、调色混入贴纸层。 */
enum class EffectResourceType { BACKGROUND, MOTION, BEAUTY }

/** 已校验的资源包。IO线程加载一次；applyTo仅合并所属类别，保留其他已选择的效果。 */
class EffectResourcePack private constructor(
    val id: String, val name: String, val type: EffectResourceType,
    private val description: String, private val encoded: Map<String,ByteArray>,
    private val scene: BeautyScene?, private val beauty: BeautySettings?,
) {
    val requiresExtendedBeauty: Boolean get() = beauty?.adjustments?.isIdentity == false
    fun previewPng(): ByteArray = encoded.getValue(JSONObject(description).getString("preview")).copyOf()
    /** 不创建图像缓冲、不执行推理；应在配置变化时调用，勿逐帧调用。 */
    fun applyTo(base: BeautySettings): BeautySettings = when(type) {
        EffectResourceType.BACKGROUND -> base.copy(scene=base.scene.copy(
            background=scene!!.background,backgroundColor=scene.backgroundColor,backgroundImage=scene.backgroundImage,
            blurRadius=scene.blurRadius,backgroundFocusX=scene.backgroundFocusX,backgroundFocusY=scene.backgroundFocusY))
        EffectResourceType.MOTION -> base.copy(scene=base.scene.copy(
            motion=scene!!.motion,zoom=scene.zoom,periodMs=scene.periodMs,timeline=scene.timeline))
        EffectResourceType.BEAUTY -> base.copy(smoothing=beauty!!.smoothing,whitening=beauty.whitening,
            filter=beauty.filter,adjustments=beauty.adjustments,eyeMakeup=beauty.eyeMakeup)
    }
    /** 保留原始素材，便于跨端分发；关闭输出流。 */
    fun writeZip(output: OutputStream) = ZipOutputStream(output).use { zip ->
        (mapOf("manifest.json" to description.toByteArray(Charsets.UTF_8))+encoded).forEach { (name,bytes) ->
            zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
        }
    }
    companion object {
        /** 严格字段、类型、文件引用和像素预算；拒绝包内脚本、路径、未知版本和动画PNG。 */
        @JvmStatic fun loadZip(input: InputStream): EffectResourcePack {
            val entries=StickerResourcePack.readEntries(input)
            val text=(entries.remove("manifest.json") ?: error("Missing manifest.json")).toString(Charsets.UTF_8)
            val root=JSONObject(text)
            root.fields("schemaVersion","type","id","name","preview","config")
            val version=root.integer("schemaVersion",2,4)
            val type=EffectResourceType.valueOf(root.text("type"))
            require(version==2L || type==EffectResourceType.BEAUTY) { "V3/V4 only support BEAUTY" }
            val id=root.text("id");val name=root.text("name");val preview=root.text("preview")
            require(id.matches(Regex("[a-z][a-z0-9_]{0,47}")))
            require(name.isNotBlank() && name.length<=80 && name.none { it.isISOControl() })
            val c=root.getJSONObject("config");var scene:BeautyScene?=null;var beauty:BeautySettings?=null
            val refs=mutableSetOf(preview)
            val imageName=if(type==EffectResourceType.BACKGROUND) {
                c.fields("mode","image","color","blurRadius","focus")
                if(c.isNull("image")) null else c.text("image").also { refs.add(it) }
            } else null
            val eye=if(version==4L) c.getJSONObject("eyeMakeup").also {
                it.fields("kind","image","root","length","mirrorRight")
                require(it.text("kind")=="EYELASH")
                refs.add(it.text("image"))
            } else null
            require(entries.keys==refs) { "Missing or unreferenced PNG" }
            var pixels=0L
            entries.values.forEach { bytes ->
                StickerResourcePack.validateStaticPng(bytes)
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outMimeType=="image/png")
                pixels+=bounds.outWidth.toLong()*bounds.outHeight;require(pixels<=4_194_304)
            }
            when(type) {
                EffectResourceType.BACKGROUND -> {
                    val mode=BackgroundMode.valueOf(c.text("mode"));require(mode!=BackgroundMode.NONE)
                    require((mode==BackgroundMode.IMAGE)==(imageName!=null))
                    val color=c.text("color");require(color.matches(Regex("#[0-9a-fA-F]{6}")))
                    val focus=c.getJSONArray("focus");require(focus.length()==2)
                    val image=imageName?.let {
                        val bytes=entries.getValue(it)
                        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inScaled=false }))
                        try { val colors=IntArray(bitmap.width*bitmap.height)
                            bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                            StickerImage.fromOwnedPixels(bitmap.width,bitmap.height,colors)
                        } finally { bitmap.recycle() }
                    }
                    scene=BeautyScene(background=mode,backgroundColor=(0xff000000L or color.drop(1).toLong(16)).toInt(),
                        backgroundImage=image,blurRadius=c.number("blurRadius",0f,32f),
                        backgroundFocusX=focus.coordinate(0),backgroundFocusY=focus.coordinate(1))
                }
                EffectResourceType.MOTION -> {
                    c.fields("mode","zoom","periodMs","timeline")
                    val mode=CameraMotion.valueOf(c.text("mode"));require(mode!=CameraMotion.NONE)
                    val period=c.integer("periodMs",1000,60000)
                    val timeline=if(c.isNull("timeline")) null else {
                        val t=c.getJSONObject("timeline");t.fields("loop","frames");require(t.get("loop") is Boolean)
                        val frames=t.getJSONArray("frames");require(frames.length() in 2..32)
                        MotionTimeline((0 until frames.length()).map { i ->
                            val f=frames.getJSONObject(i);f.fields("timeMs","x","y","zoom","easing")
                            val time=f.integer("timeMs",0,60000)
                            MotionKeyframe(time,f.number("x",0f,1f),f.number("y",0f,1f),f.number("zoom",1f,2f),MotionEasing.valueOf(f.text("easing")))
                        },t.getBoolean("loop"))
                    }
                    require((mode==CameraMotion.KEYFRAMES)==(timeline!=null))
                    scene=BeautyScene(motion=mode,zoom=c.number("zoom",1f,2f),periodMs=period,timeline=timeline)
                }
                EffectResourceType.BEAUTY -> {
                    c.fields(*(listOf("smoothing","whitening","filter","adjustments")+if(version==4L) listOf("eyeMakeup") else emptyList()).toTypedArray())
                    val a=c.getJSONObject("adjustments")
                    val adjustmentNames=listOf("rosy","brightness","contrast","saturation","sharpening","slimFace","bigEyes") +
                        (if(version>=3L) listOf("lipstick","blusher","slimNose","chinLength","mouthSize") else emptyList()) +
                        (if(version==4L) listOf("eyelashes") else emptyList())
                    a.fields(*adjustmentNames.toTypedArray())
                    beauty=BeautySettings(c.number("smoothing",0f,1f),c.number("whitening",0f,1f),BeautyFilter.valueOf(c.text("filter")),
                        adjustments=BeautyAdjustments(a.number("rosy",0f,1f),a.number("brightness",-1f,1f),a.number("contrast",0f,2f),
                            a.number("saturation",0f,2f),a.number("sharpening",0f,1f),a.number("slimFace",0f,1f),a.number("bigEyes",0f,1f),
                            lipstick=if(version>=3L) a.number("lipstick",0f,1f) else 0f,
                            blusher=if(version>=3L) a.number("blusher",0f,1f) else 0f,
                            slimNose=if(version>=3L) a.number("slimNose",0f,1f) else 0f,
                            chinLength=if(version>=3L) a.number("chinLength",-1f,1f) else 0f,
                            mouthSize=if(version>=3L) a.number("mouthSize",-1f,1f) else 0f,
                            eyelashes=if(version==4L) a.number("eyelashes",0f,1f) else 0f),
                        eyeMakeup=eye?.let { e ->
                            val curve=e.getJSONArray("root");require(curve.length()==9)
                            val rootPoints=(0 until 9).map {i -> val point=curve.getJSONArray(i);require(point.length()==2);FacePoint(point.coordinate(0),point.coordinate(1))}
                            require(e.get("mirrorRight") is Boolean)
                            val bytes=entries.getValue(e.text("image"))
                            val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inScaled=false}))
                            try { val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                                require(colors.any {(it ushr 24)<255} && colors.any {(it ushr 24)>0}) {"Eyelash image needs transparent and visible pixels"}
                                EyeMakeup(StickerImage.fromOwnedPixels(bitmap.width,bitmap.height,colors),rootPoints,e.number("length",.02f,.5f),e.getBoolean("mirrorRight"))
                            } finally { bitmap.recycle() }
                        })
                }
            }
            return EffectResourcePack(id,name,type,text,entries.toMap(),scene,beauty)
        }
    }
}

private fun JSONObject.fields(vararg names:String) { require(keys().asSequence().toSet()==names.toSet()) { "Unknown or missing fields" } }
private fun JSONObject.text(key:String):String { require(get(key) is String);return getString(key) }
private fun JSONObject.number(key:String,min:Float,max:Float):Float {
    require(get(key) is Number);val v=getDouble(key);require(v.isFinite() && v>=min.toDouble() && v<=max.toDouble());return v.toFloat()
}
private fun JSONArray.coordinate(index:Int):Float { require(get(index) is Number);return getDouble(index).toFloat().also { require(it in 0f..1f) } }

private fun JSONObject.integer(key:String,min:Long,max:Long):Long {
    require(get(key) is Number);val v=getDouble(key)
    require(v.isFinite() && v>=min.toDouble() && v<=max.toDouble() && v%1.0==0.0)
    return v.toLong()
}
