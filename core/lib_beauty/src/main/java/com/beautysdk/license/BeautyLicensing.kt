package com.beautysdk.license

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import com.beautysdk.*
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 商业Release默认拒绝；仅源码Debug变体为开发模式，没有运行时关闭授权的开关。 */
object BeautyLicensing {
    private data class Gate(val state: LicenseState, val deadline: Long)
    @Volatile private var gate = Gate(LicenseState(LicenseStatus.NOT_INITIALIZED), 0)
    private val listeners = CopyOnWriteArrayList<(LicenseState) -> Unit>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "beauty-license").apply { isDaemon = true } }
    private var engine: LicenseEngine? = null // worker only
    private var source: String? = null
    private var timerStarted = false
    private val tasks = LicenseTasks(worker) { engine?.tick() }

    @JvmStatic fun state(): LicenseState {
        if (!BuildConfig.LICENSE_ENFORCED) return development
        val current = gate
        return if (current.state.status == LicenseStatus.ACTIVE && SystemClock.elapsedRealtime() >= current.deadline)
            current.state.copy(status = LicenseStatus.EXPIRED, features = emptySet()) else current.state
    }
    private val development = LicenseState(LicenseStatus.DEVELOPMENT, immutableFeatures(LicensedFeature.entries.toSet()))
    /** 回调在授权worker执行；UI应切主线程。不会在视频线程执行回调。 */
    @JvmStatic fun addListener(listener: (LicenseState) -> Unit) { listeners.addIfAbsent(listener) }
    @JvmStatic fun removeListener(listener: (LicenseState) -> Unit) { listeners.remove(listener) }
    @JvmStatic fun allows(feature: LicensedFeature): Boolean {
        if (!BuildConfig.LICENSE_ENFORCED) return true
        val current = gate
        return current.state.status == LicenseStatus.ACTIVE && SystemClock.elapsedRealtime() < current.deadline && feature in current.state.features
    }
    @JvmStatic fun requireFeatures(features: Set<LicensedFeature>) {
        val missing = features.filterNot(::allows).toSet()
        if (missing.isNotEmpty()) throw LicenseException("LICENSE_FEATURE_UNAVAILABLE", immutableFeatures(missing))
    }
    /** 整份请求缺任一权限即旁路，不悄悄丢弃其中某个参数后宣称应用成功。 */
    @JvmStatic fun allowsSettings(s: BeautySettings): Boolean {
        if (!BuildConfig.LICENSE_ENFORCED) return true
        val a = s.adjustments
        return (!(s.smoothing > 0 || s.whitening > 0 || a.rosy != 0f) || allows(LicensedFeature.BEAUTY)) &&
            (!(s.filter != BeautyFilter.NONE || a.brightness != 0f || a.contrast != 1f || a.saturation != 1f || a.sharpening != 0f) || allows(LicensedFeature.FILTER)) &&
            (!a.hasReshape || allows(LicensedFeature.RESHAPE)) &&
            (!(a.hasMakeup || s.eyeMakeup != null) || allows(LicensedFeature.MAKEUP)) &&
            (!(s.glasses || s.moustache || s.hat || !s.stickerPack?.layers.isNullOrEmpty()) || allows(LicensedFeature.STICKER)) &&
            (!s.scene.needsSegmentation || allows(LicensedFeature.BACKGROUND)) &&
            (s.scene.motion == CameraMotion.NONE || allows(LicensedFeature.MOTION)) &&
            (!s.requiresFaceAnalysis || allows(LicensedFeature.FACE))
    }
    /** 公钥和发行端点编译在核心中；调用方提供传输实现也不能安装未经签名的状态。 */
    @JvmStatic fun initialize(context: Context, licenseAsset: String, transport: LicenseTransport) {
        val app = context.applicationContext
        if (!BuildConfig.LICENSE_ENFORCED) { worker.execute { publish(development, Long.MAX_VALUE) }; return }
        tasks.initialize {
            try {
                require(licenseAsset.matches(Regex("[A-Za-z0-9_./-]{1,160}")) && !licenseAsset.startsWith('/') && licenseAsset.split('/').none { it == ".." || it.isEmpty() })
                val key = "${app.packageName}:$licenseAsset"
                if (source != key || engine == null) {
                    val config = JSONObject(BuildConfig.LICENSE_CONFIG)
                    val keys = config.getJSONObject("keys")
                    val verifier = LicenseVerifier(keys.keys().asSequence().associateWith { id ->
                        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(keys.getString(id), Base64.NO_WRAP)))
                    })
                    val envelope = app.assets.open(licenseAsset).use { input ->
                        val data = input.readBytesBounded(32768); String(data, Charsets.UTF_8)
                    }
                    val grant = verifier.grant(envelope, identity(app), BuildConfig.LICENSE_SDK_MAJOR)
                    val clock = object : LicenseClock {
                        override fun wall() = System.currentTimeMillis()
                        override fun elapsed() = SystemClock.elapsedRealtime()
                    }
                    val file = AtomicFile(File(app.noBackupFilesDir, "beautysdk-license/${grant.hash}.json"))
                    val store = object : LicenseStore {
                        override fun read(): String? = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                            file.openRead().use { String(it.readBytesBounded(65536), Charsets.UTF_8) } else null
                        override fun write(value: String) {
                            file.baseFile.parentFile!!.mkdirs()
                            val out = file.startWrite()
                            try { out.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
                            catch (e: Exception) { file.failWrite(out); throw e }
                        }
                        override fun clear() = file.delete()
                    }
                    engine = LicenseEngine(grant, verifier, transport, store, clock, ::publish).also { it.restore() }
                    source = key
                }
                if (!timerStarted) {
                    timerStarted = true
                    worker.scheduleWithFixedDelay({ refreshIfDue() }, 60, 60, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                engine = null; source = null
                publish(LicenseState(LicenseStatus.CONFIGURATION_ERROR, lastError = "LICENSE_INITIALIZATION_FAILED"), 0)
            }
        }
    }
    /** 只是检查持久化刷新/退避计划；反复调用不会强制发请求。 */
    @JvmStatic fun refreshIfDue() {
        if (BuildConfig.LICENSE_ENFORCED) tasks.request()
    }
    private fun publish(state: LicenseState, deadline: Long) {
        val old = gate.state
        gate = Gate(state, deadline)
        if (old != state) listeners.forEach { try { it(state) } catch (_: Exception) { } }
    }
    @Suppress("DEPRECATION")
    private fun identity(context: Context): AppIdentity {
        val info = context.packageManager.getPackageInfo(context.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val certificates = if (Build.VERSION.SDK_INT >= 28) requireNotNull(info.signingInfo).apkContentsSigners else info.signatures
        return AppIdentity(context.packageName, requireNotNull(certificates).map { sha256(it.toByteArray()) }.toSet())
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit); out.write(buffer, 0, count) }
        return out.toByteArray()
    }
    /** 供统一入口构造HTTPS传输；不是从客户文件读取的可替换发行地址。 */
    @JvmStatic fun endpoint(): String = JSONObject(BuildConfig.LICENSE_CONFIG).optString("endpoint")
}
