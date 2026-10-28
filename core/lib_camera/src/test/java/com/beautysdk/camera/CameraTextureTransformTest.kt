package com.beautysdk.camera

import org.junit.Assert.*
import org.junit.Test

class CameraTextureTransformTest {
    private fun point(m: FloatArray,x: Float,y: Float) = floatArrayOf(m[0]*x+m[4]*y+m[12],m[1]*x+m[5]*y+m[13])
    @Test fun cropIsAppliedBeforeProducerTransform() {
        // 常见 ST 垂直翻转：逻辑左上 crop 应映射为完整缓冲区的 (1/4,1/4)。
        val surface=floatArrayOf(1f,0f,0f,0f,0f,-1f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f)
        val matrix=CameraTextureTransform.cropped(surface,800,600,200,150,600,450)
        assertArrayEquals(floatArrayOf(.25f,.25f),point(matrix,0f,0f),.00001f)
        assertArrayEquals(floatArrayOf(.75f,.75f),point(matrix,1f,1f),.00001f)
        // 非对称的生产者裁剪，验证矩阵乘法顺序不能反过来。
        surface[0]=.8f; surface[12]=.1f
        val combined=CameraTextureTransform.cropped(surface,800,600,200,150,600,450)
        assertArrayEquals(floatArrayOf(.3f,.25f),point(combined,0f,0f),.00001f)
    }
    @Test fun allRotationsAndMirrorsMapDisplayCornersBackToRaw() {
        val corners=listOf(0f to 0f,1f to 0f,1f to 1f,0f to 1f)
        val expected=listOf(listOf(0,1,2,3),listOf(3,0,1,2),listOf(2,3,0,1),listOf(1,2,3,0))
        for (rotation in 0..3) for (mirror in listOf(false,true)) {
            val matrix=CameraTextureTransform.displayToRaw(rotation*90,mirror)
            corners.forEachIndexed { index,(x,y) ->
                val sourceIndex=if (mirror) listOf(1,0,3,2)[index] else index
                val target=corners[expected[rotation][sourceIndex]]
                assertArrayEquals("$rotation/$mirror/$index",floatArrayOf(target.first,target.second),point(matrix,x,y),.00001f)
            }
        }
    }
    @Test fun compositionKeepsInputsAndRejectsInvalidCrop() {
        val source=CameraTextureTransform.displayToRaw(90,true)
        val saved=source.copyOf()
        val identity=CameraTextureTransform.displayToRaw(0,false)
        assertArrayEquals(source,CameraTextureTransform.multiply(source,identity),0f)
        assertArrayEquals(saved,source,0f)
        assertThrows(IllegalArgumentException::class.java) { CameraTextureTransform.cropped(source,10,10,5,0,4,10) }
    }
    @Test fun cameraProducerOrientationIsRemovedBeforeCropForEveryLensAndRotation() {
        val flip = floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)
        // 独立列出 GL 中绕中心正向旋转的二维仿射系数，不用被测函数构造期望值。
        val rotations = listOf(
            floatArrayOf(1f,0f,0f,1f,0f,0f), floatArrayOf(0f,1f,-1f,0f,1f,0f),
            floatArrayOf(-1f,0f,0f,-1f,1f,1f), floatArrayOf(0f,-1f,1f,0f,0f,1f))
        for ((index, r) in rotations.withIndex()) for (front in listOf(false,true)) {
            val rotate = FloatArray(16).apply {
                this[0]=r[0]; this[1]=r[1]; this[4]=r[2]; this[5]=r[3]
                this[12]=r[4]; this[13]=r[5]; this[10]=1f; this[15]=1f
            }
            val mirror = floatArrayOf(if (front) -1f else 1f,0f,0f,0f, 0f,1f,0f,0f,
                0f,0f,1f,0f, if (front) 1f else 0f,0f,0f,1f)
            val producer = CameraTextureTransform.multiply(flip,CameraTextureTransform.multiply(rotate,mirror))
            val normalized = CameraTextureTransform.compensateCamera(producer,index*90,front)
            val crop = CameraTextureTransform.cropped(normalized,800,600,80,120,640,540)
            assertArrayEquals(floatArrayOf(.1f,.2f),point(crop,0f,0f),.00001f)
            assertArrayEquals(floatArrayOf(.8f,.9f),point(crop,1f,1f),.00001f)
        }
    }
}
