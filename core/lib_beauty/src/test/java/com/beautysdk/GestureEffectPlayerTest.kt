package com.beautysdk

import org.junit.Assert.*
import org.junit.Test

class GestureEffectPlayerTest {
    private fun rule(animated:Boolean=false)=GestureEffectRule("victory",GestureKind.VICTORY,
        StickerImage(if(animated)2 else 1,1,IntArray(if(animated)2 else 1){-1}),animated,1,1,
        if(animated)2 else 1,if(animated)2 else 1,if(animated)2 else 20,if(animated)1000 else 500)
    private fun event(id:Long,ms:Long,phase:GestureEventPhase=GestureEventPhase.TRIGGER)=GestureEvent(
        id,GestureKind.VICTORY,phase,ms*1_000_000,emptyList(),FacePoint(.5f,.5f),GestureSource.MODEL,.9f)
    @Test fun atlasAdvancesAndEndsWithoutLooping() {
        val p=GestureEffectPlayer();p.configure(GestureEffectPack(listOf(rule(true))))
        p.accept(listOf(event(1,1000)),1_000_000_000)
        assertEquals(0,p.frame(1_000_000_000)!!.index)
        assertEquals(1,p.frame(1_500_000_000)!!.index)
        assertNull(p.frame(2_000_000_000));assertNull(p.frame(3_000_000_000))
    }
    @Test fun playingAndCooldownIgnoreEventsWithoutQueuingOrReplayingIds() {
        val p=GestureEffectPlayer();p.configure(GestureEffectPack(listOf(rule())))
        p.accept(listOf(event(1,1000)),1_000_000_000)
        p.accept(listOf(event(2,1200)),1_200_000_000)
        assertNull(p.frame(1_500_000_000))
        p.accept(listOf(event(3,1600)),1_600_000_000);assertNull(p.frame(1_600_000_000))
        p.accept(listOf(event(3,1800)),1_800_000_000);assertNull(p.frame(1_800_000_000))
        p.accept(listOf(event(4,1800)),1_800_000_000);assertNotNull(p.frame(1_800_000_000))
    }
    @Test fun staleFutureNonTriggerAndDisabledCannotStart() {
        val p=GestureEffectPlayer();p.configure(GestureEffectPack(listOf(rule())))
        p.accept(listOf(event(1,0),event(2,2000),event(3,1000,GestureEventPhase.UPDATE)),1_000_000_000)
        assertNull(p.frame(1_000_000_000))
        p.accept(listOf(event(4,1000)),1_000_000_000);assertNotNull(p.frame(1_000_000_000))
        p.configure(null);assertNull(p.frame(1_100_000_000))
        p.accept(listOf(event(5,1200)),1_200_000_000);assertNull(p.frame(1_200_000_000))
    }
    @Test fun resetAndBackwardsTimelineClearActiveEffect() {
        val p=GestureEffectPlayer();p.configure(GestureEffectPack(listOf(rule())))
        p.accept(listOf(event(1,1000)),1_000_000_000);assertNull(p.frame(999_000_000))
        p.reset();p.accept(listOf(event(1,2000)),2_000_000_000);assertNotNull(p.frame(2_000_000_000))
        p.reset();assertNull(p.frame(2_100_000_000))
    }
    @Test fun invalidLayoutDurationAndDuplicateBindingsAreRejected() {
        for(value in listOf(0f,Float.NaN,1.01f)) {
            try{rule().copy(width=value);fail("accepted width")}catch(_:IllegalArgumentException){}
        }
        try{rule(true).copy(durationMs=999);fail("accepted duration")}catch(_:IllegalArgumentException){}
        try{GestureEffectPack(listOf(rule(),rule().copy(id="duplicate")));fail("accepted duplicate")}catch(_:IllegalArgumentException){}
    }
}
