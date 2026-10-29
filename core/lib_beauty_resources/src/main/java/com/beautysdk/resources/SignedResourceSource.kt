package com.beautysdk.resources

import java.net.HttpURLConnection
import java.net.URI

/** 宿主固定的发行源；地址、公钥与版本必须来自可信应用配置，不能来自下载目录。 */
class SignedResourceSource(
    packageUrl: String,
    envelopeUrl: String,
    private val releaseId: String,
    trustedKeys: Map<String, ByteArray>,
) {
    private val verifier = SignedResourceDelivery(trustedKeys)
    private val archive = endpoint(packageUrl)
    private val envelope = endpoint(envelopeUrl)

    init {
        require(releaseId.matches(Regex("r-[0-9a-f]{16}"))) { "INVALID_RELEASE_ID" }
        require(archive.host.equals(envelope.host, true) && archive.port == envelope.port) { "SOURCE_ORIGIN_MISMATCH" }
    }

    /** 仅IO线程调用。有限超时、拒绝跳转；失败不降级到未签名安装，不自动启用。 */
    @JvmOverloads fun install(manager: BeautyResourceManager, cancel: ResourceCancellation = ResourceCancellation()): SignedResourceDelivery.Receipt {
        cancel.check()
        val signature = connection(envelope)
        try {
            signature.inputStream.use { proof ->
                cancel.check()
                val resource = connection(archive)
                try {
                    return resource.inputStream.use { bytes -> verifier.install(manager, bytes, proof, releaseId, cancel) }
                } finally { resource.disconnect() }
            }
        } finally { signature.disconnect() }
    }

    private fun connection(uri: URI): HttpURLConnection {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200) { "RESOURCE_HTTP_${connection.responseCode}" }
            return connection
        } catch (error: Exception) { connection.disconnect(); throw error }
    }

    private fun endpoint(value: String): URI = URI(value).also {
        require(it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null) { "HTTPS_SOURCE_REQUIRED" }
    }
}
