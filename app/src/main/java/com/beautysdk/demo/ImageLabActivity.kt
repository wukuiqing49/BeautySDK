package com.beautysdk.demo

import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.beautysdk.demo.feature.app.BeautyDemoController
import com.beautysdk.demo.feature.app.DemoBackend
import com.beautysdk.demo.feature.app.DemoEffects
import android.net.Uri
import android.view.View

import com.wkq.base.activity.BaseActivity
import com.beautysdk.demo.databinding.ActivityImageLabBinding
import com.beautysdk.demo.feature.app.FeatureAppEntry

class ImageLabActivity : BaseActivity<ActivityImageLabBinding>() {
    private lateinit var demo: BeautyDemoController
    private val selectImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) render(uri)
    }

    override fun initView() {
        enableEdgeToEdge()
        val horizontal = binding.root.paddingLeft
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(horizontal + bars.left,bars.top,horizontal + bars.right,bars.bottom)
            insets
        }
        demo = BeautyDemoController(applicationContext)
        binding.backend.useDemoEntries(R.array.beauty_backends)
        binding.glassesStyle.useDemoEntries(R.array.sticker_glasses_styles)
        binding.headStyle.useDemoEntries(R.array.sticker_head_styles)
        binding.effectResult.setText(R.string.demo_no_image)
        binding.showOriginal.setOnCheckedChangeListener { _,checked ->
            binding.processedImage.visibility = if (checked) View.INVISIBLE else View.VISIBLE
        }
        binding.backend.setSelection(1)
        binding.glassesStyle.setSelection(1)
        binding.headStyle.setSelection(1)
        binding.openCamera.setOnClickListener { startActivity(android.content.Intent(this,CameraTestActivity::class.java)) }
        binding.selectImage.setOnClickListener { selectImage.launch("image/*") }
        binding.applyEffects.setOnClickListener { render(null) }
        binding.runCheck.setOnClickListener {
            binding.result.setText(
                if (FeatureAppEntry.runFrameCheck()) R.string.check_passed else R.string.check_failed
            )
        }
    }

    override fun initData() = Unit

    private fun render(uri: Uri?) {
        binding.applyEffects.isEnabled = false
        binding.selectImage.isEnabled = false
        binding.effectResult.setText(R.string.processing)
        val backend = DemoBackend.entries[binding.backend.selectedItemPosition]
        demo.render(uri, backend, DemoEffects(binding.smoothing.progress / 100f,
            binding.whitening.progress / 100f, binding.warmFilter.isChecked,
            binding.glasses.isChecked, binding.moustache.isChecked, binding.hat.isChecked, binding.glassesStyle.selectedItemPosition, binding.headStyle.selectedItemPosition)) { result ->
            binding.applyEffects.isEnabled = true
            binding.selectImage.isEnabled = true
            result.onSuccess {
                binding.originalImage.setImageBitmap(it.original)
                binding.processedImage.setImageBitmap(it.processed)
                binding.effectResult.text = if (it.faceCount == 0 && backend != DemoBackend.BEAUTY_ONLY)
                    getString(R.string.demo_image_no_face) else getString(R.string.effect_success, it.faceCount, it.elapsedMs)
            }.onFailure { binding.effectResult.text = getString(R.string.effect_error, it.message ?: it.javaClass.simpleName) }
        }
    }
    override fun onDestroy() {
        if (::demo.isInitialized) demo.close()
        super.onDestroy()
    }
}
