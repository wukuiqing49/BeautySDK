package com.beautysdk.mediapipe

import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature
import android.content.Context
import com.beautysdk.*
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/** 本地固定模型、最多双手。VIDEO同步调用仅在SDK有界worker内执行，不占相机/GL线程。 */
class MediaPipeGestureAnalyzer @JvmOverloads constructor(
    context: Context,
    modelAsset: String="gesture_recognizer.task",
) : GestureAnalyzer {
    private val owner=Thread.currentThread()
    private var closed=false
    private var previousMs=-1L
    private val rgb=MediaPipeRgbInput()
    private val detector=GestureRecognizer.createFromOptions(context.applicationContext,
        GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAsset).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(2)
            .setMinHandDetectionConfidence(.6f).setMinHandPresenceConfidence(.6f).setMinTrackingConfidence(.6f).build())

    override fun analyzeRgba(frame: RgbaFrame,maxSide: Int): List<HandObservation> {
        if (!BeautyLicensing.allows(LicensedFeature.GESTURE)) return emptyList()
        check(Thread.currentThread()===owner && !closed){"Gesture analyzer must run on its owner worker"}
        val buffer=rgb.prepare(frame,maxSide)
        val image=ByteBufferImageBuilder(buffer,rgb.width,rgb.height,MPImage.IMAGE_FORMAT_RGB).build()
        try {
            val timestamp=maxOf(previousMs+1,frame.info.timestampNs/1_000_000)
            previousMs=timestamp
            val result=detector.recognizeForVideo(image,timestamp)
            val coordinates=AnalysisCoordinates(frame.info)
            return result.landmarks().mapIndexedNotNull {index,points ->
                if(points.size!=21)return@mapIndexedNotNull null
                val hand=result.handedness().getOrNull(index)?.maxByOrNull {it.score()}
                val gesture=result.gestures().getOrNull(index)?.maxByOrNull {it.score()}
                HandObservation(points.map {coordinates.toRaw(FacePoint(it.x(),it.y()))},
                    when(hand?.categoryName()) {"Left"->Handedness.LEFT;"Right"->Handedness.RIGHT;else->Handedness.UNKNOWN},
                    hand?.score()?:0f,when(gesture?.categoryName()) {
                        "Closed_Fist"->GestureKind.CLOSED_FIST;"Open_Palm"->GestureKind.OPEN_PALM
                        "Pointing_Up"->GestureKind.POINTING_UP;"Thumb_Down"->GestureKind.THUMB_DOWN
                        "Thumb_Up"->GestureKind.THUMB_UP;"Victory"->GestureKind.VICTORY
                        "ILoveYou"->GestureKind.I_LOVE_YOU;else->null
                    },gesture?.score()?:0f)
            }
        } finally {image.close()}
    }
    override fun close() {
        check(Thread.currentThread()===owner){"Close gesture analyzer on its owner worker"}
        if(closed)return
        closed=true
        try {detector.close()}finally {rgb.clear()}
    }
    companion object {
        @JvmStatic @JvmOverloads fun factory(context: Context,modelAsset: String="gesture_recognizer.task"): GestureAnalyzerFactory {
            val app=context.applicationContext
            return GestureAnalyzerFactory {MediaPipeGestureAnalyzer(app,modelAsset)}
        }
    }
}
