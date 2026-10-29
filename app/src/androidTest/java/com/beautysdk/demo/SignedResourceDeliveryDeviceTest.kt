package com.beautysdk.demo

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.BeautyResourceManager
import com.beautysdk.resources.SignedResourceDelivery
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID

/** 临时密钥与独立缓存目录，不访问Demo资源状态，不保留私钥。新增，尚未执行。 */
class SignedResourceDeliveryDeviceTest {
    @Test fun authenticPackageInstallsButTamperingAndWrongTrustDoNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "signed-delivery-test-${UUID.randomUUID()}")
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = context.assets.open("resource_delivery.zip").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val payload = JSONObject().put("releaseId", "r-0000000000000000").put("bytes", bytes.size).put("sha256", digest).toString().toByteArray()
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private); update("BeautySDK/resource-delivery/v1\n".toByteArray()); update(payload)
        }
        fun encoded(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
        val envelope = JSONObject().put("version", 1).put("keyId", "test-key").put("payload", encoded(payload)).put("signature", encoded(signer.sign())).toString().toByteArray()
        try {
            val manager = BeautyResourceManager(directory)
            val verifier = SignedResourceDelivery(mapOf("test-key" to pair.public.encoded))
            fun rejected(action: () -> Unit) {
                try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
                assertTrue(manager.list().isEmpty())
                assertTrue(manager.listGestureResources().isEmpty())
            }
            rejected { verifier.install(manager, bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }.inputStream(), envelope.inputStream()) }
            rejected { verifier.install(manager, bytes.inputStream(), envelope.inputStream(), "r-1111111111111111") }
            val wrong = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            rejected { SignedResourceDelivery(mapOf("test-key" to wrong.public.encoded)).install(manager, bytes.inputStream(), envelope.inputStream()) }
            val altered = JSONObject(String(envelope)).put("payload", encoded(payload.copyOf().apply { this[0] = 32 })).toString().toByteArray()
            rejected { verifier.install(manager, bytes.inputStream(), altered.inputStream()) }
            val receipt = verifier.install(manager, bytes.inputStream(), envelope.inputStream(), "r-0000000000000000")
            assertEquals(digest, receipt.sha256)
            assertTrue(manager.list().isNotEmpty())
        } finally { directory.deleteRecursively() }
    }
}
