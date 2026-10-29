package com.beautysdk.demo

import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 只打开资源管理页，不启动相机、不保存屏幕或人脸图像。 */
class DeliveryReceiptDeviceTest {
    @get:Rule val backup = LabSceneBackup()
    @Test fun successfulReceiptSurvivesReopenAndFailedImport() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val bytes = inst.context.assets.open("library_delivery.zip").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val input = File(context.cacheDir, "receipt-test-${System.nanoTime()}.zip").apply { writeBytes(bytes) }
        var activity: ResourceCenterActivity? = null
        lateinit var model: ResourceCenterModel
        fun ready(allowError: Boolean = false): ResourceCenterModel.Ui {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                var value: ResourceCenterModel.Ui? = null
                inst.runOnMainSync { value = model.ui.value }
                value?.let { if (!it.busy && it.settings != null) {
                    if (!allowError) assertNull(it.error)
                    return it
                } }
                Thread.sleep(25)
            }
            error("Resource page timed out")
        }
        fun open() {
            activity = inst.startActivitySync(Intent(context, ResourceCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ResourceCenterActivity
            inst.runOnMainSync { model = ViewModelProvider(activity!!)[ResourceCenterModel::class.java] }
            ready()
        }
        fun close() { inst.runOnMainSync { activity?.finish() }; inst.waitForIdleSync(); activity = null }
        try {
            open()
            inst.runOnMainSync { model.import(Uri.fromFile(input)) }
            assertEquals(hash, ready().lastImportedDeliverySha256)
            inst.runOnMainSync {
                val label = activity!!.findViewById<TextView>(R.id.resource_delivery_receipt)
                assertTrue(label.isShown)
                assertTrue(label.text.contains(hash))
            }
            close(); open()
            assertEquals(hash, ready().lastImportedDeliverySha256)
            input.writeText("invalid archive")
            inst.runOnMainSync { model.import(Uri.fromFile(input)) }
            val failed = ready(allowError = true)
            assertNotNull(failed.error)
            assertEquals(hash, failed.lastImportedDeliverySha256)
        } finally { close(); input.delete() }
    }
}
