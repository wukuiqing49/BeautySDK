package com.beautysdk.sample

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import com.beautysdk.*
import com.beautysdk.license.*
import java.util.concurrent.atomic.AtomicBoolean
import com.beautysdk.sample.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** 无相机、无外部图片，独立Maven消费者的最小RGBA示例。 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val rendered = AtomicBoolean()
    private var licenseListener: ((LicenseState) -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val listener: (LicenseState) -> Unit = { state ->
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    if (BeautyLicensing.allows(LicensedFeature.BEAUTY)) renderSample(binding)
                    else binding.status.text = getString(R.string.license_state, state.status.name)
                }
            }
        }
        licenseListener = listener
        BeautyLicensing.addListener(listener)
        BeautySdk.initialize(applicationContext)
        listener(BeautyLicensing.state())
    }
    private fun renderSample(binding: ActivityMainBinding) {
        if (!rendered.compareAndSet(false, true)) return
        worker.execute {
            try {
                val size = 128
                val bytes = ByteBuffer.allocateDirect(size * size * 4)
                repeat(size) { y -> repeat(size) { x ->
                    bytes.put((x * 2).toByte()).put((y * 2).toByte()).put(100.toByte()).put(255.toByte())
                } }
                bytes.flip()
                val input = RgbaFrame(FrameInfo(size, size, System.nanoTime()), bytes, size * 4)
                val session = BeautySession()
                val output = try {
                    session.start()
                    session.settings = BeautySettings(smoothing = .3f, whitening = .2f)
                    session.process(input) as RgbaFrame
                } finally { session.close() }
                fun image(frame: RgbaFrame): Bitmap {
                    val pixels = frame.readOnlyPixels()
                    val colors = IntArray(size * size)
                    repeat(size) { y -> repeat(size) { x ->
                        val i = y * frame.rowStrideBytes + x * 4
                        colors[y * size + x] = android.graphics.Color.argb(pixels.get(i + 3).toInt() and 255, pixels.get(i).toInt() and 255, pixels.get(i + 1).toInt() and 255, pixels.get(i + 2).toInt() and 255)
                    } }
                    return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
                }
                val before = image(input); val after = image(output)
                runOnUiThread {
                    if (isDestroyed || isFinishing) { before.recycle(); after.recycle() }
                    else { binding.before.setImageBitmap(before); binding.after.setImageBitmap(after); binding.status.setText(R.string.complete) }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed && !isFinishing) binding.status.text = getString(R.string.failed, error.javaClass.simpleName) }
            }
        }
    }
    override fun onDestroy() { licenseListener?.let(BeautyLicensing::removeListener); worker.shutdown(); super.onDestroy() }
}
