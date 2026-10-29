# BeautySDK

当前版本为限定范围试用版。单一 Maven 入口、本地发布和独立消费者 Debug/Release/R8 已验证；真人画质、多设备持续性能和商业资格仍待验收。当前能力与限制统一见 [产品状态](doc/PRODUCT_STATUS.md)，首次接入见 [任务卡](doc/FIRST_INTEGRATION.md)。

**面向直播与实时视频的 Android 美颜、贴纸和场景效果 SDK，配套可视化效果工坊。**

以 **MediaPipe + GPUPixel + OpenGL ES 3** 为主要处理链路，提供美颜、美型、美妆、调色、人脸贴纸、虚拟背景、数字运镜和手势事件能力。通过统一资源包，把素材制作、效果搭配、资源交付与 Android 加载连接起来。

SDK 接收宿主提供的视频帧，处理后交回宿主；摄像头、音频、编码和推流由宿主或直播引擎管理。仓库附带 CameraX Demo，便于调试和验证。

[效果工坊](https://beautystickerworkshop.pages.dev/) · [工坊源码](https://github.com/wukuiqing49/BeautyStickerWorkshop) · [Android SDK 使用说明](doc/ANDROID_SDK_USAGE.md) · [公共帧 API](core/lib_beauty/README.md) · [资源协议](resources/unified/v1/README.md) · [测试报告](doc/testing/TEST_REPORT.md)

> 当前处于原型与集成验证阶段。Android 已有构建、单元测试和 Pixel 8 设备夹具证据；真实直播引擎接入、真人动态画质与持续性能仍待验收。详细验证范围以测试报告为准。

## SDK 能做什么

### 美颜、美型与美妆

| 能力 | 已实现内容 |
| --- | --- |
| 基础美颜 | 磨皮、美白，强度可调 |
| 美型 | 实验性二维瘦脸、大眼，以及鼻、下巴、嘴部形变参数 |
| 美妆 | 局部口红、腮红，透明 PNG 图片睫毛及绑定配置 |
| 调色 | 红润、亮度、对比度、饱和度、锐化与暖冷色调 |
| 参数组合 | 通过 `BeautySettings` 统一配置，支持预设、单类资源与完整主题 |

Android 主线使用 GPUPixel 固定版本的 Shader/LUT 源码适配，由 C++/JNI 在宿主当前 EGL 上下文执行。它不包含完整上游运行时或其人脸检测器；人脸定位由 MediaPipe 提供。基础 CPU/OpenGL 实现保留作参考与对照。

美型和美妆依赖有效的人脸关键点；无人脸、结果过期或关键点不足时跳过相关效果。二维美型不包含三维遮挡或侧脸透视保证。来源、实现与限制见 [GPUPixel 模块说明](core/lib_beauty_gpupixel/README.md)。

### 人脸定位与 PNG 贴纸

- 本地 MediaPipe Face Landmarker，支持单人脸 **478 个关键点**及语义锚点。
- 程序化眼镜、胡子与帽子，以及最多三层的静态透明 PNG 贴纸包。
- 支持眼镜、胡子、帽子等独立资源槽位，可组合佩戴；同槽位资源互斥。
- 支持素材缩放、偏移、旋转和锚点布局；无有效人脸时隐藏人脸挂件。
- 提供实验性帧间运动跟踪，允许关闭并与模型检测结果对照。

贴纸制作规范见 [贴纸手册](doc/stickers/README.md)，跨端包格式见 [V1 贴纸协议](resources/stickers/v1/README.md)。当前资源执行器不包含动态序列帧贴纸或 3D 挂件。

### 虚拟背景与数字运镜

- 本地人像分割，支持背景虚化、纯色背景与静态图片背景。
- 支持周期推近、水平摇移、人脸跟随和关键帧运镜。
- 场景合成顺序：**背景 → 美颜与贴纸 → 数字运镜**，输出画面尺寸保持不变。
- 分割独立异步运行，支持 CPU、GPU 与 GPU 优先策略；过期或无有效蒙版时恢复原背景。

快速移动、转头、头发细节和遮挡边缘仍需优化与真人验收；实验性蒙版运动校正默认关闭。实现与接入见 [场景效果说明](doc/SDK_SCENE_EFFECTS.md)。

### 手势识别与交互事件

- 官方模型支持握拳、张掌、食指向上、点赞、倒赞、比耶与 I Love You 七种静态分类。
- 提供每只手 21 个二维关键点、左右手分类及模型置信度。
- 实验性规则支持 OK、伸指数量 0～5、单/双手比心、合十、挥手、拍手、画圈、捏合拖动及双手缩放。
- 输出稳定触发及 `START / UPDATE / END / CANCEL` 等事件，供宿主连接动画、交互或业务逻辑。

**SDK 输出识别事件；可选 GestureEffectPlayer / GestureEffectRenderer 已支持绑定透明图集动画，须由宿主显式接入。** 自定义手势是二维几何与时序规则，不是额外训练的模型。公共手势运行时默认关闭；相机 Demo 按体验需求默认开启。接入与事件去重见 [手势文档](doc/SDK_GESTURES.md)。

### 统一资源管理

可选的 `lib_beauty_resources` 将美颜、美型、美妆、调色、贴纸、背景、运镜和套装纳入统一管理：

- 内置资源、本地 ZIP 和显式 HTTPS 下载共用校验与安装入口。
- 支持名称、说明、封面、语言翻译、分类、作用域和能力声明。
- 使用 SHA-256 固定资源内容，支持修订、默认配置、组合选择与用户状态恢复。
- 支持安装、卸载、离线目录缓存、下载进度和取消；使用中的资源受引用保护。
- 包读取校验路径、文件预算、PNG、摘要和能力，准备成功后发布完整配置。

资源下载、安装与持久化不在视频帧处理回调中执行。核心美颜库不声明联网权限；网络资源组件由宿主显式使用。详见 [资源管理模块](core/lib_beauty_resources/README.md)。

## 配套效果工坊

[BeautyStickerWorkshop 效果工坊](https://beautystickerworkshop.pages.dev/) 是独立 Web 项目，面向素材制作、参数配置和资源交付。图片与方案在浏览器本地处理，不采集摄像头、不上传人像素材；SDK 仓库保留资源协议、Android 加载器和消费示例。

### 制作、搭配与交付

| 工坊能力 | 用途 |
| --- | --- |
| 八分类工作区 | 选择美颜、美型、美妆、调色、贴纸、背景、运镜与套装，调整当前搭配 |
| 贴纸编辑 | 导入透明 PNG，配置图层、锚点与布局，制作人脸挂件 |
| 图片睫毛编辑 | 导入 PNG，编辑九个根部绑定点，设置长度、强度与右眼镜像 |
| 背景与运镜编辑 | 制作背景资源、运镜参数及关键帧时间线 |
| 资源信息编辑 | 配置稳定 ID、修订号、封面、名称、说明与多语言文案 |
| 方案管理 | 本机草稿保存与恢复、最近操作撤销/重做、资源包重新打开编辑 |
| 导出交付 | 单项 ZIP、搭配套装、默认 JSON，以及包含目录、封面与资源包的交付 ZIP |

工坊提供内置制作样例和专用编辑器，完整操作说明随独立项目维护：[工坊 README](https://github.com/wukuiqing49/BeautyStickerWorkshop#readme)。网页预演用于检查构图、素材定位与运镜，**不执行 Android SDK 的真实磨皮、美型或人像分割**，最终效果应在手机上验证。

### 从工坊到手机

1. 在工坊选择或制作资源，调整参数与搭配，填写名称、封面和版本信息。
2. 导出单项资源、套装或统一资源库 ZIP；重要方案可导出备份。
3. 在 Android Demo 首页的“资源中心”导入。导入和下载仅安装，不自动启用效果。
4. 点击已安装资源启用；导入资源库后，也可显式应用包内默认配置。
5. 回到相机预览，检查佩戴位置、叠加顺序、运动跟随与处理开销。

需要网络分发时，在工坊填写最终 HTTPS 资源根地址，导出发布包，将资源与封面上传后再发布 `catalog.json`。Demo 中显式配置目录地址，即可下载、安装并使用离线缓存。导出操作本身不会自动上传或部署。

### 手势动画素材

工坊另提供比耶星光、点赞彩纸、开掌光波和爱意漂浮等动画的模拟触发与透明 PNG 图集下载。它们属于**宿主动画素材**，需要宿主播放器与 SDK 手势事件连接，不能直接导入当前 Android 统一资源中心，也不会自动合成进视频。

工坊源码与线上部署独立更新；线上可用功能以实际部署版本为准。Web 修改不会自动同步 Android/iOS 内置资源。

## Android Demo

运行后从首页进入相机或独立资源中心：

- **相机主画面**默认显示效果，MediaPipe + GPUPixel 为固定主线，隐藏基础 OpenGL 后端选择。
- **效果面板**按八分类展示参数与已安装资源，支持单类重置及整体重置。
- **长按查看原图**，松开立即恢复效果；长按期间暂停人脸和手势分析。
- **诊断卡**在原图按钮下方显示人脸、手势、处理 FPS 与合成提交耗时，使用固定宽度避免文字更新撑动边框。
- **更多菜单**提供人脸检测、手势检测、美化开关、调试、回调详情与可选原图小窗。人脸与手势在新相机页面默认开启，可独立关闭。
- **资源中心**独立管理本地导入、HTTPS 目录、下载与删除，打开资源中心不会创建相机。
- **配置记忆**保存资源选择、美颜参数及手动背景/运镜配置；整体重置保留已安装资源。

诊断中的耗时是 CPU 处理/提交统计，FPS 是本地处理新采样帧的统计，不能视作 GPU 执行耗时或直播推流 FPS。静态图片页与旧 CPU 控制器仅作参考。

## 处理链路与接入

```text
宿主相机 / 直播引擎采集帧
    ├─ RGBA 分析小图 → 异步人脸 / 手势 / 人像分割
    └─ OES / 2D 纹理 → 场景与美颜贴纸合成 → 输出纹理 → 宿主消费 / 编码推流
```

- **统一编排**：`BeautyEngineConnector` 负责取帧、处理与写回入口，`BeautyEnginePipeline` 组合 `BeautySession`、分析运行时与渲染器；厂商帧类型留在适配层。
- **异步有界**：分析不阻塞渲染等待推理，使用最新有效快照；普通 Session 按效果需求分析，Demo 可显式开启人脸观察。
- **输入支持**：RGBA8888、OES/2D 纹理；YUV420 提供到 RGBA 分析小图的兼容转换，不包含 YUV 输出管线。
- **纯纹理分析**：可选 GPU 缩小与双 PBO/fence 后续轮询桥接，读回检测小图；纹理不会隐式成为零拷贝模型输入。
- **资源所有权**：GL 创建、处理、释放须满足同线程及 EGL 上下文约束；默认输出纹理只在下一次处理或释放前有效。异步消费者需显式复制/租用并处理同步。
- **坐标契约**：旋转、镜像、裁剪、时间戳及跨尺寸映射均需与宿主实际输入一致；换源必须重置分析状态。

接口、调用示例和生命周期详见 [核心 SDK 文档](core/lib_beauty/README.md) 与 [处理链路说明](doc/SDK_PIPELINE.md)。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `core/lib_beauty` | 公共帧协议、Session、分析调度、引擎编排、基础渲染、场景与资源包协议 |
| `core/lib_beauty_gpupixel` | GPUPixel Shader/LUT 的 C++/JNI 纹理适配，美型、美妆与调色 |
| `core/lib_beauty_mediapipe` | 本地人脸关键点、人像分割和手势分析后端 |
| `core/lib_beauty_resources` | 资源安装、目录、组合、默认配置与状态管理 |
| `core/lib_camera` | CameraX 采集、前后摄与 RGBA 输出，供 Demo 验证 |
| `feature/feature_app` | 相机 GPU 预览、采集到 SDK 的适配与静态图参考编排 |
| `app` | Demo 页面、效果面板、工坊入口和资源管理宿主 |
| `core/core_base`、`core/core_utils`、`feature/feature_res` | 宿主基础包装与共享资源 |
| `ios/BeautyResources` | Swift/Metal 资源消费源码，尚未完成 Apple 环境编译与设备验收 |

核心 SDK 与相机、WKQ 宿主框架及直播厂商解耦；可选后端依赖共享库，共享库不反向依赖具体算法后端。

## 构建与验证

环境：Android API 24+、OpenGL ES 3；JDK 17。compileSdk/targetSdk 当前为 36，Gradle、NDK 和 CMake 版本以工程配置为准。首次构建需配置 Android SDK 并解析依赖。

```powershell
# 检查本地模型
python scripts/verify_models.py

# 核心单元测试、SDK AAR 与 Demo APK
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest
.\gradlew.bat :core:lib_beauty:assembleRelease :core:lib_beauty_gpupixel:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :core:lib_beauty_resources:assembleRelease :app:assembleDebug
.\gradlew.bat :app:lintDebug

# 仅用于本地无正式签名的 Release/R8 验证
.\gradlew.bat :app:assembleRelease -PrequireReleaseSigning=false
```

AAR 输出在各库的 `build/outputs/aar/`，Demo APK 在 `app/build/outputs/apk/debug/app-debug.apk`。单独复制一个 AAR 不会自动携带传递依赖；接入时须包含所选后端、共享库及模型。当前未提供远端 Maven 发布坐标。

## 当前边界

- ZEGO/声网是目标适配方向，尚未固定具体版本、实现厂商帧接入或完成远端推流验收。
- 美型、美妆、跟踪及分割已有实现和夹具验证，不能等同真人动态画质、持续温升和性能已通过。
- iOS 已有资源解析与 Metal 消费源码，能力不与 Android 完全对齐；解析兼容不代表相应效果已渲染支持。
- 默认本地处理人脸图像与模型，不自动保存或上传摄像头帧；资源联网由宿主显式开启。
- 第三方源码与模型的来源、许可和再分发要求分别见模块说明及 [模型记录](doc/third-party/MODELS.md)，商业发布前应按实际使用内容核验。

## 文档导航

| 主题 | 文档 |
| --- | --- |
| 核心 API、帧协议与引擎入口 | [lib_beauty](core/lib_beauty/README.md) |
| MediaPipe 后端 | [lib_beauty_mediapipe](core/lib_beauty_mediapipe/README.md) |
| GPUPixel 算法适配 | [lib_beauty_gpupixel](core/lib_beauty_gpupixel/README.md) |
| 资源安装与组合 | [lib_beauty_resources](core/lib_beauty_resources/README.md) |
| 统一资源制作与交付 | [统一资源协议 V1](resources/unified/v1/README.md) |
| 贴纸 / 图片睫毛 / 完整主题 | [贴纸](resources/stickers/v1/README.md) · [睫毛](resources/effects/v4/README.md) · [主题](resources/themes/v1/README.md) |
| 虚拟背景与运镜 | [SDK_SCENE_EFFECTS](doc/SDK_SCENE_EFFECTS.md) |
| 手势事件与宿主接入 | [SDK_GESTURES](doc/SDK_GESTURES.md) |
| 构建与设备证据 | [TEST_REPORT](doc/testing/TEST_REPORT.md) |

资源的统一版本、离线/网络交付与回滚见[资源发布说明](doc/RESOURCE_RELEASE.md)。个人CMS新增本机版本备份，无服务端账号；直播引擎按当前要求暂不接入。
# 统一 Maven 接入

客户只需声明 `implementation("com.beautysdk:beautysdk:0.2.0")`，自动解析核心、资源、MediaPipe、GPUPixel四个同版本模块。当前发布位置为本地`build/repo`，尚未上传远程仓库；配置方法见[Android SDK 使用说明](doc/ANDROID_SDK_USAGE.md)。发行构建使用`gradlew.bat publishBeautySdkToLocalBuildRepository`。

### 0.2.0 商业授权

Release 接入须初始化授权；服务默认每天刷新签名租约，有效缓存最长支持 7 天离线，视频线程不联网。发行工具与部署说明见 [商业授权](doc/LICENSING.md)。当前未部署正式授权服务，未配置公钥的 Release 默认拒绝效果。
