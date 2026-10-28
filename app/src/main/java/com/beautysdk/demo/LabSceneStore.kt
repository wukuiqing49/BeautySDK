package com.beautysdk.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.beautysdk.*
import com.beautysdk.resources.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** 宿主的手动场景偏好：只保存配置及资源图片，不保存相机帧，不修改公共资源包协议。
 * 仅在串行IO队列调用；图片按摘要复用，拖动运镜滑杆不会反复编码图片。
 */
internal class LabSceneStore(private val folder:File) {
    private val snapshot=AtomicFile(File(folder,"scene-state.json"))
    private var cachedImage:StickerImage?=null
    private var cachedHash:String?=null

    fun read(state:BeautyResourceState,installed:List<InstalledResource>):Pair<BeautyScene?,BeautyScene?> {
        if(!snapshot.baseFile.exists()&&!File(snapshot.baseFile.path+".bak").exists())return null to null
        val bytes=snapshot.openRead().use{it.readBytes()};require(bytes.size<=32768)
        val json=JSONObject(bytes.toString(Charsets.UTF_8));require(json.getInt("version")==1)
        fun restore(key:String,category:ResourceCategory):BeautyScene? {
            val saved=json.optJSONObject(key)?:return null
            // 独立资源页改变了该类别/主题后，不允许旧手动值覆盖新的资源选择。
            val anchors=saved.getJSONObject("selection")
            val actual=selection(state,installed,category)
            val expected=anchors.keys().asSequence().associateWith {if(anchors.isNull(it))null else anchors.getString(it)}
            if(expected!=actual)return null
            val c=saved.getJSONObject("config")
            return if(category==ResourceCategory.BACKGROUND) {
                val mode=BackgroundMode.valueOf(c.getString("mode"))
                val image=if(mode==BackgroundMode.IMAGE)loadImage(c.getString("image"))else null
                BeautyScene(background=mode,backgroundColor=c.getLong("color").toInt(),backgroundImage=image,
                    blurRadius=c.getDouble("blur").toFloat(),backgroundFocusX=c.getDouble("focusX").toFloat(),backgroundFocusY=c.getDouble("focusY").toFloat())
            } else {
                val mode=CameraMotion.valueOf(c.getString("mode"))
                val timeline=c.optJSONObject("timeline")?.let {t->val frames=t.getJSONArray("frames");require(frames.length() in 2..32)
                    MotionTimeline((0 until frames.length()).map {i->val f=frames.getJSONObject(i)
                        MotionKeyframe(f.getLong("time"),f.getDouble("x").toFloat(),f.getDouble("y").toFloat(),f.getDouble("zoom").toFloat(),MotionEasing.valueOf(f.getString("easing")))},t.getBoolean("loop"))}
                BeautyScene(motion=mode,zoom=c.getDouble("zoom").toFloat(),periodMs=c.getLong("period"),timeline=timeline)
            }
        }
        return restore("background",ResourceCategory.BACKGROUND) to restore("motion",ResourceCategory.MOTION)
    }

    fun write(state:BeautyResourceState,installed:List<InstalledResource>,background:BeautyScene?,motion:BeautyScene?) {
        check(folder.isDirectory||folder.mkdirs())
        val json=JSONObject().put("version",1)
        background?.let {scene->
            val config=JSONObject().put("mode",scene.background.name).put("color",scene.backgroundColor.toLong())
                .put("blur",scene.blurRadius).put("focusX",scene.backgroundFocusX).put("focusY",scene.backgroundFocusY)
            if(scene.background==BackgroundMode.IMAGE)config.put("image",saveImage(requireNotNull(scene.backgroundImage)))
            json.put("background",entry(state,installed,ResourceCategory.BACKGROUND,config))
        }
        motion?.let {scene->
            val config=JSONObject().put("mode",scene.motion.name).put("zoom",scene.zoom).put("period",scene.periodMs)
            scene.timeline?.let {timeline->config.put("timeline",JSONObject().put("loop",timeline.loop).put("frames",JSONArray(timeline.frames.map {f->
                JSONObject().put("time",f.timeMs).put("x",f.x).put("y",f.y).put("zoom",f.zoom).put("easing",f.easing.name)})))}
            json.put("motion",entry(state,installed,ResourceCategory.MOTION,config))
        }
        val stream=snapshot.startWrite()
        try {stream.write(json.toString().toByteArray(Charsets.UTF_8));snapshot.finishWrite(stream)}
        catch(t:Throwable){snapshot.failWrite(stream);throw t}
        // 已提交快照只引用一张背景；清理旧的宿主资源副本，避免反复换背景积累文件。
        val retained=json.optJSONObject("background")?.getJSONObject("config")?.optString("image")
        File(folder,"scene-images").listFiles().orEmpty().filter {it.name!="$retained.png"}.forEach {it.delete()}
    }

    private fun selection(state:BeautyResourceState,installed:List<InstalledResource>,category:ResourceCategory):Map<String,String?> {
        val slots=installed.filter{it.descriptor.category==category}.map{it.descriptor.slot}.toSet()+"theme"
        return state.selections.filterKeys{it in slots}
    }
    private fun entry(state:BeautyResourceState,installed:List<InstalledResource>,category:ResourceCategory,config:JSONObject)=
        JSONObject().put("selection",JSONObject(selection(state,installed,category).mapValues{it.value?:JSONObject.NULL})).put("config",config)
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun saveImage(image:StickerImage):String {
        cachedHash?.let {if(cachedImage===image&&File(folder,"scene-images/$it.png").isFile)return it}
        require(image.width.toLong()*image.height<=4_194_304)
        val colors=image.copyArgb()
        val bitmap=Bitmap.createBitmap(colors,image.width,image.height,Bitmap.Config.ARGB_8888)
        val bytes=try{ByteArrayOutputStream().also{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()}finally{bitmap.recycle()}
        val hash=digest(bytes);val dir=File(folder,"scene-images");check(dir.isDirectory||dir.mkdirs())
        val file=AtomicFile(File(dir,"$hash.png"));val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(t:Throwable){file.failWrite(stream);throw t}
        cachedImage=image;cachedHash=hash;return hash
    }
    private fun loadImage(hash:String):StickerImage {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val file=File(folder,"scene-images/$hash.png");require(file.length() in 1..20_000_000)
        val bytes=file.readBytes();require(digest(bytes)==hash)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048&&bounds.outHeight in 1..2048&&bounds.outWidth.toLong()*bounds.outHeight<=4_194_304)
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inScaled=false}))
        return try{val colors=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(colors,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            StickerImage(bitmap.width,bitmap.height,colors).also{cachedImage=it;cachedHash=hash}}finally{bitmap.recycle()}
    }
}
