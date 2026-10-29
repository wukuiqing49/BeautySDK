package com.beautysdk.demo

import androidx.test.platform.app.InstrumentationRegistry
import com.beautysdk.resources.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinResourceAssetsDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun isolated(action:(BeautyResourceManager)->Unit){
        val dir=File(inst.targetContext.cacheDir,"assets-library-${System.nanoTime()}")
        try{action(BeautyResourceManager(dir))}finally{dir.deleteRecursively()}
    }
    @Test fun zippedAndExpandedAssetsInstallSameResourcesAndReuseHashes(){isolated{manager->
        val zip=manager.installDeliveryFromAssets(inst.context.assets,"gesture_library_delivery.zip")
        val directory=manager.installDeliveryDirectoryFromAssets(inst.context.assets)
        assertEquals(zip.resources.map{it.hash},directory.resources.map{it.hash})
        assertEquals(zip.gestures.map{it.descriptor.sha256},directory.gestures.map{it.descriptor.sha256})
        assertEquals(4,manager.listGestureResources().size)
        assertEquals(zip.resources.size,manager.list().size)
        assertEquals("Gesture Effects",directory.library!!.text(directory.library!!.gestureCategory!!.nameKey,"en-US"))
        BeautyEffectComposer(manager,ResourceCapabilities.androidGpuPixel()).prepare(directory.defaults.state).use{assertNotNull(it.settings)}
        assertTrue(manager.installDeliveryFromAssets(inst.context.assets).resources.isNotEmpty())
    }}
    @Test fun missingUnsafeCancelledAndNonDeliveryAssetsLeaveNoInstalledResources(){isolated{manager->
        fun reject(action:()->Unit){var failure:Exception?=null;try{action()}catch(e:Exception){failure=e};assertNotNull(failure);assertTrue(manager.list().isEmpty());assertTrue(manager.listGestureResources().isEmpty())}
        reject{manager.installDeliveryFromAssets(inst.context.assets,"../gesture_library_delivery.zip")}
        reject{manager.installDeliveryDirectoryFromAssets(inst.context.assets,"../beauty_resources")}
        reject{manager.installDeliveryFromAssets(inst.context.assets,"does_not_exist.zip")}
        reject{manager.installDeliveryDirectoryFromAssets(inst.context.assets,"does_not_exist")}
        reject{manager.installDeliveryDirectoryFromAssets(inst.context.assets,"beauty_resources/gesture/victory_sparkles")}
        val cancel=ResourceCancellation().apply{cancel()}
        reject{manager.installDeliveryDirectoryFromAssets(inst.context.assets,cancel=cancel)}
        reject{manager.installDeliveryFromAssets(inst.context.assets,"gesture_library_delivery.zip",cancel)}
    }}
}
