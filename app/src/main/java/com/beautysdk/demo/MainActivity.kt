package com.beautysdk.demo

import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.beautysdk.demo.databinding.ActivityMainBinding
import com.wkq.base.activity.BaseActivity

/** 首页聚焦实时美颜与素材创作；开发诊断使用次级入口。 */
class MainActivity : BaseActivity<ActivityMainBinding>() {
    override fun initView() {
        enableEdgeToEdge()
        androidx.core.view.WindowInsetsControllerCompat(window,binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // 首页自行消费系统栏，滚动内容与底部留白不重复叠加 Insets。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun camera(section: Int) {
            startActivity(Intent(this, CameraTestActivity::class.java).putExtra("initial_section", section))
        }
        binding.openCamera.setOnClickListener { camera(-1) }
        binding.openWorkshop.setOnClickListener { startActivity(Intent(this,ResourceCenterActivity::class.java)) }
        binding.openDiagnostics.setOnClickListener { camera(2) }
    }
    override fun initData() = Unit
}
