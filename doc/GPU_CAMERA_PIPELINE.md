# 2026-10-26 公共会话更新

相机分析回调现在向BeautyAnalysisRuntime提交，GL回调通过BeautyEngineConnector → BeautyEnginePipeline(sharedAnalysis=...) → BeautySession合成；相机只负责CameraX/OES生产、视场规范化和双画面显示。普通调参使用redraw重绘同一采样帧，不伪造时间戳。下文早期方案中的“直接snapshot后调用renderer”已由公共会话替代。详见SDK_PIPELINE.md与最新测试报告。

---

# GPU 相机管线（实现及本地验证）

## 选择与边界

新增可选 `lib_beauty_gpupixel`：固定导入上游美颜 Shader 与 LUT，由 C++/JNI 在同一 EGL context 完成磨皮美白，随后复用原贴纸。相机页“美颜”可切换基础 OpenGL/GPUPixel，默认基础 OpenGL（效率优先）。不是完整上游运行时接入，当前未引入其独立线程/检测器/模型；详细来源、绘制次数和内存约定见 `core/lib_beauty_gpupixel/README.md`。新后端只做本地验证，设备性能和视觉未验收。

当前实时页以效率为优先，使用 OpenGL ES 3 Shader 执行磨皮、美白及 PNG 挂件 source-over 合成，Kotlin 仅负责采集适配、状态和调度。没有把 CPU 逐像素循环搬到 C++，也没有在直播关键线程等待人脸推理。静态图片页仍使用 CPU 参考渲染，便于算法与像素校验。

```text
CameraX Preview → SurfaceTexture/OES → BeautyEngineConnector → BeautyEnginePipeline → GlBeautyRenderer → 复用2D FBO → GPU 双画面显示
CameraX ImageAnalysis → 借用带crop/stride的RGBA视图 → 单次转换到复用RGB DirectByteBuffer → MediaPipe CPU VIDEO → 有效锚点
```

显示与分析独立：Preview 请求640×480；分析请求320×240、最多30次/秒，实际以 CameraX 协商为准。没有预览 GPU 读回或逐渲染帧 Bitmap；MediaPipe 适配通过 ByteBufferImageBuilder 包装 RGB，不创建逐帧 Bitmap。实时页同步借用 CameraX RGBA，缩放/去alpha/旋转/镜像一次写入复用 DirectByteBuffer；CameraX 内部仍有 YUV→RGBA，MediaPipe 内部也可能复制，因此不声称完整零拷贝。

## 减少跟随滞后的处理

- 取消旧实时页8Hz渲染限制，不让CPU美颜阻塞取下一预览纹理。
- 相机分析使用 KEEP_ONLY_LATEST，慢时丢帧，不排队；单一分析线程创建/调用/关闭检测器。
- 不等待分析返回；每帧只读取一份不可变检测结果。
- 按相机会话、后端、旋转、镜像和共享ViewPort比例过滤；未来结果不能使用，旧结果超过150ms隐藏。
- 150ms是使用截止，不是延迟保证。尚未加入速度外推，避免未经验证的预测在急转头时过冲；实际是否需要预测、检测delegate或降采样仍需测量。
- UI统计最多每250ms发布一次，最多一项待处理；矩阵和显示uniform位置复用，素材按选择变化解码/上传，FBO按尺寸变化重建。

## 坐标及资源

两UseCase共享 ViewPort，以各自 cropRect 得到相同传感器视场；因此低分辨率检测的归一化锚点可用于预览裁剪后的逻辑纹理。生产者ST变换、ViewPort裁剪先合成为采样矩阵，美颜在原始方向合成；显示时仅做一次顺时针旋转及前摄水平镜像。JVM覆盖四旋转×两镜像和非对称crop的变换顺序，不能替代真实OES像素验收。

单个 GLSurfaceView/context 直接绘制大图和小图，不跨context传递纹理ID。GlBeautyRenderer输出仅有效到下一render/close；本地在当前render内消费。引擎若异步持有，必须另做共享EGL/fence或拷贝和所有权适配。

SurfaceRequest携带会话代次，旧请求拒绝；Surface只在CameraX使用完成后释放。EGL线程上的任务也可能处于 EGL_NO_CONTEXT，销毁时校验创建context，已失效context的资源由EGL回收，禁止跨context删除同号句柄。context重建后重新创建Shader/FBO，不复用旧句柄。渲染失败回退当前原纹理，诊断不能继续声称贴纸已绘制。

## 验证范围

本轮只进行构建、Lint、JVM及UI/i18n静态检查，详见 testing/TEST_REPORT.md。曾启动的设备回归在早期版本暴露EGL释放问题；已按代码分析修订，用户要求暂停真机测试后未再次运行设备验证。

未验证：新管线首次出帧、前后摄真实OES方向/裁剪、快速转头/遮挡、后台恢复与持续context丢失、实际FPS/p95延迟/内存/温升、GPU执行时间、端到端时延，以及Zego/声网编码前纹理交回和远端合成。调试页GL提交耗时只计CPU提交调用，不代表GPU完成或整体延迟。

参考：[CameraX变换](https://developer.android.com/media/camera/camerax/transform-output)、[ViewPort](https://developer.android.com/reference/androidx/camera/core/ViewPort)。


## 固定测试主线（用户决定，2026-10-23）

实时路径固定为 CameraX 测试输入 → MediaPipe 按需关键点分析 + 基础 OpenGL 美颜/贴纸 → 双画面预览。生产目标输入仍来自直播引擎，CameraX 只作为测试适配器。

相机页现有默认值已满足该组合；当前使用 MediaPipe + GPUPixel，RFB 已移除。静态图片页使用 CPU 参考实现，不承担实时性能验收。本轮只固定范围，不新增后端、不将静态页改成 GPU、不宣称已完成公共会话收敛或原生预处理。

后续验收以原图、仅基础美颜、贴纸检测与合成、美颜叠加贴纸四组为准。分别测预处理、推理、检测结果年龄、渲染提交和实际帧率；GPU 执行耗时须独立测量。前后摄、旋转、镜像、进出画面及启停均须覆盖。


## 结果时间选择（2026-10-23修复）

分析与预览支路可能先后到达，不能只存最新检测。公共FaceAnalysisHistory保留最多8条结果，渲染选择采样时间不晚于当前视频帧的最新结果；相机和AsyncFaceAnalysis共用此逻辑。缓存只含人脸观测和时间，不保存图像，不新增推理队列。无脸/错误是时间线上的明确失效信号，不能绕过它找更早人脸；150ms预览有效期不变。切流/镜头/方向/分析配置代次须丢弃旧结果。该机制处理两支路到达顺序导致的闪烁，不替代模型精度或推理耗时优化。


## 检测缓冲输入（2026-10-23）

- `FaceAnalyzer.analyzeRgba` 接收独立于相机/厂商的 RgbaFrame。MediaPipe 覆盖为融合转换；其他后端通过默认方法兼容 RGB 接口。
- `ReusableRgbInput` 按输出方向一次采样源 RGBA，支持 position、行 padding、等比例缩小、四种旋转和镜像。尺寸不变时复用原生 RGB 缓冲，不创建中间像素数组。MediaPipe 对直接缓冲使用 C++/JNI 融合转换；Kotlin 实现用于堆缓冲回退和像素参考。未引入 libyuv，原生实现只负责这条明确的 RGBA→RGB 小图链路，不宣称已有完整 YUV/引擎适配。
- 相机监听器显式 `acceptsBorrowedFrames=true` 才借用输入；仅 pixelStride=4 使用该路径，特殊像素步长保留兼容打包。默认监听器仍取得独占副本，不改变旧异步消费者的契约。
- 回调内同步分析，返回后先使借用帧失效，再关闭 ImageProxy；不得把借用帧/ByteBuffer保存到任务队列。实时页仍在独立 CameraX worker 上执行，GL 不等待模型。
- MediaPipe 同步 detectForVideo 返回并关闭 MPImage 后才能覆盖缓冲。切会话/停止时释放模型和缓冲引用。
- 生产引擎若回调属于关键采集线程，必须使用有界 worker 和 retain/release 或缓冲池复制，不能照搬相机同步调用。尚未完成 ZEGO/声网适配。
- 150ms 结果有效期不变；减少输入开销不保证推理、跟随和闪烁已经达到目标。测试证据以 TEST_REPORT.md 最新轮次为准。

原生转换库 `beauty_rgb_input` 使用项目既有 NDK/CMake 版本，单次调用不分配内存、同步读取/写入宿主持有的 DirectByteBuffer，并校验容量、stride、尺寸和旋转。JNI 入口包含 consumer keep 规则，链接设置16KB页对齐；是否通过设备及打包验证见最新测试报告。


## SDK统一运行时（2026-10-26，取代上文相机回调内同步推理方式）

CameraX只提交RGBA视图；公共AsyncFaceAnalysis在回调内批量复制到复用DirectByteBuffer，立即交还相机输入，再在SDK worker调用MediaPipe原生转换/推理。忙时在复制前丢帧，没有待分析队列。相机Demo不再自行创建/调用检测器，也不再使用PreviewDetectionHistory；其GL显示继续调用公共GlBeautyRenderer。

BeautySession同样使用该运行时，支持显式allowScaledAnalysis以接收同视场小图并处理高清纹理。复制是为异步生命周期安全引入的一次必要边界复制，不是Bitmap转换；不宣称零拷贝。调试统计新增copyMs、queueMs和intervalMs，允许区分回调成本、排队以及模型结果更新周期。

此轮统一输入和调度，没有加入厂商依赖，没有实现I420/NV21适配、跨裁剪映射或GPU输入推理；具体接入契约见core/lib_beauty/README.md。
