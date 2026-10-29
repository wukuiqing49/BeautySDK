package com.beautysdk.license

import org.json.JSONObject
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Collections

enum class LicensedFeature { BEAUTY, FILTER, FACE, RESHAPE, MAKEUP, STICKER, BACKGROUND, MOTION, GESTURE }
enum class LicenseStatus { DEVELOPMENT, NOT_INITIALIZED, ACTIVATION_REQUIRED, ACTIVE, EXPIRED, REVOKED, CLOCK_ERROR, CONFIGURATION_ERROR }
data class LicenseState(val status: LicenseStatus, val features: Set<LicensedFeature> = emptySet(),
    val expiresAtMs: Long = 0, val lastError: String? = null)
class LicenseException(val code: String, val missingFeatures: Set<LicensedFeature>) : SecurityException(code)

/** 可替换网络传输，但所有返回值仍经过SDK内置公钥验签，不提供安装任意授权状态的入口。 */
fun interface LicenseTransport { fun exchange(requestJson: String): String }

internal const val DAY = 86_400_000L
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun immutableFeatures(values: Set<LicensedFeature>): Set<LicensedFeature> = Collections.unmodifiableSet(values.toSet())
internal data class AppIdentity(val packageName: String, val certificates: Set<String>)
internal data class LicenseGrant(val id: String, val hash: String, val identity: AppIdentity,
    val sdkMajor: Int, val features: Set<LicensedFeature>, val issuedAt: Long, val expiresAt: Long, val envelope: String)
internal data class LicenseLease(val active: Boolean, val reason: String, val issuedAt: Long, val expiresAt: Long,
    val refreshAt: Long, val features: Set<LicensedFeature>, val envelope: String)

internal class LicenseVerifier(private val keys: Map<String, PublicKey>) {
    init { require(keys.isNotEmpty() && keys.size <= 8); keys.values.forEach { require(it is RSAPublicKey && it.modulus.bitLength() in 2048..4096) } }
    private fun claims(envelope: String, kind: String): JSONObject {
        require(envelope.toByteArray(Charsets.UTF_8).size <= 32768) { "LICENSE_TOO_LARGE" }
        val doc = JSONObject(envelope)
        fields(doc, setOf("version", "keyId", "payload", "signature"))
        require(number(doc, "version") == 1L) { "LICENSE_VERSION" }
        val key = requireNotNull(keys[text(doc, "keyId")]) { "LICENSE_KEY_UNKNOWN" }
        val payload = text(doc, "payload")
        require(payload.toByteArray(Charsets.UTF_8).size <= 8192) { "LICENSE_PAYLOAD_SIZE" }
        val signature = text(doc, "signature")
        require(signature.matches(Regex("[0-9a-f]{512,1024}")) && signature.length == (key as RSAPublicKey).modulus.bitLength() / 4) { "LICENSE_SIGNATURE_SIZE" }
        val bytes = ByteArray(signature.length / 2) { signature.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update("BeautySDK/license/$kind/v1\n".toByteArray(Charsets.UTF_8))
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        require(verifier.verify(bytes)) { "LICENSE_SIGNATURE_INVALID" }
        return JSONObject(payload)
    }
    fun grant(envelope: String, app: AppIdentity, sdkMajor: Int): LicenseGrant {
        val c = claims(envelope, "grant")
        fields(c, setOf("licenseId", "packageName", "certificates", "sdkMajor", "features", "issuedAt", "expiresAt"))
        val id = text(c, "licenseId"); require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val certificates = strings(c, "certificates"); require(certificates.isNotEmpty() && certificates.size <= 8 && certificates.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(text(c, "packageName") == app.packageName && app.certificates.isNotEmpty() && certificates.containsAll(app.certificates)) { "LICENSE_APP_MISMATCH" }
        require(number(c, "sdkMajor") == sdkMajor.toLong()) { "LICENSE_SDK_MISMATCH" }
        val issued = number(c, "issuedAt"); val expires = number(c, "expiresAt")
        require(issued > 0 && (expires == 0L || expires > issued)) { "LICENSE_DATES" }
        return LicenseGrant(id, sha256(envelope.toByteArray(Charsets.UTF_8)), app, sdkMajor, features(c), issued, expires, envelope)
    }
    fun lease(envelope: String, grant: LicenseGrant, nonce: String? = null): LicenseLease {
        val c = claims(envelope, "lease")
        fields(c, setOf("licenseId", "grantSha256", "nonce", "status", "reason", "issuedAt", "expiresAt", "refreshAt", "features"))
        require(text(c, "licenseId") == grant.id && text(c, "grantSha256") == grant.hash) { "LEASE_GRANT_MISMATCH" }
        val responseNonce = text(c, "nonce"); require(responseNonce.matches(Regex("[0-9a-f]{64}")))
        require(nonce == null || nonce == responseNonce) { "LEASE_NONCE_MISMATCH" }
        val status = text(c, "status"); require(status in setOf("ACTIVE", "DENIED"))
        val reason = text(c, "reason"); require(reason in setOf("OK", "REVOKED", "EXPIRED", "NOT_REGISTERED"))
        require((status == "ACTIVE") == (reason == "OK"))
        val issued = number(c, "issuedAt"); val expires = number(c, "expiresAt"); val refresh = number(c, "refreshAt")
        require(issued >= grant.issuedAt && expires > issued && expires - issued <= 7 * DAY && refresh > issued && refresh <= expires && refresh - issued <= DAY) { "LEASE_DATES" }
        val features = features(c)
        require(grant.features.containsAll(features)) { "LEASE_FEATURE_ESCALATION" }
        if (status == "ACTIVE") require(grant.expiresAt == 0L || expires <= grant.expiresAt) { "LEASE_EXCEEDS_GRANT" }
        else require(features.isEmpty())
        return LicenseLease(status == "ACTIVE", reason, issued, expires, refresh, features, envelope)
    }
    private fun features(c: JSONObject) = immutableFeatures(strings(c, "features").map { LicensedFeature.valueOf(it) }.toSet())
    private fun strings(c: JSONObject, key: String): Set<String> {
        val array = c.getJSONArray(key); require(array.length() <= 16)
        val items = (0 until array.length()).map { require(array.get(it) is String); array.getString(it) }
        require(items.distinct().size == items.size); return items.toSet()
    }
    private fun fields(c: JSONObject, allowed: Set<String>) { require(c.keys().asSequence().toSet() == allowed) { "LICENSE_FIELDS" } }
    private fun text(c: JSONObject, name: String): String { val v = c.get(name); require(v is String); return v }
    private fun number(c: JSONObject, name: String): Long { val v = c.get(name); require(v is Long || v is Int); return (v as Number).toLong() }
}
