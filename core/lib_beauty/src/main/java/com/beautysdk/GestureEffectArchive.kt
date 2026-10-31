package com.beautysdk

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream

/** IO线程严格读包；预算预检后一次解码，不在帧回调读取ZIP或Bitmap。 */
object GestureEffectArchive {
    fun loadZip(input:InputStream):GestureEffectPack = loadZip(input,8388608L)

    /** 组合加载时传入剩余像素预算；在分配Bitmap/ARGB前拒绝，不改变旧入口。 */
    fun loadZip(input:InputStream,maxPixels:Long):GestureEffectPack {
        require(maxPixels in 0..8388608L){"GESTURE_PIXEL_BUDGET"}
        val files=linkedMapOf<String,ByteArray>();var total=0
        ZipInputStream(input).use{zip->while(true){val e=zip.nextEntry?:break
            require(!e.isDirectory && e.name.matches(Regex("gestures\\.json|asset_[0-6]\\.png")) && e.name !in files && files.size<8)
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);val limit=if(e.name=="gestures.json")65536 else 4194304
            while(true){val n=zip.read(buffer);if(n<0)break;total+=n;require(out.size()+n<=limit&&total<=33554432);out.write(buffer,0,n)}
            files[e.name]=out.toByteArray()
        }}
        val root=JSONObject(requireNotNull(files["gestures.json"]).toString(Charsets.UTF_8))
        root.exact("format","version","rules");require(root.get("format")=="beauty-gesture-effects"&&root.int("version",1,1)==1)
        val rules=root.getJSONArray("rules");require(rules.length() in 1..7)
        val refs=mutableSetOf("gestures.json");var pixels=0L
        for(i in 0 until rules.length()){
            val r=rules.getJSONObject(i);r.exact("id","gesture","kind","image","frameWidth","frameHeight","columns","frameCount","fps","durationMs","position","width")
            val name=r.getString("image");require(name.matches(Regex("asset_[0-6]\\.png")))
            if(refs.add(name)){
                val bytes=requireNotNull(files[name]);StickerResourcePack.validateStaticPng(bytes)
                val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,b)
                require(b.outWidth in 1..2048 && b.outHeight in 1..2048 && b.outMimeType=="image/png")
                pixels+=b.outWidth.toLong()*b.outHeight;require(pixels<=maxPixels){"GESTURE_PIXEL_BUDGET"}
            }
        }
        require(files.keys==refs)
        val images=refs.filter{it.endsWith(".png")}.associateWith{name->
            val bytes=files.getValue(name);val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            try{val argb=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(argb,0,bitmap.width,0,0,bitmap.width,bitmap.height);StickerImage.fromOwnedPixels(bitmap.width,bitmap.height,argb)}finally{bitmap.recycle()}
        }
        return GestureEffectPack((0 until rules.length()).map{i->val r=rules.getJSONObject(i)
            val kind=r.getString("kind");require(kind in setOf("ANIMATION","STICKER"))
            val p=r.getJSONArray("position");require(p.length()==2)
            fun coordinate(index:Int):Float {val v=p.get(index);require(v is Number);return v.toDouble().also{require(it.isFinite()&&it in 0.0..1.0)}.toFloat()}
            GestureEffectRule(r.getString("id"),GestureKind.valueOf(r.getString("gesture")),images.getValue(r.getString("image")),kind=="ANIMATION",
                r.int("frameWidth",1,2048),r.int("frameHeight",1,2048),r.int("columns",1,8),r.int("frameCount",1,60),r.int("fps",1,30),r.int("durationMs",500,10000),coordinate(0),coordinate(1),
                r.get("width").also{require(it is Number && it.toDouble().isFinite() && it.toDouble() in .1..1.0)}.let{(it as Number).toFloat()})
        })
    }
    private fun JSONObject.exact(vararg fields:String){require(keys().asSequence().toSet()==fields.toSet())}
    private fun JSONObject.int(key:String,min:Int,max:Int):Int {val v=get(key);require(v is Number);val n=v.toDouble();require(n.isFinite()&&n%1==0.0&&n>=min&&n<=max);return n.toInt()}
}
