package com.beautysdk

import java.util.Collections

/** 关键帧中心为正向显示坐标，缩放保持输入宽高比；easing控制到下一帧的插值。 */
enum class MotionEasing { LINEAR, SMOOTH }
data class MotionKeyframe(val timeMs: Long, val x: Float, val y: Float, val zoom: Float,
    val easing: MotionEasing = MotionEasing.SMOOTH) {
    init { require(timeMs in 0..60000 && x in 0f..1f && y in 0f..1f && zoom in 1f..2f) }
}

/** 不可变、无状态的时间轴。非循环停留最后一帧；循环包必须首尾闭合，避免跳变。 */
class MotionTimeline(frames: List<MotionKeyframe>, val loop: Boolean = true) {
    val frames: List<MotionKeyframe> = Collections.unmodifiableList(frames.toList())
    init {
        require(frames.size in 2..32 && frames.first().timeMs == 0L)
        require(frames.zipWithNext().all { (a,b) -> b.timeMs > a.timeMs })
        require(!loop || frames.first().let { a -> frames.last().let { b -> a.x==b.x && a.y==b.y && a.zoom==b.zoom } })
    }
    /** elapsedMs来自帧时间戳差，不使用系统墙钟，也不修改共享配置。 */
    fun sample(elapsedMs: Long): MotionKeyframe {
        val duration=frames.last().timeMs
        val t=if(loop) elapsedMs.coerceAtLeast(0)%duration else elapsedMs.coerceIn(0,duration)
        val i=frames.indexOfLast { it.timeMs<=t }.coerceAtMost(frames.lastIndex-1)
        val a=frames[i];val b=frames[i+1]
        val u=(t-a.timeMs).toFloat()/(b.timeMs-a.timeMs)
        val v=if(a.easing==MotionEasing.SMOOTH) u*u*(3-2*u) else u
        return MotionKeyframe(t,a.x+(b.x-a.x)*v,a.y+(b.y-a.y)*v,a.zoom+(b.zoom-a.zoom)*v,a.easing)
    }
}
