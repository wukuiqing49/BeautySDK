package com.beautysdk.gpupixel

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Test

/** Web导出的完整顶点夹具，避免各端自行解释控制点、眼角顺序与镜像规则。 */
class TexturedEyelashGeometryTest {
    @Test fun allWebVerticesMatchForOpenClosedRotationMirrorAndMissingEye() {
        val lines=requireNotNull(javaClass.getResourceAsStream("/eye-geometry.tsv")).bufferedReader().readLines()
        var index=0;var cases=0
        while(index<lines.size) {
            val header=lines[index++].split('\t');assertEquals("CASE",header[0])
            val roots=(0..8).map {val p=lines[index++].split('\t');FacePoint(p[1].toFloat(),p[2].toFloat())}
            val points=(0..477).map {val p=lines[index++].split('\t');FacePoint(p[1].toFloat(),p[2].toFloat())}
            val material=EyeMakeup(StickerImage(1,1,intArrayOf(0)),roots,header[4].toFloat(),header[5].toBoolean())
            val out=FloatArray(1440){42f}
            assertTrue(texturedEyelashGeometry(FaceObservation(FaceBounds(0f,0f,1f,1f),landmarks=points),
                FrameInfo(header[2].toInt(),header[3].toInt(),0),material,out))
            assertEquals(42f,out[0],0f)
            for(vertex in 0..287) {
                val expected=lines[index++].split('\t')
                for(c in 0..3) assertEquals("${header[1]} vertex $vertex component $c",expected[c+1].toFloat(),out[288+vertex*4+c],.00002f)
            }
            cases++
        }
        assertEquals(6,cases)
    }
}
