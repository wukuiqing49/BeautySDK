package com.beautysdk.demo.feature.app

/** 演示采集请求；CameraX可能协商回退，最终尺寸以帧诊断为准。 */
enum class DemoCaptureResolution(val width:Int,val height:Int) {
    SD(640,480), HD(1280,720), FULL_HD(1920,1080);

    companion object {
        fun fromName(name:String?):DemoCaptureResolution = entries.firstOrNull { it.name==name } ?: SD
    }
}
