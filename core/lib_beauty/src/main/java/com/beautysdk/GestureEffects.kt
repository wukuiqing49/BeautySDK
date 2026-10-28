package com.beautysdk

import java.util.Collections

/** 一次触发的画面坐标素材。位置为旋转/镜像后的画面归一化中心，width为画面宽度比例。 */
data class GestureEffectRule(val id:String,val gesture:GestureKind,val image:StickerImage,
    val animated:Boolean,val frameWidth:Int,val frameHeight:Int,val columns:Int,val frameCount:Int,
    val fps:Int,val durationMs:Int,val x:Float=.5f,val y:Float=.5f,val width:Float=.6f) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,47}")))
        require(gesture in HandObservation.MODEL_GESTURES)
        require(frameWidth in 1..2048 && frameHeight in 1..2048 && columns in 1..8 && frameCount in 1..60)
        require(image.width==columns*frameWidth && image.height==((frameCount+columns-1)/columns)*frameHeight)
        require(fps in 1..30 && durationMs in 500..10000)
        require(!animated && frameCount==1 && columns==1 || animated && frameCount>=2 && durationMs==(frameCount*1000+fps-1)/fps)
        require(x.isFinite()&&y.isFinite()&&width.isFinite()&&x in 0f..1f&&y in 0f..1f&&width in .1f..1f)
    }
}
class GestureEffectPack(rules:List<GestureEffectRule>) {
    val rules:List<GestureEffectRule> = Collections.unmodifiableList(rules.toList())
    init { require(rules.size in 1..7 && rules.map{it.id}.toSet().size==rules.size && rules.map{it.gesture}.toSet().size==rules.size) }
}
data class GestureEffectFrame(val rule:GestureEffectRule,val index:Int)
/** 单渲染线程消费事件；全局单效果、播放中忽略、800ms冷却，不因保持姿势重播。 */
class GestureEffectPlayer {
    private var pack:GestureEffectPack?=null
    private var active:GestureEffectRule?=null
    private var started=0L;private var lastId=-1L;private var lastAccepted=Long.MIN_VALUE
    fun configure(value:GestureEffectPack?) {if(pack!==value){reset();pack=value}}
    fun reset(){active=null;started=0;lastId=-1;lastAccepted=Long.MIN_VALUE}
    fun accept(events:List<GestureEvent>,timestampNs:Long) {
        frame(timestampNs)
        for(event in events){
            if(event.id<=lastId)continue
            lastId=event.id
            if(event.phase!=GestureEventPhase.TRIGGER || event.timestampNs>timestampNs || timestampNs-event.timestampNs>300_000_000L)continue
            val rule=pack?.rules?.firstOrNull{it.gesture==event.kind}?:continue
            if(active!=null || lastAccepted!=Long.MIN_VALUE&&timestampNs-lastAccepted<800_000_000L)continue
            active=rule;started=timestampNs;lastAccepted=timestampNs
        }
    }
    fun frame(timestampNs:Long):GestureEffectFrame? {
        val rule=active?:return null
        val elapsed=timestampNs-started
        if(elapsed<0 || elapsed>=rule.durationMs*1_000_000L){active=null;return null}
        return GestureEffectFrame(rule,if(rule.animated)minOf(rule.frameCount-1,(elapsed*rule.fps/1_000_000_000L).toInt())else 0)
    }
}
