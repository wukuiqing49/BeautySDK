package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.io.File

/** 新增持久化场景后，既有参数/资源测试同样须恢复场景配置及其资源图片。 */
class LabSceneBackup:ExternalResource() {
    private val folder get()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"beauty_resources")
    private var saved:Map<String,ByteArray> = emptyMap()
    private fun files()=listOf("last-local-delivery.sha256","last-local-delivery.sha256.bak","library.json","active-defaults.json","pending-defaults.json","gesture-effects.zip","pending-gesture-effects.zip","gesture-selection.json","user-state.json","scene-state.json","scene-state.json.bak").map{File(folder,it)}+
        File(folder,"scene-images").listFiles().orEmpty().filter{it.isFile}+
        File(folder,"gesture-installed").walkTopDown().filter{it.isFile}.toList()
    private fun drain(){
        val queue=ResourceCenterModel::class.java.getDeclaredField("resourceIo").apply{isAccessible=true}.get(null) as java.util.concurrent.ExecutorService
        queue.submit{}.get(20,java.util.concurrent.TimeUnit.SECONDS)
    }
    override fun before(){
        // 相机页退出后设备可能重新进入锁屏；每项UI回归单独建立可见窗口前置条件。
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        for(command in listOf("input keyevent KEYCODE_WAKEUP","wm dismiss-keyguard")){
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
        }
        drain();saved=files().filter{it.isFile}.associate{it.relativeTo(folder).path to it.readBytes()}
        // 界面用例使用已知默认排序/语言；finally恢复真实宿主默认配置，不依赖上一用例。
        folder.mkdirs()
        File(folder,"active-defaults.json").writeBytes(InstrumentationRegistry.getInstrumentation().targetContext.assets.open("resource_center/defaults.json").use{it.readBytes()})
    }
    override fun after(){
        drain();files().forEach{it.delete()}
        File(folder,"gesture-installed").listFiles().orEmpty().filter{it.isDirectory&&it.listFiles().isNullOrEmpty()}.forEach{it.delete()}
        saved.forEach{(path,bytes)->File(folder,path).apply{parentFile!!.mkdirs();writeBytes(bytes)}}
    }
}
