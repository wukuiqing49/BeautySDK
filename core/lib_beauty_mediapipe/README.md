# MediaPipe Face Landmarker 后端

本地 Tasks 0.10.32、单人脸478点、VIDEO同步推理，由公共 AsyncFaceAnalysis 在独立worker执行。支持直接RGBA输入经复用RGB缓冲预处理，无逐帧Bitmap。默认CPU。

## GPU 显式选择

```kotlin
val factory = MediaPipeFaceAnalyzer.factory(
    context,
    execution = MediaPipeFaceAnalyzer.Execution.GPU,
)
val analysis = AsyncFaceAnalysis(factory, keepLatestPending = true)
```

创建、分析、关闭必须同线程；公共worker遵守该约定。GPU仍使用RGB DirectByteBuffer，不是纹理零拷贝。未修改采样时间、坐标或150ms过期限制。创建失败报告错误，不静默降级以免对照数据误标。配置切换必须重建，reset会清除旧结果并在worker释放模型。

实时相机Demo“调试”弹框提供GPU对照开关，默认关闭。BeautyAnalysisWindow汇总每秒窗口内全部检测的数量、平均和最大耗时；BeautyAnalysis旧逐秒样本保留，不能据此计算完整P95/P99。初始化计入首个worker分析总耗时，不计入后端prepare/infer。

Pixel 8固定人像ABBA对照，各delegate80个计时样本，通过单脸478点、上唇和最后鼻点差异<0.03断言。GPU两轮总耗时中位数108.98/100.84ms，CPU185.26/103.38ms，轮次波动明显；GPU首轮初始化2249ms。测试不含摄像头、并行美颜渲染、真实转头、热稳态或远端推流，不能宣称闪烁已修复。详见doc/testing/TEST_REPORT.md。

线程契约参考：[官方Android示例](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/face_landmarker/android/app/src/main/java/com/google/mediapipe/examples/facelandmarker/FaceLandmarkerHelper.kt)。没有升级依赖。


## NativeFaceMotion实验跟踪

NativeFaceMotion实现公共FaceMotionEstimator，C++源码face_motion.cpp与原转换器编入同一个beauty_rgb_input库。没有新增OpenCV或其他依赖。支持小幅平移、平面旋转/缩放的局部图像匹配；不是模型替代，也不是经过全面验证的通用跟踪库。SDK契约、300ms校正上限及诊断见lib_beauty/README.md。参考帧必须独占不可变；当前图像仅在其所属跟踪worker顺序复用。

## 可选虚拟背景分割

新增MediaPipeForegroundMasks与GlForegroundMaskBridge，独立单在途worker、本地固定SelfieSegmenter模型；默认关闭，不替换FaceLandmarker，也不改变贴纸跟随。公共组合器支持基础GL/GPUPixel背景和运镜。接口、所有权、转换/读回成本及限制见[场景接入](../../doc/SDK_SCENE_EFFECTS.md)。模型来源及SHA256随assets记录，Apache声明复用SOURCE_LICENSE。

## 可选手势后端

`MediaPipeGestureAnalyzer`使用同一固定tasks-vision 0.10.32，加载gesture_recognizer.task，最多双手，21个二维关键点和7种官方手势分类。CPU VIDEO模式，由GestureAnalysisRuntime独立worker管理；复用原生RGBA→RGB转换，无逐帧Bitmap。不新增ONNX或so。默认关闭，不依赖是否需要检测人脸。只返回结果/事件，动画由宿主实现。具体限制与实验规则见[手势接入](../../doc/SDK_GESTURES.md)。
