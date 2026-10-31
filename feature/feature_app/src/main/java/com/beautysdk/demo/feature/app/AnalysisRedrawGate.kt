package com.beautysdk.demo.feature.app

/** 只唤醒仍在屏幕上的真实采样，每个采样最多补画一次；不排队、不预测坐标。 */
internal class AnalysisRedrawGate {
    private var timestamp: Long? = null
    private var enabled = false
    private var requested = false
    private var fresh = false

    @Synchronized fun beginFrame(timestampNs: Long, active: Boolean) {
        if (timestamp != timestampNs) {
            timestamp = timestampNs
            requested = false
            fresh = false
        }
        enabled = active
    }

    @Synchronized fun completed(timestampNs: Long): Boolean {
        if (!enabled || timestamp != timestampNs || requested || fresh) return false
        requested = true
        return true
    }

    @Synchronized fun rendered(timestampNs: Long, resultTimestampNs: Long?) {
        if (timestamp == timestampNs && resultTimestampNs == timestampNs) fresh = true
    }

    @Synchronized fun clear() {
        timestamp = null
        enabled = false
        requested = false
        fresh = false
    }
}
