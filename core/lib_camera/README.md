# lib_camera

自定义相机采集库，路径 `:core:lib_camera`，namespace `com.beautysdk.camera`，API 24+ / JDK 17。基于 CameraX 1.4.2：预览、前后摄切换、独立 RGBA 帧、生命周期启停和错误回调。

## 使用

宿主声明 `android.permission.CAMERA` 并申请运行时权限。模块不自行请求权限，不录音、不拍照保存、不录像、不联网。依赖不包含美颜库、算法模型或直播引擎。

```kotlin
val camera = CustomCameraController(context,
    listener = object : CameraFrameListener {
        override fun onFrame(frame: CameraFrame) {
            // 独立 RGBA8888 副本；此回调在专用单线程执行。
            // frame.readOnlyPixels() 支持在回调后继续读取，无需 release。
        }
        override fun onStreamStopped() {
            // 同一个帧线程，在先前回调结束后释放处理器。
        }
    },
    onStatus = { state -> /* 主线程：STARTING / BOUND / STOPPED / ERROR */ },
    onError = { error -> /* 主线程，展示或记录错误 */ }
)
// 所有控制方法均在主线程调用，使用具有有效生命周期的宿主。
camera.start(lifecycleOwner, previewView, CameraConfig(
    lens = CameraLens.FRONT, width = 640, height = 480, maxAnalysisFps = 10
))
camera.switchCamera()
camera.stop()
camera.close()
```

在宿主 STARTED 时交付帧，ON_STOP 暂停交付并通知 onStreamStopped，ON_DESTROY 自动 close。显式 stop 会解除自己的 use case，需再次 start。close 幂等，之后不可重启；不调用全局 unbindAll，不影响其他组件的 use case。

切换到不存在的镜头会报告错误并保留当前镜头；初始化/绑定错误通过回调报告，CameraX 异步设备错误也转发。BOUND 仅代表绑定成功，不能当作第一帧或相机性能通过。

## 帧协议

- RGBA 顺序为 R、G、B、A；输出紧凑 `width*4` 行 stride，处理输入行/像素 stride 和 crop；ImageProxy 总在 finally 关闭。
- 像素是裁剪后的原始方向，左上角原点；显示时先按 rotationDegrees 顺时针旋转，再按 mirrored 做水平镜像。前摄 mirrored=true，PreviewView 自行处理预览镜像，不要再对原始预览重复镜像。
- timestampNs 是相机采样单调纳秒时间线，不能假定与 System.nanoTime 同源。sessionId 在重启、镜头切换或生命周期暂停恢复时变化；异步消费者使用 isCurrent(frame) 丢弃过期输出。
- 请求分辨率由设备协商，可能与配置不同，消费者必须使用实际帧尺寸。当前优先4:3预览/分析，不承诺任意宽高比或预览和分析逐像素重合。
- maxAnalysisFps 只限制分析回调，不是摄像头硬件帧率。KEEP_ONLY_LATEST 加回调前限频避免积压；帧转换包含 CameraX YUV→RGBA 和内存复制，不是零拷贝。
- onFrame 与 onStreamStopped 串行；stop/close 返回时可能仍有已经开始的帧回调，随后按顺序执行停止通知。用户回调应尽快返回，不要自行创建无界队列。

## 美颜测试页

启动演示 App，点击“相机测试”，授权后启动。实时页面使用 feature_app 的 GpuBeautyPreview，CameraBeautyController 仅保留作 CPU 参考。GPU 双画面、底部美颜/贴纸/调试面板已实现；本轮仅进行本地验证，不代表实机视觉或性能通过。

## 自定义纹理入口

`startTexture(owner, surfaceProvider, rotation, config)` 在主线程绑定 CameraX Preview 与 ImageAnalysis。Preview 尺寸由 config 请求，分析单独请求320×240；二者使用同一 UseCaseGroup/ViewPort。宿主必须根据 SurfaceRequest 的实际 resolution、cropRect、rotationDegrees 及 SurfaceTexture 生产者矩阵进行采样，不假定请求尺寸等于实际尺寸，也不能仅按尺寸比例猜测不同视场的坐标。

SurfaceProvider 的创建/使用/释放由宿主 EGL 线程负责：只有 provideSurface 的完成回调确认 CameraX 已释放 Surface 后，才能释放对应 SurfaceTexture。停止后或切换会话的 SurfaceRequest 被拒绝。queueEvent 不保证存在 current EGL context，清理时必须校验资源创建时的 context。

`CameraTextureTransform` 为列主序采样矩阵工具：cropped 组合 ST 与 ViewPort 裁剪；displayToRaw 逆转正/镜像；工具不修改输入数组。新增 trySwitchCamera 返回是否发起切换，原 switchCamera API 保留。

分析线程使用 needsFrame() 在 RGBA 打包前判断需求；纯纹理旁路返回 false 可跳过复制，仍然不声称 CameraX 内部的 YUV→RGBA 转换已被消除。KEEP_ONLY_LATEST 保持有界，无 SDK 自建待处理队列。

```powershell
.\gradlew.bat :core:lib_camera:testDebugUnitTest :core:lib_camera:assembleDebug :core:lib_camera:assembleRelease :core:lib_camera:lintDebug
```

产物：`core/lib_camera/build/outputs/aar/lib_camera-release.aar`。AAR 不自动内嵌 CameraX 传递依赖；用 Gradle project 依赖或同时提供对应 Maven 依赖。设备证据与剩余项见 `doc/testing/TEST_REPORT.md`。
