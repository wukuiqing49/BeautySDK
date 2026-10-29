package com.beautysdk.license

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

class LicenseEngineTest {
    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private const val NOW = 1793232000000L
        private const val CERT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
    private class Clock(var wall: Long = NOW, var elapsed: Long = 1000) : LicenseClock {
        override fun wall() = wall
        override fun elapsed() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class Store : LicenseStore {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { if (fail) error("disk"); this.value = value }
        override fun clear() { value = null }
    }
    private fun signed(kind: String, claims: JSONObject): String {
        val payload = claims.toString()
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private); update("BeautySDK/license/$kind/v1\n$payload".toByteArray())
        }
        return JSONObject().put("version", 1).put("keyId", "test").put("payload", payload)
            .put("signature", signer.sign().joinToString("") { "%02x".format(it) }).toString()
    }
    private val verifier = LicenseVerifier(mapOf("test" to pair.public))
    private val identity = AppIdentity("com.example.app", setOf(CERT))
    private val rawGrant = signed("grant", JSONObject().put("licenseId", "id").put("packageName", identity.packageName)
        .put("certificates", JSONArray(listOf(CERT))).put("sdkMajor", 0).put("features", JSONArray(listOf("BEAUTY")))
        .put("issuedAt", NOW - 1000).put("expiresAt", 0))
    private val grant = verifier.grant(rawGrant, identity, 0)
    private fun response(nonce: String, time: Long, active: Boolean = true, features: List<String> = if(active) listOf("BEAUTY") else emptyList()) = signed("lease",
        JSONObject().put("licenseId", "id").put("grantSha256", grant.hash).put("nonce", nonce)
            .put("status", if(active) "ACTIVE" else "DENIED").put("reason", if(active) "OK" else "REVOKED")
            .put("issuedAt", time).put("expiresAt", time + 7 * DAY).put("refreshAt", time + DAY).put("features", JSONArray(features)))

    @Test fun firstActivationThenManyCallsAndRestartDoNotRequestAgain() {
        val clock = Clock(); val store = Store(); var calls = 0; var state: LicenseState? = null
        val transport = LicenseTransport { calls++; response(JSONObject(it).getString("nonce"), clock.wall) }
        fun engine() = LicenseEngine(grant, verifier, transport, store, clock, { s, _ -> state = s }, { 0 })
        val first = engine(); first.restore(); first.tick(); repeat(1000) { first.tick() }
        assertEquals(1, calls); assertEquals(LicenseStatus.ACTIVE, state!!.status)
        clock.advance(3600000); val restart = engine(); restart.restore(); restart.tick(); assertEquals(1, calls)
        clock.advance(DAY); restart.tick(); assertEquals(2, calls)
    }
    @Test fun failedRefreshKeepsValidLeaseAndBackoffSurvivesRestart() {
        val clock = Clock(); val store = Store(); var calls = 0; var offline = false; var state: LicenseState? = null
        val transport = LicenseTransport { calls++; if(offline) error("offline"); response(JSONObject(it).getString("nonce"), clock.wall) }
        fun engine() = LicenseEngine(grant, verifier, transport, store, clock, { s, _ -> state = s }, { 0 })
        val engine = engine(); engine.restore(); engine.tick(); offline = true; clock.advance(DAY); engine.tick()
        assertEquals(LicenseStatus.ACTIVE, state!!.status); assertEquals(2, calls)
        val restart = engine(); restart.restore(); repeat(1000) { restart.tick() }; assertEquals(2, calls)
        clock.advance(60000); restart.tick(); assertEquals(3, calls)
        clock.advance(7 * DAY); restart.tick(); assertEquals(LicenseStatus.EXPIRED, state!!.status)
    }
    @Test fun signedRevocationPersistsAndOfflineCannotReviveActiveLease() {
        val clock = Clock(); val store = Store(); var revoked = false; var offline = false; var state: LicenseState? = null
        val transport = LicenseTransport { if(offline) error("offline"); response(JSONObject(it).getString("nonce"), clock.wall, !revoked) }
        fun engine() = LicenseEngine(grant, verifier, transport, store, clock, { s, _ -> state = s }, { 0 })
        val engine = engine(); engine.restore(); engine.tick(); clock.advance(DAY); revoked = true; engine.tick()
        assertEquals(LicenseStatus.REVOKED, state!!.status)
        offline = true; val restart = engine(); restart.restore(); restart.tick(); assertEquals(LicenseStatus.REVOKED, state!!.status)
    }
    @Test fun cachedClockRollbackDoesNotGrantOfflineAccess() {
        val clock = Clock(); val store = Store(); var state: LicenseState? = null
        val first = LicenseEngine(grant, verifier, LicenseTransport { response(JSONObject(it).getString("nonce"), NOW) }, store, clock, { s, _ -> state = s }, { 0 })
        first.restore(); first.tick(); clock.wall -= DAY
        val restart = LicenseEngine(grant, verifier, LicenseTransport { error("offline") }, store, clock, { s, _ -> state = s }, { 0 })
        restart.restore(); restart.tick(); assertEquals(LicenseStatus.CLOCK_ERROR, state!!.status)
    }
    @Test fun tamperedLeaseWrongNonceAndEscalatedFeaturesRejected() {
        val nonce = "b".repeat(64); val good = response(nonce, NOW)
        assertThrows(IllegalArgumentException::class.java) { verifier.lease(good.replace("ACTIVE", "DENIED"), grant, nonce) }
        assertThrows(IllegalArgumentException::class.java) { verifier.lease(good, grant, "c".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { verifier.lease(response(nonce, NOW, features = listOf("GESTURE")), grant, nonce) }
    }
    @Test fun wrongAppCertificateOrSdkRejected() {
        assertThrows(IllegalArgumentException::class.java) { verifier.grant(rawGrant, identity.copy(packageName = "com.wrong.app"), 0) }
        assertThrows(IllegalArgumentException::class.java) { verifier.grant(rawGrant, identity.copy(certificates = setOf("c".repeat(64))), 0) }
        assertThrows(IllegalArgumentException::class.java) { verifier.grant(rawGrant, identity, 1) }
    }
    @Test fun storageFailureBeforeRequestDoesNotSpamNetwork() {
        val store = Store().apply { fail = true }; val clock = Clock(); var calls = 0; var state: LicenseState? = null
        val engine = LicenseEngine(grant, verifier, LicenseTransport { calls++; error("unexpected") }, store, clock, { s, _ -> state = s }, { 0 })
        engine.restore(); repeat(100) { engine.tick() }; assertEquals(0, calls)
        assertEquals("LICENSE_CACHE_WRITE_FAILED", state!!.lastError)
    }
    @Test fun revocationWriteFailureClearsOldActiveCache() {
        val store = Store(); val clock = Clock(); var revoked = false; var state: LicenseState? = null
        val engine = LicenseEngine(grant, verifier, LicenseTransport {
            val value = response(JSONObject(it).getString("nonce"), clock.wall, !revoked)
            if (revoked) store.fail = true // fail after the pre-request plan has been saved
            value
        }, store, clock, { s, _ -> state = s }, { 0 })
        engine.restore(); engine.tick(); assertNotNull(store.value)
        revoked = true; clock.advance(DAY); engine.tick()
        assertEquals(LicenseStatus.REVOKED, state!!.status); assertNull(store.value)
        val restart = LicenseEngine(grant, verifier, LicenseTransport { error("offline") }, store, clock, { s, _ -> state = s }, { 0 })
        restart.restore(); restart.tick()
        assertEquals(LicenseStatus.ACTIVATION_REQUIRED, state!!.status)
    }
    @Test fun liveLeaseExpiresOnMonotonicClockEvenIfWallClockStops() {
        val store = Store(); val clock = Clock(); var state: LicenseState? = null; var offline = false
        val engine = LicenseEngine(grant, verifier, LicenseTransport {
            if (offline) error("offline")
            response(JSONObject(it).getString("nonce"), NOW)
        }, store, clock, { s, _ -> state = s }, { 0 })
        engine.restore(); engine.tick(); offline = true
        clock.elapsed += 7 * DAY - 1; engine.tick(); assertEquals(LicenseStatus.ACTIVE, state!!.status)
        clock.elapsed++; engine.tick(); assertEquals(LicenseStatus.EXPIRED, state!!.status)
    }
    @Test fun nodeSignedGrantAndLeaseVerifyInJvm() {
        val f = JSONObject(File(System.getProperty("beauty.license.fixtures")).readText())
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(java.util.Base64.getDecoder().decode(f.getString("publicKey"))))
        val verifier = LicenseVerifier(mapOf(f.getString("keyId") to publicKey))
        val grant = verifier.grant(f.getString("grant"), AppIdentity("com.beautysdk.test", setOf(CERT)), 0)
        assertTrue(verifier.lease(f.getString("active"), grant, f.getString("nonce")).active)
        assertFalse(verifier.lease(f.getString("revoked"), grant, f.getString("nonce")).active)
    }
}
