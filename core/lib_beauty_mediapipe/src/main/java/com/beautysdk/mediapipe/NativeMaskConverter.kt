package com.beautysdk.mediapipe

import java.nio.ByteBuffer

/** 分割worker独占：直接读取原生浮点置信度，一次C++循环完成量化和逆旋转/镜像。
 * 输出仅是本实例借用缓冲，下次prepare可覆盖；发布前必须构造独立ForegroundMask快照。
 */
internal class NativeMaskConverter {
    companion object { init { System.loadLibrary("beauty_rgb_input") } }
    private var output:ByteBuffer?=null
    private var compatibleInput:ByteBuffer?=null

    fun prepare(source:ByteBuffer,width:Int,height:Int,rotation:Int,mirrored:Boolean):ByteBuffer {
        require(width in 1..2048 && height in 1..2048)
        require(rotation in listOf(0,90,180,270))
        val count=width*height
        require(source.remaining().toLong()==count.toLong()*4)
        // slice使JNI基址与当前position一致；通常直接借用MediaPipe输出，不复制浮点图。
        val input=if(source.isDirect)source.slice() else {
            val storage=compatibleInput?.takeIf{it.capacity()==count*4}
                ?:ByteBuffer.allocateDirect(count*4).also{compatibleInput=it}
            storage.clear();storage.put(source.duplicate());storage.flip();storage
        }
        val destination=output?.takeIf{it.capacity()==count}
            ?:ByteBuffer.allocateDirect(count).also{output=it}
        destination.clear()
        check(convert(input,width,height,rotation,mirrored,destination)){"Invalid native confidence mask buffer"}
        return destination
    }

    /** 不保留模型输出指针；关闭时释放本实例对复用缓冲的引用。 */
    fun clear(){output=null;compatibleInput=null}
    private external fun convert(source:ByteBuffer,width:Int,height:Int,rotation:Int,mirrored:Boolean,destination:ByteBuffer):Boolean
}
