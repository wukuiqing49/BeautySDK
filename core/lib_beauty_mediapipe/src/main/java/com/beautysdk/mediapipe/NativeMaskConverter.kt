package com.beautysdk.mediapipe

import java.nio.ByteBuffer
import com.beautysdk.FrameInfo

/** 分割worker独占：直接读取原生浮点置信度，一次C++循环完成量化和逆旋转/镜像。
 * 输出仅是本实例借用缓冲，下次prepare可覆盖；发布前必须构造独立ForegroundMask快照。
 */
internal class NativeMaskConverter {
    companion object { init { System.loadLibrary("beauty_rgb_input") } }
    private var output:ByteBuffer?=null
    private var compatibleInput:ByteBuffer?=null
    private var temporalHistory:ByteBuffer?=null
    private var temporalInfo:FrameInfo?=null
    private var temporalGeneration=-1L
    private var temporalWidth=0;private var temporalHeight=0
    private var temporalRgbWidth=0;private var temporalRgbHeight=0

    fun prepare(source:ByteBuffer,width:Int,height:Int,rotation:Int,mirrored:Boolean):ByteBuffer {
        temporalInfo=null
        val input=input(source,width,height,rotation)
        val destination=destination(width*height)
        check(convert(input,width,height,rotation,mirrored,destination)){"Invalid native confidence mask buffer"}
        return destination
    }

    /** 仅本worker持有历史RGB/alpha；画面变化、时间断层和换源不沿用旧边缘。 */
    fun prepareStable(source:ByteBuffer,width:Int,height:Int,info:FrameInfo,generation:Long,
        rgb:ByteBuffer,rgbWidth:Int,rgbHeight:Int,rgbStride:Int):ByteBuffer {
        val input=input(source,width,height,info.rotationDegrees)
        require(rgbWidth>0 && rgbHeight>0 && rgbStride.toLong()>=rgbWidth.toLong()*3)
        require(rgb.isDirect && rgb.remaining().toLong()>=(rgbHeight-1L)*rgbStride+rgbWidth.toLong()*3)
        val destination=destination(width*height)
        val history=temporalHistory?.takeIf{it.capacity()==width*height*4}
            ?:ByteBuffer.allocateDirect(width*height*4).also{temporalHistory=it;temporalInfo=null}
        val old=temporalInfo
        val valid=old!=null && temporalGeneration==generation && width==temporalWidth && height==temporalHeight &&
            rgbWidth==temporalRgbWidth && rgbHeight==temporalRgbHeight &&
            old.copy(timestampNs=info.timestampNs)==info && info.timestampNs-old.timestampNs in 1..150_000_000L
        check(convertStable(input,width,height,info.rotationDegrees,info.mirrored,destination,
            rgb.slice(),rgbWidth,rgbHeight,rgbStride,history,valid)){"Invalid temporal mask buffers"}
        temporalInfo=info;temporalGeneration=generation;temporalWidth=width;temporalHeight=height
        temporalRgbWidth=rgbWidth;temporalRgbHeight=rgbHeight
        return destination
    }

    private fun input(source:ByteBuffer,width:Int,height:Int,rotation:Int):ByteBuffer {
        require(width in 1..2048 && height in 1..2048)
        require(rotation in listOf(0,90,180,270))
        val count=width*height
        require(source.remaining().toLong()==count.toLong()*4)
        // slice使JNI基址与当前position一致；通常直接借用MediaPipe输出，不复制浮点图。
        return if(source.isDirect)source.slice() else {
            val storage=compatibleInput?.takeIf{it.capacity()==count*4}
                ?:ByteBuffer.allocateDirect(count*4).also{compatibleInput=it}
            storage.clear();storage.put(source.duplicate());storage.flip();storage
        }
    }
    private fun destination(count:Int):ByteBuffer {
        val destination=output?.takeIf{it.capacity()==count}
            ?:ByteBuffer.allocateDirect(count).also{output=it}
        destination.clear()
        return destination
    }

    /** 不保留模型输出指针；关闭时释放本实例对复用缓冲的引用。 */
    fun clear(){output=null;compatibleInput=null;temporalHistory=null;temporalInfo=null}
    private external fun convert(source:ByteBuffer,width:Int,height:Int,rotation:Int,mirrored:Boolean,destination:ByteBuffer):Boolean
    private external fun convertStable(source:ByteBuffer,width:Int,height:Int,rotation:Int,mirrored:Boolean,destination:ByteBuffer,
        rgb:ByteBuffer,rgbWidth:Int,rgbHeight:Int,rgbStride:Int,history:ByteBuffer,useHistory:Boolean):Boolean
}
