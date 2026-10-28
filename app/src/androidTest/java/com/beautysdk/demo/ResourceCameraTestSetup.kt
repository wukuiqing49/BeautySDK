package com.beautysdk.demo

import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.demo.feature.app.GpuBeautyPreview

/** 相机用例走当前公共资源入口，避免旧隐藏开关被异步默认资源覆盖。 */
internal fun selectCameraTestSticker(activity:CameraTestActivity) {
    val inst=InstrumentationRegistry.getInstrumentation()
    lateinit var model:ResourceCenterModel
    inst.runOnMainSync{model=ViewModelProvider(activity)[ResourceCenterModel::class.java]}
    fun await(predicate:(ResourceCenterModel.Ui)->Boolean) {
        val deadline=System.nanoTime()+15_000_000_000L
        while(System.nanoTime()<deadline){var ready=false
            inst.runOnMainSync{model.ui.value?.let{ui->check(ui.error==null){ui.error.orEmpty()};ready=!ui.busy&&ui.settings!=null&&predicate(ui)}}
            if(ready)return
            Thread.sleep(25)
        }
        error("Camera resource setup did not settle")
    }
    await{true}
    inst.runOnMainSync{model.select(model.ui.value!!.installed.first{it.descriptor.id=="round_glasses"})}
    await{it.settings!!.requiresFaceAnalysis&&activity.findViewById<GpuBeautyPreview>(R.id.camera_gpu_preview).options.effects.themeSettings?.requiresFaceAnalysis==true}
}
