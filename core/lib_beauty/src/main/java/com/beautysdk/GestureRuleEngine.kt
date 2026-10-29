package com.beautysdk

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import kotlin.math.*

/** 单线程规则状态机；可用于离线结果回放。自定义手型/动作是二维启发式，须按业务真人验收。
 * 使用转正后的等距坐标判形，输出仍在原始空间；不做无采样外推，不把检测顺序当跟踪ID。
 */
class GestureRuleEngine @JvmOverloads constructor(private val config: GestureConfig = GestureConfig()) {
    private data class P(val x: Float, val y: Float) {
        operator fun minus(o: P) = P(x-o.x, y-o.y)
        fun length() = hypot(x, y)
    }
    private class Shape(val hand: HandObservation, info: FrameInfo) {
        val p = hand.landmarks.map { raw ->
            val rotated = when(info.rotationDegrees) {
                90 -> FacePoint(1f-raw.y,raw.x)
                180 -> FacePoint(1f-raw.x,1f-raw.y)
                270 -> FacePoint(raw.y,1f-raw.x)
                else -> raw
            }
            val swap=info.rotationDegrees%180!=0
            val w=if(swap)info.height else info.width
            val h=if(swap)info.width else info.height
            P((if(info.mirrored)1f-rotated.x else rotated.x)*w/min(w,h),rotated.y*h/min(w,h))
        }
        val center = P((p[0].x+p[5].x+p[9].x+p[13].x+p[17].x)/5,
            (p[0].y+p[5].y+p[9].y+p[13].y+p[17].y)/5)
        val span = max((p[5]-p[17]).length(),(p[0]-p[9]).length()).coerceAtLeast(.0001f)
        fun d(a: Int,b: Int)=(p[a]-p[b]).length()/span
        private fun straight(a: Int,b: Int,c: Int): Boolean {
            val u=p[a]-p[b];val v=p[c]-p[b]
            return (u.x*v.x+u.y*v.y)/(u.length()*v.length()).coerceAtLeast(.000001f)<-.60f
        }
        val fingers = booleanArrayOf(
            straight(2,3,4) && d(4,17)>d(3,17)*1.12f,
            straight(5,6,8) && d(8,0)>d(6,0)*1.12f,
            straight(9,10,12) && d(12,0)>d(10,0)*1.12f,
            straight(13,14,16) && d(16,0)>d(14,0)*1.12f,
            straight(17,18,20) && d(20,0)>d(18,0)*1.12f)
        val pinchRatio = d(4,8)
        val rawPinch = FacePoint((hand.landmarks[4].x+hand.landmarks[8].x)/2,
            (hand.landmarks[4].y+hand.landmarks[8].y)/2)
        fun custom(): Set<GestureKind> = buildSet {
            if(span < .025f) return@buildSet
            add(GestureKind.entries[GestureKind.NUMBER_0.ordinal+fingers.count { it }])
            if(pinchRatio<.32f && fingers[2] && fingers[3] && fingers[4]) add(GestureKind.OK)
            // 捏心限定食指/拇指尖靠近、其余三指收拢，并且指尖位于掌心上方。
            if(pinchRatio<.30f && !fingers[2] && !fingers[3] && !fingers[4] &&
                p[8].y<center.y-.25f*span && p[4].y<center.y-.15f*span && d(8,0)>.8f)
                add(GestureKind.FINGER_HEART)
        }
    }
    private data class Candidate(var since: Long,var last: Long,var fired: Boolean=false,var absentSince: Long?=null)
    private data class Sample(val time: Long,val point: P)
    private class Track(val id: Long,var shape: Shape,var time: Long) {
        val candidates=mutableMapOf<GestureKind,Candidate>()
        val firedAt=mutableMapOf<GestureKind,Long>()
        val path=ArrayDeque<Sample>()
        var pathKind: GestureKind?=null
        var pinchSince: Long?=null
        var pinching=false
        var pinchBase=1f
        var lastPinch=FacePoint(0f,0f)
    }
    private var nextTrack=0L
    private var nextEvent=0L
    private var lastTime=-1L
    private var geometry: FrameInfo?=null
    private val tracks=mutableListOf<Track>()
    private val pairCandidates=mutableMapOf<GestureKind,Candidate>()
    private val pairFired=mutableMapOf<GestureKind,Long>()
    private val recentTriggers=mutableMapOf<Pair<Handedness,GestureKind>,Long>()
    private var pairIds=emptyList<Long>()
    private var clapArmed=false
    private var scaleBase: Float?=null
    private var scalePosition=FacePoint(0f,0f)

    /** 时间线/画面发生变化时调用；不复用姿势、轨迹、冷却或拖动基线。 */
    fun reset() {
        tracks.clear();pairCandidates.clear();pairFired.clear();pairIds=emptyList()
        recentTriggers.clear()
        clapArmed=false;scaleBase=null;lastTime=-1;geometry=null
    }

    fun process(info: FrameInfo,hands: List<HandObservation>): List<GestureEvent> {
        if (!BeautyLicensing.allows(LicensedFeature.GESTURE)) { reset(); return emptyList() }
        require(hands.size<=2)
        val old=geometry
        if(old!=null && (old.rotationDegrees!=info.rotationDegrees || old.mirrored!=info.mirrored ||
            abs(old.width.toDouble()/old.height-info.width.toDouble()/info.height)>.02)) reset()
        if(info.timestampNs<=lastTime) return emptyList()
        val events=mutableListOf<GestureEvent>()
        val t=info.timestampNs
        fun emit(kind: GestureKind,phase: GestureEventPhase,ids: List<Long>,pos: FacePoint,
                 score: Float?=null,scale: Float=1f,delta: FacePoint=FacePoint(0f,0f)) {
            events+=GestureEvent(++nextEvent,kind,phase,t,ids,pos,
                if(kind in HandObservation.MODEL_GESTURES) GestureSource.MODEL else GestureSource.EXPERIMENTAL_RULE,
                score,scale,delta)
        }
        if(lastTime>=0 && t-lastTime>config.maxGapNs) {
            tracks.filter { it.pinching }.forEach { emit(GestureKind.PINCH,GestureEventPhase.CANCEL,listOf(it.id),it.lastPinch) }
            if(scaleBase!=null)emit(GestureKind.TWO_HAND_SCALE,GestureEventPhase.CANCEL,pairIds,scalePosition)
            reset()
        }
        lastTime=t;geometry=info
        val remaining=tracks.filter {t-it.time<=config.maxGapNs}.toMutableList()
        val active=hands.map { hand ->
            val shape=Shape(hand,info)
            val matched=remaining.filter { previous ->
                val a=previous.shape.hand;val b=shape.hand
                val known=a.handednessScore>=.7f && b.handednessScore>=.7f &&
                    a.handedness!=Handedness.UNKNOWN && b.handedness!=Handedness.UNKNOWN
                (!known || a.handedness==b.handedness) &&
                    (previous.shape.center-shape.center).length()<max(previous.shape.span,shape.span)*2f
            }.minByOrNull { (it.shape.center-shape.center).length() }
            if(matched!=null) {remaining.remove(matched);matched.shape=shape;matched.time=t;matched}
            else Track(++nextTrack,shape,t)
        }
        remaining.filter { it.pinching }.forEach {
            emit(GestureKind.PINCH,GestureEventPhase.CANCEL,listOf(it.id),it.lastPinch)
            it.pinching=false;it.pinchSince=null
        }
        // 短暂丢手保留静态触发锁存，避免恢复检测后把仍保持的手型再次触发。
        // 交互和动作轨迹不能跨越无手采样，恢复后必须重新稳定。
        remaining.forEach {it.pinchSince=null;it.path.clear();it.pathKind=null}
        tracks.clear();tracks.addAll(active)
        tracks.addAll(remaining.sortedByDescending {it.time}.take(2-active.size))
        for(track in active) {
            val s=track.shape
            val candidates=mutableSetOf<GestureKind>()
            if(s.hand.modelScore>=config.minModelScore)s.hand.modelGesture?.let(candidates::add)
            if(config.experimentalRules)candidates+=s.custom()
            for(kind in candidates) {
                if(ready(kind,t,track.candidates,track.firedAt,s.hand.handedness))
                    emit(kind,GestureEventPhase.TRIGGER,listOf(track.id),s.rawPinch,
                        if(kind in HandObservation.MODEL_GESTURES)s.hand.modelScore else null)
            }
            expire(candidates,t,track.candidates)
            if(!config.experimentalRules)continue
            if(s.span<.025f) {
                if(track.pinching)emit(GestureKind.PINCH,GestureEventPhase.CANCEL,listOf(track.id),track.lastPinch)
                track.pinching=false;track.pinchSince=null;track.path.clear();track.pathKind=null
                continue
            }
            val pinched=s.pinchRatio < if(track.pinching) .43f else .28f
            if(pinched) {
                val since=track.pinchSince?:t.also { track.pinchSince=it }
                if(!track.pinching && t-since>=config.stableNs) {
                    track.pinching=true;track.pinchBase=s.pinchRatio.coerceAtLeast(.08f);track.lastPinch=s.rawPinch
                    emit(GestureKind.PINCH,GestureEventPhase.START,listOf(track.id),s.rawPinch)
                } else if(track.pinching) {
                    val delta=FacePoint(s.rawPinch.x-track.lastPinch.x,s.rawPinch.y-track.lastPinch.y)
                    emit(GestureKind.PINCH,GestureEventPhase.UPDATE,listOf(track.id),s.rawPinch,
                        scale=(s.pinchRatio/track.pinchBase).coerceIn(.1f,5f),delta=delta)
                    track.lastPinch=s.rawPinch
                }
            } else {
                if(track.pinching)emit(GestureKind.PINCH,GestureEventPhase.END,listOf(track.id),s.rawPinch)
                track.pinching=false;track.pinchSince=null
            }
            // 开掌挥手、食指画圈均要求连续有效采样；手型变化立即清除轨迹。
            val moving=GestureKind.OPEN_PALM in candidates || GestureKind.POINTING_UP in candidates
            if(moving) {
                val mode=if(GestureKind.POINTING_UP in candidates)GestureKind.CIRCLE else GestureKind.WAVE
                if(track.pathKind!=mode){track.path.clear();track.pathKind=mode}
                val point=if(GestureKind.POINTING_UP in candidates)s.p[8] else s.center
                track.path.addLast(Sample(t,point))
                while(track.path.size>32 || track.path.firstOrNull()?.let {t-it.time>1_800_000_000L}==true)track.path.removeFirst()
                if(GestureKind.OPEN_PALM in candidates && wave(track.path,s.span) && cooled(GestureKind.WAVE,t,track.firedAt)) {
                    emit(GestureKind.WAVE,GestureEventPhase.TRIGGER,listOf(track.id),s.hand.landmarks[0]);track.path.clear()
                }
                if(GestureKind.POINTING_UP in candidates && circle(track.path,s.span) && cooled(GestureKind.CIRCLE,t,track.firedAt)) {
                    emit(GestureKind.CIRCLE,GestureEventPhase.TRIGGER,listOf(track.id),s.hand.landmarks[8]);track.path.clear()
                }
            } else {track.path.clear();track.pathKind=null}
        }
        val ids=active.map { it.id }.sorted()
        if(ids!=pairIds) {
            if(scaleBase!=null)emit(GestureKind.TWO_HAND_SCALE,GestureEventPhase.CANCEL,pairIds,scalePosition)
            // 双手短暂漏检也保留静态锁存；实际换手由新trackIds清除。
            if(active.size==2 || pairIds.size!=2) {
                pairIds=ids;pairCandidates.clear();pairFired.clear()
            }
            clapArmed=false;scaleBase=null
        }
        if(active.size==2 && config.experimentalRules) {
            val a=active[0].shape;val b=active[1].shape
            val unit=(a.span+b.span)/2
            val gap=(a.center-b.center).length()/unit
            val pos=FacePoint((a.rawPinch.x+b.rawPinch.x)/2,(a.rawPinch.y+b.rawPinch.y)/2)
            val kinds=mutableSetOf<GestureKind>()
            val thumbGap=(a.p[4]-b.p[4]).length()/unit
            val indexGap=(a.p[8]-b.p[8]).length()/unit
            if(thumbGap<.65f && indexGap<.65f && gap in .65f..2.8f &&
                a.p[8].y<a.p[4].y-.2f*unit && b.p[8].y<b.p[4].y-.2f*unit &&
                a.d(4,8)>.5f && b.d(4,8)>.5f)kinds+=GestureKind.TWO_HAND_HEART
            if(a.fingers.drop(1).all { it } && b.fingers.drop(1).all { it } && gap<.8f &&
                (a.p[12]-b.p[12]).length()/unit<.6f && (a.p[0]-b.p[0]).length()/unit<.8f)
                kinds+=GestureKind.PRAYER
            for(kind in kinds)if(ready(kind,t,pairCandidates,pairFired,Handedness.UNKNOWN))
                emit(kind,GestureEventPhase.TRIGGER,ids,pos)
            expire(kinds,t,pairCandidates)
            val palms=a.hand.modelGesture==GestureKind.OPEN_PALM && b.hand.modelGesture==GestureKind.OPEN_PALM &&
                a.hand.modelScore>=config.minModelScore && b.hand.modelScore>=config.minModelScore
            if(palms && gap>2.2f)clapArmed=true
            if(!palms)clapArmed=false
            if(palms && clapArmed && gap<.85f && cooled(GestureKind.CLAP,t,pairFired)) {
                emit(GestureKind.CLAP,GestureEventPhase.TRIGGER,ids,pos);clapArmed=false
            }
            val bothPinching=active.all {it.pinching}
            val distance=(a.rawPinch.let { P(it.x*info.width,it.y*info.height) }-
                b.rawPinch.let { P(it.x*info.width,it.y*info.height) }).length().coerceAtLeast(1f)
            if(bothPinching) {
                val base=scaleBase
                if(base==null) {scaleBase=distance;scalePosition=pos;emit(GestureKind.TWO_HAND_SCALE,GestureEventPhase.START,ids,pos)}
                else {
                    emit(GestureKind.TWO_HAND_SCALE,GestureEventPhase.UPDATE,ids,pos,scale=distance/base,
                        delta=FacePoint(pos.x-scalePosition.x,pos.y-scalePosition.y));scalePosition=pos
                }
            } else if(scaleBase!=null) {
                emit(GestureKind.TWO_HAND_SCALE,GestureEventPhase.END,ids,pos);scaleBase=null
            }
        }
        return events
    }

    private fun ready(kind: GestureKind,t: Long,candidates: MutableMap<GestureKind,Candidate>,fired: MutableMap<GestureKind,Long>,side: Handedness): Boolean {
        var c=candidates.getOrPut(kind){Candidate(t,t)}
        if(c.absentSince?.let {t-it>=config.stableNs.coerceAtLeast(1)}==true) {
            c=Candidate(t,t);candidates[kind]=c
        }
        c.absentSince=null
        c.last=t
        if(!c.fired && t-c.since>=config.stableNs &&
            fired[kind]?.let {t-it<config.cooldownNs}!=true && reentryAllowed(side,kind,t)) {
            fired[kind]=t;c.fired=true;return true
        }
        return false
    }
    private fun expire(active: Set<GestureKind>,t: Long,candidates: MutableMap<GestureKind,Candidate>) {
        candidates.entries.removeAll {
            if(it.key in active)false else {
                val since=it.value.absentSince?:t.also {time -> it.value.absentSince=time}
                t-since>=config.stableNs.coerceAtLeast(1)
            }
        }
    }
    private fun cooled(kind: GestureKind,t: Long,fired: MutableMap<GestureKind,Long>): Boolean {
        if(fired[kind]?.let {t-it<config.cooldownNs}==true)return false
        fired[kind]=t;return true
    }
    private fun reentryAllowed(side: Handedness,kind: GestureKind,t: Long): Boolean {
        val key=side to kind
        if(recentTriggers[key]?.let {t-it<config.cooldownNs}==true)return false
        recentTriggers[key]=t;return true
    }
    private fun wave(path: ArrayDeque<Sample>,span: Float): Boolean {
        if(path.size<6 || path.last().time-path.first().time<400_000_000L)return false
        val xs=path.map {it.point.x};val ys=path.map {it.point.y}
        if(xs.max()-xs.min()<span*.8f || ys.max()-ys.min()>span*.9f)return false
        var direction=0;var reversals=0;var extreme=xs.first()
        for(x in xs.drop(1)) {
            val sign=if(x>extreme+span*.3f)1 else if(x<extreme-span*.3f)-1 else 0
            if(sign!=0) {if(direction!=0 && sign!=direction)reversals++;direction=sign;extreme=x}
        }
        return reversals>=2
    }
    private fun circle(path: ArrayDeque<Sample>,span: Float): Boolean {
        if(path.size<10 || path.last().time-path.first().time<600_000_000L)return false
        val points=path.map {it.point};val cx=points.map {it.x}.average().toFloat();val cy=points.map {it.y}.average().toFloat()
        val radii=points.map {(it-P(cx,cy)).length()};val radius=radii.average().toFloat()
        if(radius<span*.4f || radii.min()<radius*.4f || radii.max()>radius*1.8f ||
            (points.last()-points.first()).length()>radius*.8f)return false
        var total=0f;var travel=0f
        for(i in 1 until points.size) {
            var d=atan2(points[i].y-cy,points[i].x-cx)-atan2(points[i-1].y-cy,points[i-1].x-cx)
            while(d>PI)d-=(2*PI).toFloat()
            while(d< -PI)d+=(2*PI).toFloat()
            total+=d;travel+=abs(d)
        }
        return abs(total)>5.2f && abs(total)>travel*.85f
    }
}
