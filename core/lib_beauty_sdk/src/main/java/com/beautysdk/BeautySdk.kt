package com.beautysdk

import android.content.Context
import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicenseTransport
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** 显式初始化联网授权；不会自行采集视频、启动服务或上传人脸。 */
object BeautySdk {
    @JvmStatic @JvmOverloads fun initialize(context: Context, licenseAsset: String = "beautysdk.license") {
        BeautyLicensing.initialize(context, licenseAsset, LicenseTransport { request ->
            val url = URL(BeautyLicensing.endpoint())
            require(url.protocol == "https" && url.userInfo == null && url.ref == null) { "LICENSE_ENDPOINT_INVALID" }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 10000
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                val bytes = request.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 49152)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                require(connection.responseCode == 200) { "LICENSE_SERVICE_UNAVAILABLE" }
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 32768); output.write(buffer, 0, n) }
                    String(output.toByteArray(), Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        })
    }
}
