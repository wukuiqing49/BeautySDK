# Demo入口与效果调整（2026-10-27）

首页“资源中心”独立管理导入、HTTPS目录下载及已安装资源；下载/导入不自动启用。相机默认大屏显示处理结果、小窗显示原图，底部按分类选择已安装效果与调节参数，不混入网络或文件管理。背景小图的显式高清映射、配置状态和限制见[场景效果](doc/SDK_SCENE_EFFECTS.md)。

# BeautySDK

Android 直播视频帧美颜与挂件 SDK，API 24+，JDK 17。引擎提供帧 → SDK 处理 → 引擎编码推流；核心美颜 SDK 不接管摄像头、音频、编码或推流；可选 lib_camera 为功能测试提供采集输入。

## 模块与能力

| 模块 | 职责 |
| --- | --- |
| `core/lib_beauty` | 公共帧/人脸协议、BeautySession、有界异步分析、CPU 参考渲染、ES3 纹理渲染 |
| `core/lib_beauty_gpupixel` | 固定上游源码的磨皮/美白 Shader 与 LUT，C++/JNI 同 EGL 纹理适配；相机 Demo 默认启用 |
| `core/lib_beauty_mediapipe` | MediaPipe Tasks 0.10.32，本地模型、478 关键点及语义锚点 |
| `core/lib_camera` | CameraX 预览、前后摄与 RGBA 帧输出，已接入实时美颜测试页 |
| `feature/feature_app` | 静态图方案对比编排；宿主使用 WKQ 基础框架 |
| `app` | XML 演示页，图片选择、方案切换、原图/处理图及参数 |
| `core/core_base`、`core/core_utils`、`feature/feature_res` | 宿主基础包装和共享资源 |

后端 api 依赖共享库，共享库不依赖 MediaPipe、WKQ 或直播厂商。当前人脸后端为 MediaPipe，相机默认使用 MediaPipe + GPUPixel。

基础效果：局部颜色相似度加权平滑、线性美白、暖/冷色滤镜。可选 GPUPixel 适配库提供 box blur/高通磨皮和 LUT 美白，相机 Demo 默认使用 MediaPipe + GPUPixel，隐藏后端切换，基础 OpenGL 仅保留为 SDK 内部对照；静态图片页仍使用原 CPU 参考实现。支持程序化眼镜/胡子/礼帽及最多三层 PNG 图片贴纸，Demo 内置六张本地透明素材。Android GPUPixel另有实验性美型/美妆，MediaPipe提供可选人像分割；首页独立资源中心管理本地及显式HTTPS下载。当前不提供3D遮挡。GPUPixel 是源码算法适配，不包含其完整运行时，来源、授权和限制见 [模块说明](core/lib_beauty_gpupixel/README.md)。设备夹具验证范围见[测试报告](doc/testing/TEST_REPORT.md)，真人动态观感和持续性能仍待验收。


## RGBA 接入

```kotlin
val session = BeautySession(
    factory = MediaPipeFaceAnalyzer.factory(context),
    onError = { error -> /* worker 或渲染线程回调，宿主负责日志/UI 切线程 */ }
)
session.settings = BeautySettings(
    smoothing = 0.4f, whitening = 0.3f,
    glasses = true, moustache = true
)
session.start()
val input = RgbaFrame(FrameInfo(width, height, timestampNs, rotation, mirrored), rgba, rowStride)
val output = session.process(input)
// 依选定引擎版本的编码前处理接口交回 output。
session.stop()
session.close()
```

`BeautyProcessor` 保留原有旁路兼容 API；实际处理使用 `BeautySession`。CPU 渲染用于 CPU 帧集成和效果验证，高分辨率实时使用优先选择 GL 并真机测量。

所有生命周期及 process/submitAnalysis 均在同一宿主处理线程调用。RGBA8888 为非预乘 alpha，原始像素左上角原点、x 右/y 下，支持行 padding；从 ByteBuffer.position 起算。SDK 不修改输入。分析忙时不复制新图，空闲时同步复制缩小 RGB 后交给 worker；借用输入不会逃逸到异步线程。CPU 非旁路输出是独占副本，输出紧凑 RGBA；旁路返回原对象，仍借用宿主资源。

最多一张正在分析的图像，无待分析队列。VIDEO 推理在 worker 同步执行，外层对引擎异步；单人模式关闭表情/矩阵输出。结果按原始采样纳秒时间戳关联，默认有效期 200ms；无人脸、超时、尺寸/旋转/镜像变化隐藏挂件。切摄像头即使尺寸相同也应 stop/start；下一代分析器在 worker 重建，不沿用旧跟踪状态。

每个会话时间戳严格递增，stop/start 可重新计时；close 不可恢复。close 不等待正在运行的推理，立即禁止发布新结果，随后在 worker 释放后端。渲染失败通过 onError 报告并返回原帧；生命周期/参数错误直接抛出。

## 纹理接入

```kotlin
// 在宿主当前 ES3 EGL context/thread 中创建与调用；SDK 不创建 EGL。
val session = BeautySession(
    factory = MediaPipeFaceAnalyzer.factory(context),
    renderer = GlBeautyRenderer()
)
session.start()
session.settings = BeautySettings(whitening = 0.3f, glasses = true)
// 若需要人脸挂件，显式提供与纹理逻辑画面一致的 RGBA 原图用于分析。
session.submitAnalysis(rgbaAnalysisFrame)
val output = session.process(textureFrame)
```

支持 GL_TEXTURE_2D；OES 路径要求 `GL_OES_EGL_image_external_essl3`，当前设备自动测试覆盖 2D，OES 尚未实测。纹理输入不会隐式读回，只有纹理且不提供 RGBA 分析图时仍可做基础美颜，但不会自动得到关键点。

纹理矩阵负责生产者采样/裁剪，语义锚点对应采样后的逻辑画面；分析图必须匹配这一空间。输出 identity 采样矩阵，尺寸和 rotation/mirrored 元数据保持不变，SDK 不把旋转/镜像重复烘焙到像素。分析图 FrameInfo 的尺寸/旋转/镜像必须与纹理帧一致。

GL 输出归 SDK，**只有效至下一次 render 或 close**；引擎异步消费必须自行复制或建立同步。不可把输出纹理作为下次输入形成 FBO 反馈。SDK 恢复绘制 framebuffer、viewport、program、纹理单元0绑定、sampler、unpack buffer、颜色写掩码及修改过的 enable 状态。GL 创建/渲染/释放必须在创建时的 context/thread；context 丢失后新建 renderer/session。

## 构建与验证

```powershell
python scripts/verify_models.py
.\gradlew.bat :core:lib_beauty:testDebugUnitTest
.\gradlew.bat :core:lib_beauty:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :app:assembleDebug
.\gradlew.bat :app:lintDebug :core:lib_beauty:lintDebug :core:lib_beauty_mediapipe:lintDebug
# 仅本地未签名 R8 验证，正式发布默认要求正式签名。
.\gradlew.bat :app:assembleRelease -PrequireReleaseSigning=false
# 公开官方测试人像，只打入测试 APK。
python scripts/prepare_test_fixture.py
.\gradlew.bat :app:assembleDebugAndroidTest
```

AAR 位于各库 `build/outputs/aar`，Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。只拷贝一个 AAR 不会自动携带传递依赖；宿主必须同时解析共享库、所选运行库和模型。共享库保留本地 Maven 发布任务；两个后端尚未配置远端发布。

Zego/声网具体版本尚未选定，未实现厂商适配、远端合成及持续直播性能验证。静态图耗时包括转换/模型/CPU 处理，不是实时 FPS。模型来源与许可记录见 [MODELS.md](doc/third-party/MODELS.md)，本轮证据见 [TEST_REPORT.md](doc/testing/TEST_REPORT.md)。

## 自定义相机测试

运行 App 后点击“相机测试”，授权后自动启动。相机原图作为底层大画面，左上角为处理效果；点击小窗交换主副画面，底部提供美颜、贴纸、调试三个面板。磨皮/美白可调强度，六张贴纸以缩略图选择，默认不佩戴；启停与检测方案位于调试面板。后台停止、返回按启动状态恢复；不录像或上传。

实时页改为 `GpuBeautyPreview`：CameraX Preview → SurfaceTexture/OES → GlBeautyRenderer/FBO → 同一 EGL context 的双画面。渲染不再逐帧生成 Bitmap、执行 CPU 美颜或 GPU 读回；独立 ImageAnalysis 请求320×240、最多30次/秒，设备实际协商可能不同。MediaPipe 仍为 CPU VIDEO 推理，使用 Bitmap 作为其输入，这不等于 GPU 推理或整个链路零拷贝。挂件仅使用150ms内、同会话/方向/方案/ViewPort的结果；渲染不会等待检测。新 GPU 相机链路仅完成本地验证，真实跟随、OES视觉与性能待真机验收。详见 [GPU 管线说明](doc/GPU_CAMERA_PIPELINE.md)。

## PNG 贴纸体验

进入静态图页选择图片，或打开“自定义相机测试”；后端选择 MediaPipe。静态图页用款式及开关配置后点击应用；实时页打开贴纸面板，点击圆框/爱心眼镜、胡子或礼帽/皇冠/猫耳卡片，下一帧使用新设置；再次点击取消，清除按钮移除全部贴纸。

六张素材位于 feature/feature_app/src/main/assets/beauty_stickers，由 DemoStickerCatalog 按 manifest 解码缓存，经 BeautySettings.stickerPack 送入共享 CPU/GL 渲染器；无脸、结果过期或缺锚点则隐藏。实时页已改用 OES/GPU 合成实现，CameraBeautyController 保留作 CPU 参考适配，不再用于页面；GPU 真机验收、直播引擎纹理交回及远端合成仍待完成。

共享库的自定义图片 API 及纹理有效期见 [lib_beauty 接入说明](core/lib_beauty/README.md)。

## 跨平台贴纸制作与加载

在线制作：[贴纸工坊](https://beautystickerworkshop.pages.dev/)。同事可直接制作并导出ZIP，再通过SDK加载；源码位于私有仓库 [BeautyStickerWorkshop](https://github.com/wukuiqing49/BeautyStickerWorkshop)。

网页工坊已拆至同级独立项目 ../BeautyStickerWorkshop，可单独建立Git仓库发布Cloudflare Pages：构建命令 npm run build，输出目录 dist。SDK保留 resources/stickers/v1 跨平台协议、iOS资源组件及Android加载器。完整部署说明见独立项目README。


### 场景与美颜资源工坊 V2

独立Web项目 `C:/work/AI/BeautyStickerWorkshop` 新增背景、运镜时间轴、美颜预设编辑和ZIP导入导出；贴纸仍沿用V1。Android公共入口 `EffectResourcePack.loadZip` / `applyTo`，Demo工坊可加载三个内置示例、导入资源并分类记忆。完整跨端协议与平台边界见 [资源V2](resources/effects/v2/README.md)。新版Web已推送并通过Cloudflare Pages自动发布，在线入口：https://beautystickerworkshop.pages.dev/effects 。

## 资源制作与加载

Demo 使用资源中心加载内置完整主题、单类资源、本地 ZIP 与 HTTPS 目录。Web 制作端独立位于 BeautyStickerWorkshop 仓库。V3 美颜格式、目录、缓存与性能统计契约见 [资源说明](resources/effects/v3/README.md)；本轮实测与未验证项见 [测试报告](doc/testing/TEST_REPORT.md)。


图片睫毛制作与跨平台资源消费：[V4资源说明](resources/effects/v4/README.md)。Web独立工程为BeautyStickerWorkshop，新增eyes.html；本地实现尚未发布至Pages。


## 统一资源闭环（2026-10-27）

已新增core/lib_beauty_resources及实验室8分类资源面板，内置17个示例；资源制作使用独立Web的resource-studio.html。名称/翻译/封面、参数预设、图片睫毛、贴纸、背景、运镜、套装、默认JSON及目录交付走同一协议。

[资源制作与接入手册](resources/unified/v1/README.md) · [实施方案及状态](doc/design/BEAUTY_RESOURCE_WORKFLOW_IMPLEMENTATION.md) · [测试报告](doc/testing/TEST_REPORT.md)。

## 可选手势分析

新增本地MediaPipe手势模型，官方7种静态分类及21点手部坐标；另有实验性OK、伸指数量0～5、捏心/双手比心/合十、挥手/拍手/画圈、捏合拖动/双手缩放规则。独立单在途支路默认关闭，按需开启；只输出结果和事件，动画由宿主实现。相机页新增开关与事件诊断。自定义手势不代表真人识别率或持续性能已验收。接入契约见[SDK_GESTURES](doc/SDK_GESTURES.md)，实测范围见[测试报告](doc/testing/TEST_REPORT.md)。
