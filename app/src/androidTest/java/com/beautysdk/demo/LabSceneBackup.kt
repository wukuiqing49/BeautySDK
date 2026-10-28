package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import java.io.File

/** 新增持久化场景后，既有参数/资源测试同样须恢复场景配置及其资源图片。 */
class LabSceneBackup:ExternalResource() {
    private val folder get()=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"beauty_resources")
    private var saved:Map<String,ByteArray> = emptyMap()
    private fun files()=listOf(File(folder,"library.json"),File(folder,"gesture-selection.json"),File(folder,"user-state.json"),File(folder,"scene-state.json"),File(folder,"scene-state.json.bak"))+
        File(folder,"scene-images").listFiles().orEmpty().filter{it.isFile}
    private fun drain(){
        val queue=ResourceCenterModel::class.java.getDeclaredField("resourceIo").apply{isAccessible=true}.get(null) as java.util.concurrent.ExecutorService
        queue.submit{}.get(20,java.util.concurrent.TimeUnit.SECONDS)
    }
    override fun before(){drain();saved=files().filter{it.isFile}.associate{it.relativeTo(folder).path to it.readBytes()}}
    override fun after(){drain();files().forEach{it.delete()};saved.forEach{(path,bytes)->File(folder,path).apply{parentFile!!.mkdirs();writeBytes(bytes)}}}
}
