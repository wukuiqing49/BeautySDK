package com.beautysdk.resources

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

/** 显式启用的发行方验证入口。信任公钥由宿主随应用分发，不读取包内公钥。
 * 验签、摘要校验全部成功后才调用原安装器；不启用效果、不联网、不在视频线程调用。
 * 此接口不代替客户授权，也不提供防回滚；宿主可固定 expectedReleaseId。
 */
class SignedResourceDelivery(trustedKeys: Map<String, ByteArray>) {
    private val keys = trustedKeys.mapValues { (id, der) ->
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "INVALID_KEY_ID" }
        require(der.size in 256..2048) { "INVALID_PUBLIC_KEY" }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        require(key.modulus.bitLength() in 2048..4096) { "INVALID_RSA_SIZE" }
        key
    }.also { require(it.isNotEmpty() && it.size <= 16) }

    data class Receipt(val releaseId: String, val keyId: String, val sha256: String, val result: ResourceDeliveryResult)

    /** 关闭两个输入流，包括验证失败时。envelope 最多16KiB，ZIP最多32MiB。 */
    @JvmOverloads fun install(
        manager: BeautyResourceManager,
        input: InputStream,
        envelope: InputStream,
        expectedReleaseId: String? = null,
        cancel: ResourceCancellation = ResourceCancellation(),
    ): Receipt = input.use { packageStream -> envelope.use { signatureStream ->
        val document = JSONObject(String(bounded(signatureStream, 16384) { cancel.check() }, Charsets.UTF_8))
        fields(document, setOf("version", "keyId", "payload", "signature"))
        require(integer(document, "version") == 1L) { "UNSUPPORTED_SIGNATURE_VERSION" }
        val keyId = string(document, "keyId")
        val key = requireNotNull(keys[keyId]) { "UNTRUSTED_KEY" }
        val payload = decode(string(document, "payload"), 4096)
        val signature = decode(string(document, "signature"), 512)
        require(signature.size == (key.modulus.bitLength() + 7) / 8) { "INVALID_SIGNATURE_SIZE" }
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update("BeautySDK/resource-delivery/v1\n".toByteArray(Charsets.UTF_8))
        verifier.update(payload)
        require(verifier.verify(signature)) { "INVALID_RESOURCE_SIGNATURE" }
        val claims = JSONObject(String(payload, Charsets.UTF_8))
        fields(claims, setOf("releaseId", "bytes", "sha256"))
        val id = string(claims, "releaseId")
        require(id.matches(Regex("r-[0-9a-f]{16}"))) { "INVALID_RELEASE_ID" }
        require(expectedReleaseId == null || id == expectedReleaseId) { "UNEXPECTED_RELEASE" }
        val count = integer(claims, "bytes")
        require(count in 1..33554432L) { "INVALID_PACKAGE_SIZE" }
        val digest = string(claims, "sha256")
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "INVALID_DIGEST" }
        val bytes = bounded(packageStream, count.toInt()) { cancel.check() }
        require(bytes.size.toLong() == count && sha256(bytes) == digest) { "SIGNED_PACKAGE_MISMATCH" }
        cancel.check()
        Receipt(id, keyId, digest, manager.installDelivery(bytes.inputStream(), cancel))
    } }

    private fun fields(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "INVALID_SIGNATURE_FIELDS" }
    }
    private fun integer(value: JSONObject, name: String): Long {
        val number = value.get(name)
        require(number is Int || number is Long) { "INVALID_INTEGER" }
        return (number as Number).toLong()
    }
    private fun string(value: JSONObject, name: String): String {
        val text = value.get(name)
        require(text is String) { "INVALID_STRING" }
        return text
    }
    private fun decode(text: String, limit: Int): ByteArray {
        require(text.length <= ((limit + 2) / 3) * 4 && text.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) { "INVALID_BASE64" }
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size <= limit && Base64.encodeToString(bytes, Base64.NO_WRAP) == text) { "NON_CANONICAL_BASE64" }
        return bytes
    }
}
