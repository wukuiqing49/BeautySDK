package com.beautysdk.license

import org.json.JSONObject
import java.security.SecureRandom

internal interface LicenseClock { fun wall(): Long; fun elapsed(): Long }
internal interface LicenseStore { fun read(): String?; fun write(value: String); fun clear() }

/** 仅在授权worker调用；签名凭证和重试计划持久化。渲染仅读取不可变快照。 */
internal class LicenseEngine(private val grant: LicenseGrant, private val verifier: LicenseVerifier,
    private val transport: LicenseTransport, private val store: LicenseStore, private val clock: LicenseClock,
    private val publish: (LicenseState, Long) -> Unit, private val jitter: () -> Long = { SecureRandom().nextInt(15000).toLong() }) {
    private var lease: LicenseLease? = null
    private var nextAttempt = 0L
    private var failures = 0
    private var anchorTime = clock.wall()
    private var anchorElapsed = clock.elapsed()
    private var highWater = 0L
    private var error: String? = null
    private var clockBlocked = false

    private fun now(): Long = maxOf(anchorTime + (clock.elapsed() - anchorElapsed).coerceAtLeast(0), highWater)
    fun restore() {
        try {
            store.read()?.let { raw ->
                require(raw.length <= 65536)
                val c = JSONObject(raw)
                if (c.getString("grantSha256") != grant.hash) return@let
                lease = c.optString("lease").takeIf { it.isNotEmpty() }?.let { verifier.lease(it, grant) }
                highWater = c.getLong("highWater")
                require(highWater >= 0)
                nextAttempt = c.getLong("nextAttempt")
                failures = c.getInt("failures").coerceIn(0, 16)
                clockBlocked = clock.wall() + 300000 < highWater
                anchorTime = maxOf(clock.wall(), highWater, lease?.issuedAt ?: 0)
                anchorElapsed = clock.elapsed()
                // 本地缓存不是信任根；不能将损坏的重试时间延伸到无限未来。
                nextAttempt = nextAttempt.coerceIn(0, anchorTime + DAY)
            }
        } catch (_: Exception) { lease = null; nextAttempt = 0; highWater = 0; error = "LICENSE_CACHE_INVALID" }
        emit()
    }
    fun tick() {
        emit()
        if (now() < nextAttempt) return
        val before = now()
        failures = (failures + 1).coerceAtMost(16)
        val backoff = (60000L * (1L shl (failures - 1).coerceAtMost(9))).coerceAtMost(6 * 3600000L)
        nextAttempt = before + backoff + jitter().coerceIn(0, 15000)
        // 请求前记录退避，进程被杀/重启不会每次都重新发起激活。
        if (!persist()) { emit(); return }
        try {
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val request = JSONObject().put("version", 1).put("grant", grant.envelope).put("nonce", nonce)
                .put("packageName", grant.identity.packageName).put("certificates", org.json.JSONArray(grant.identity.certificates.sorted()))
                .put("sdkMajor", grant.sdkMajor).toString()
            val fresh = verifier.lease(transport.exchange(request), grant, nonce)
            require(lease == null || fresh.issuedAt >= lease!!.issuedAt) { "LEASE_TIME_REGRESSION" }
            lease = fresh
            anchorTime = fresh.issuedAt; anchorElapsed = clock.elapsed(); highWater = fresh.issuedAt
            clockBlocked = false; nextAttempt = fresh.refreshAt; failures = 0; error = null
            persist()
        } catch (_: Exception) { error = "LICENSE_REFRESH_FAILED" }
        emit()
    }
    private fun persist(): Boolean {
        highWater = maxOf(highWater, now())
        return try {
            store.write(JSONObject().put("grantSha256", grant.hash).put("lease", lease?.envelope ?: "")
                .put("highWater", highWater).put("nextAttempt", nextAttempt).put("failures", failures).toString())
            true
        } catch (_: Exception) {
            // 尤其不能在撤销写入失败后留下可恢复的旧ACTIVE缓存。
            try { store.clear() } catch (_: Exception) { }
            error = "LICENSE_CACHE_WRITE_FAILED"; false
        }
    }
    private fun emit() {
        val current = lease
        val state = when {
            clockBlocked -> LicenseState(LicenseStatus.CLOCK_ERROR, lastError = "LICENSE_CLOCK_ROLLBACK")
            current == null -> LicenseState(LicenseStatus.ACTIVATION_REQUIRED, lastError = error)
            !current.active -> LicenseState(if(current.reason == "EXPIRED") LicenseStatus.EXPIRED else LicenseStatus.REVOKED, lastError = current.reason)
            now() >= current.expiresAt -> LicenseState(LicenseStatus.EXPIRED, expiresAtMs = current.expiresAt, lastError = error)
            else -> LicenseState(LicenseStatus.ACTIVE, current.features, current.expiresAt, error)
        }
        val remaining = if (state.status == LicenseStatus.ACTIVE) (current!!.expiresAt - now()).coerceAtLeast(0) else 0
        publish(state, clock.elapsed() + remaining)
    }
}
