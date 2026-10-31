package com.beautysdk.demo

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** 旋转通过UiAutomation，并通过WindowManager原样恢复；不追加shell系统写权限。 */
class UiPreviewConfigurationRule:ExternalResource(),Application.ActivityLifecycleCallbacks {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private var automatic="1"
    private var rotation="0"
    private var rotated=false
    private fun read(key:String)=ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("settings get system $key")).use{it.readBytes().toString(Charsets.UTF_8).trim()}
    override fun before(){
        InstrumentationRegistry.getArguments().getString("uiRotation")?.toInt()?.let{
            automatic=read("accelerometer_rotation");rotation=read("user_rotation")
            check(inst.uiAutomation.setRotation(it));rotated=true;Thread.sleep(500)
        }
    }
    @Suppress("DEPRECATION")
    override fun after(){
        if(rotated){
            val command=if(android.os.Build.VERSION.SDK_INT<=30)"set-user-rotation" else "user-rotation"
            val mode=if(automatic=="1")"free" else "lock"
            fun run(value:String){ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("wm $command $value")).use{it.readBytes()}}
            fun await(checkState:()->Boolean){val until=System.nanoTime()+3_000_000_000L;while(!checkState()&&System.nanoTime()<until)Thread.sleep(25);check(checkState())}
            run("lock $rotation")
            await{read("user_rotation")==rotation&&read("accelerometer_rotation")=="0"}
            if(mode=="free")run("free")
            await{read("accelerometer_rotation")==automatic&&read("user_rotation")==rotation}
        }
    }
    override fun onActivityCreated(activity:Activity,state:Bundle?)=Unit
    override fun onActivityStarted(activity:Activity)=Unit
    override fun onActivityResumed(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivityStopped(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
    override fun onActivityDestroyed(activity:Activity)=Unit
}
