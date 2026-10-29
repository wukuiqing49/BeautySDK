# BeautySDK 开发代理入口

## 2026-10-29 单一Maven发行入口

新增core/lib_beauty_sdk，发布坐标com.beautysdk:beautysdk，与四个实现模块共用sdk版本；api传递暴露核心/资源/MediaPipe/GPUPixel，不合并AAR，不引入Demo/WKQ/相机。根任务publishBeautySdkToLocalBuildRepository发布入口与依赖，候选包包含五个制品及元数据。独立示例仅声明入口；发布、三类classpath同版校验、Debug/Release-R8及4项发布契约回归通过。当前本地仓库，未远程发布；Pixel8本轮离线，未复跑设备。详见最新测试报告。

## 项目事实

- 本项目是 Android 美颜与人脸挂件 SDK，面向直播引擎视频帧处理；Zego、声网是目标适配参考，尚未选定具体 SDK 版本或完成适配。
- 已按用户脚手架创建 `app`、`core/core_base`、`core/core_utils`、`feature/feature_app`、`feature/feature_res` 和 `core/lib_beauty`，生命周期为 `initialized / multi-backend-prototype`。已实现共享 BeautySession、有界异步分析、CPU/ES3 基础美颜和程序化眼镜/胡子，MediaPipe 人脸关键点后端；引擎适配和持续直播性能未验证。
- 数据流：直播引擎提供采集帧 → 人脸定位与图像处理 → 将合成帧交回引擎 → 引擎编码推流。SDK 默认不接管摄像头、音频、编码或推流。
- 已使用本地 MediaPipe Face Landmarker 提供单人脸关键点，OpenGL ES 3 实现基础美颜与程序化眼镜、胡子合成；实际引擎接入、持续性能和模型商业再分发许可仍需验证。
- 当前事实见 `.agents/config/project-profile.yml`；候选模块规划见 `.agents/config/project-scaffold.yml`。不存在的模块、构建和测试不能描述为已经完成。

## 加载与路由

- 已新增 `core/lib_camera`（`:core:lib_camera`），用于后续自定义相机测试；已基于 CameraX 1.4.2 实现预览、前后摄、独立 RGBA 帧和生命周期控制；宿主相机页已改用 OES/ES3 GPU 合成实现，保留独立低分辨率 RGBA 检测；新 GPU 链路尚未通过真机验收，非生产引擎管线。

所有开发任务读取 `.agents/rules/execution.md`。视频帧、检测、渲染或引擎接入读取 `.agents/rules/beauty-sdk.md`。

| 任务 | 入口 |
| --- | --- |
| SDK 方案、帧处理、MediaPipe、OpenGL、引擎适配 | `.agents/workflows/beauty-sdk.md` |
| 创建 Android SDK 工程 | `.agents/workflows/create-project.md` |
| 公共 API、模块和依赖边界 | `.agents/workflows/change-architecture.md` |
| Gradle、AAR、依赖、R8、ABI | `.agents/workflows/change-build.md` |
| Bug 修复 | `.agents/workflows/fix-bug.md` |
| Demo 页面、文案与设计还原 | `.agents/workflows/implement-page.md`、`.agents/workflows/localize-content.md`；设计工具按 `.agents/INDEX.md` |
| 功能和性能验证 | `$android-functional-test-workflow`，结合 SDK 规则中的测试矩阵 |

## 开发边界

### 当前固定测试主线

- 用户最新要求（2026-10-27）隐藏Demo的基础OpenGL选项：相机Demo默认使用MediaPipe Face Landmarker + GPUPixel，直接展示美型/美妆参数。基础OpenGL保留为SDK内部对照，早先基础OpenGL默认约定由本要求替代。
- 当前使用 MediaPipe + GPUPixel；RFB 模块、模型及 ONNX Runtime 已按用户要求移除。基础 OpenGL 仅保留 SDK 内部对照。
- 静态图片页和旧 CPU 相机控制器仅作参考；其耗时不能代表实时 OpenGL 主线。
- 相机已通过共享 BeautyAnalysisRuntime 与 BeautySession 收敛分析和合成，下一步验收统一链路的持续性能与坐标/生命周期矩阵；libyuv 接入及直播引擎适配尚未实现。
- 贴纸开启时按需检测；无贴纸/原图模式跳过 SDK 分析。默认后端不等于默认持续推理。


- 新增 `core/lib_beauty_gpupixel`：固定 GPUPixel 提交的 Shader/LUT 源码适配，C++/JNI 在宿主当前 ES3 EGL 上下文执行磨皮美白、调色锐化，并复用 MediaPipe 478 点进行实验性瘦脸大眼及原贴纸；不是完整上游运行时，不包含其检测器。相机Demo隐藏后端切换并默认GPUPixel；Pixel8夹具/页面回归通过，但真人动态观感和持续性能未验收，详见模块README和测试报告。

- 核心 SDK 与直播厂商解耦；厂商类型封装在适配层，Demo 仅作为集成与验证宿主。
- 检测支路异步、有界、不积压；渲染使用最近有效且未过期的关键点。不得在引擎关键回调上等待推理。
- 定义旋转、镜像、裁剪、坐标转换、时间戳，以及图像/纹理所有权与线程要求。GPU 推理不等于零拷贝。
- 默认本地处理人脸图像和模型，不自动上传、保存人脸帧，不引入账号、支付、广告、统计或联网鉴权。
- 不沿用其他项目的包名、发布身份、授权记录、已实现清单或设备限制；宿主按用户技能使用 WKQ 基础框架，美颜库保持独立。仅维护代理文档不等于要求创建 Android 工程。
- UI、Figma、Zeplin、国际化和商店素材能力仅按 Demo/宿主的实际任务加载，不作为 SDK 核心的依赖或默认交付物。

## 验证

当前可执行（构建与功能证据见 `doc/testing/TEST_REPORT.md`）：

```powershell
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B .agents/scripts/validate_android_workflows.py --project-root . --config-only
.\gradlew.bat :core:lib_beauty:assembleRelease :core:lib_beauty:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

工程由现有脚手架生成器在临时目录生成后合并，扩展 :core:lib_beauty。app 使用 WKQ 基类与工具初始化，库不依赖宿主基础框架。默认宿主身份 com.beautysdk.demo，SDK namespace com.beautysdk；发布前确认归属。Release 默认要求正式签名，本地未签名 R8 验证显式传 -PrequireReleaseSigning=false。设备测试按当前任务范围执行，静态检查不代表视觉或实时性能通过。

- 新增 `core/lib_beauty_mediapipe`：本地 Face Landmarker 后端；MediaPipe 后端 api 依赖 lib_beauty，共享库不依赖算法后端。静态图演示可选择基础美颜/MediaPipe，静态图验证不等于直播测试。

## 统一SDK输入进展（2026-10-26）

- 实时CameraX Demo和BeautySession共用AsyncFaceAnalysis，模型由SDK worker管理；相机回调仅提交帧，不再维护Demo专用检测器/结果历史。
- 统一输入为RGBA8888及OES/2D纹理。RGBA异步边界使用复用DirectByteBuffer批量复制，MediaPipe worker调用C++融合转换，无逐帧Bitmap。纹理不隐式读回。
- BeautySession显式allowScaledAnalysis允许同视场小检测图搭配高清输出；方向、镜像、时间线与crop契约必须正确。公共Session和实时Demo默认150ms有效期。
- 2026-10-26本轮49项JVM与Pixel 8上6项设备测试通过，详见固定测试报告。ZEGO/声网实际适配、I420/NV21支持、持续性能与贴纸闪烁完整验收未完成。

- 2026-10-26 新增公共 AnalysisFrameMapping/FrameGeometry/FrameRegion 与 BeautySession.setAnalysisMapping：支持显式跨尺寸、裁剪/留边及旋转镜像坐标转换。配置变更保守清除旧结果，不伪造采样时间；同一结果映射缓存。仅变换定位数据，宿主仍须提供与声明一致的图像及小尺寸分析帧；实际厂商适配、高分辨率纹理分析读回和跨分辨率美颜强度一致性未完成。

- 2026-10-26 新增实验性TrackedFaceAnalysis/FaceMotionEstimator，BeautySession可选接入；NativeFaceMotion以C++小灰度块匹配补充帧间平移/缩放/平面旋转。模型校正年龄300ms、跟踪帧150ms分别限制，保留真实检测时间；无脸/错误/重置失效。相机调试页默认勾选实验跟踪，可关闭对照。持续真人闪烁、快速侧脸和遮挡表现未验收，不得描述为已经解决；不是成熟光流或三维跟踪实现。

- 已新增SDK v1静态PNG资源包读取/导出与布局参数：`StickerResourcePack`，1～3层，固定JSON字段与有界ZIP/PNG校验。相机贴纸页新增素材工坊（导入、逐层定位、JSON/ZIP导出），6个内置素材已生成标准包；制作规范在 `doc/stickers/README.md`。该功能不代表动态贴纸或直播引擎接入完成，实际验证见测试报告。

## 2026-10-26 公共SDK收敛

- 相机GPU主线通过BeautySession借用BeautyAnalysisRuntime合成，采集线程只向公共运行时提交RGBA；不再在Demo侧直接选择人脸并调用效果渲染器。
- 普通Session按效果需求启停分析；借用运行时的宿主负责需求、跟踪开关、换源reset和最终释放。redraw用于同一采样帧调参，不更改时间戳。
- 当前内置素材按选择加载，公共诊断使用固定容量样本。工坊私有原子草稿仅保存素材，恢复不自动启用效果。
- 本轮不接直播厂商；持续性能、真人视觉、远端推流、libyuv与YUV适配仍未完成。详见doc/SDK_PIPELINE.md和最新测试报告。

## 公共引擎接入增量（2026-10-26）

- 新增Yuv420Frame/YuvPlane及Yuv420RgbaConverter，支持显式601/709量化范围、padding/UV交错，复用RGBA分析小图；Kotlin兼容路径，非libyuv，不包含YUV输出或高清实时验收。
- 新增GlTextureOutputPool，有限RGBA 2D副本槽位及幂等租约归还，池满不等待。仅同创建ES3上下文执行copy/close；跨context生产/消费同步、OES规范化、context丢失恢复仍由适配层负责，未接厂商。

- 新增可选GlTextureAnalysisBridge，原始2D/OES纹理经GPU缩小及双PBO/fence后续帧轮询，向已有BeautyAnalysisRuntime投递检测小图；无Bitmap、无主动等待、按需限频。相机Demo仍用独立RGBA，不启用此桥接。纹理输入设备夹具不等于厂商/持续性能验收。

- 新增BeautyEngineConnector统一同步取帧/标准化/处理/写回入口和BeautyEnginePipeline公共编排；厂商只实现read/write/passthrough。写回尝试后不自动重复提交，资源/线程错误明确拒绝。未引入厂商依赖或实现其帧类型，未通过远端推流验收。

- 相机Demo已复用BeautyEngineConnector/BeautyEnginePipeline统一帧入口；共享独立RGBA分析运行时由宿主管理，GL同帧调参使用onRedraw，不额外读取相机纹理。具体直播厂商适配仍未实现。

- 相机贴纸会在宿主私有偏好保存内置款式/开关；工坊私有草稿恢复上次启用状态，清除可取消在途恢复。SDK核心不保存宿主UI选择，不存摄像头图像。

- 工坊导出分区提供“保存并加载”：IO线程原子保存完整ZIP后重新走StickerResourcePack.loadZip，成功才替换预览；busy期间不将旧快照flush覆盖，清除可取消在途启用。

- 快速运动处理现状：Pipeline保留可选FaceMotionEstimator；用户报告真人效果退化后，已撤回新增两级粗搜及连续增益，恢复原单尺度匹配及原平滑。结果有效期不延长，不添加渲染线程等待。实际快速转头和持续性能需真机验收。

- 新增可选BeautySceneRenderer：原始蒙版背景合成→delegate美颜贴纸→数字运镜；MediaPipeForegroundMasks提供本地SelfieSegmenter单在途异步分割，支持RGBA小图和可选双PBO纯纹理桥。背景/运镜默认关闭；纯纹理分割不是零拷贝，真人边缘画质、持续性能及实际引擎远端待验证。见doc/SDK_SCENE_EFFECTS.md。

- 新增完整主题容器 `BeautyThemePack`（bundleVersion=1）：复用V1贴纸/V2效果子ZIP，整体预算预检、能力检查、一次BeautySettings替换；Demo效果导入口支持主题与AtomicFile恢复。协议见resources/themes/v1，Swift/Metal主题消费代码尚待Xcode验证，设备验收范围见doc/testing/TEST_REPORT.md。

## 2026-10-27 资源与 Demo 入口补充

- Demo 单一底部面板按美颜/美型/美妆/调色/贴纸/背景/运镜分区；工坊正常入口改为资源中心，高级布局/导出显式展开。
- BeautyResourceStore 为可选显式网络资源组件；核心不声明联网权限。主视频处理路径不联网。Demo 支持内置完整主题、本地包、HTTPS 目录和摘要缓存。
- V1 贴纸、V2 背景/运镜/旧美颜继续兼容，V3 仅扩展 BEAUTY。完整格式见 resources/effects/v3/README.md。iOS Metal 不支持新美型/美妆，不能把解析兼容描述为渲染已实现。
- BeautyEnginePipeline.performance 只统计 CPU 提交与新采样处理 FPS，不能当作 GPU 时间或推流 FPS。最终证据见 doc/testing/TEST_REPORT.md；线上目录本轮未发布。


## 2026-10-27增量事实

图片睫毛使用BEAUTY V4透明PNG+9点根部配置，Android GPUPixel复用贴妆FBO/网格，Web独立工程新增eyes.html及主题导出，三款内置样例。Pixel8相关13项、核心85项/GPUPixel4项JVM、Web18项通过。iOS新增同包解析与Metal源码，当前Windows未编译运行；Pages未发布；真实引擎和持续性能仍未验证。Demo按用户最新要求隐藏基础OpenGL选择、默认GPUPixel，旧默认描述仅历史记录。


## 统一资源管理（2026-10-27）

- 新增core/lib_beauty_resources，独立资源安装/目录/默认/状态/组合，api依赖lib_beauty。
- 实验室ResourceCenterController/Model调用公共库；内置/本地/网络同一校验入口，固定摘要引用，八类与17个内置示例。
- Web资源交付中心位于独立BeautyStickerWorkshop/resource-studio.html；新协议见resources/unified/v1/README.md。iOS消费组件已补源码，Windows未编译。
- 验证与限制以doc/testing/TEST_REPORT.md本轮资源闭环记录为准；不代表线上已发布、厂商已接入或持续性能通过。

## 2026-10-27 手势分析增量

- 新增公共GestureAnalysisRuntime/GestureRuleEngine和MediaPipeGestureAnalyzer：独立单在途worker、默认关闭、按需开启，与人脸需求无关；复用tasks-vision 0.10.32和现有原生RGB转换，不引入ONNX。模型gesture_recognizer.task为8,373,440字节，摘要见scripts/verify_models.py。
- 官方7种静态分类及21个二维手部关键点；实验性OK、伸指数量0～5、单/双手比心、合十、挥手/拍手/画圈、捏合拖动及双手缩放。自定义均为二维/时序规则，不得描述为已训练的模型或真人准确率已验收。
- 只提供观测、稳定触发及START/UPDATE/END/CANCEL事件；用户明确动画由宿主接入，不自动播放动画、切换主题或写入视频。Demo增加可见开关和诊断，原图模式跳过手势分析。契约见doc/SDK_GESTURES.md，验证范围见固定测试报告。

## 2026-10-27 Demo与资源入口修复

- 首页资源中心改为独立ResourceCenterActivity，不创建相机；显式HTTPS目录地址配置、导入/下载/删除在独立页面。导入/下载仅安装，已安装卡片点击才启用。
- 相机效果默认主画面；底部按八分类展示参数和已安装效果，移除管理按钮，背景/运镜使用同一ResourceCenterModel最终快照。串行配置更新不能因后续输入丢掉不同字段；返回资源页只失效变化分类的手动场景覆盖。
- MediaPipeForegroundMasks默认严格尺寸契约，新增显式allowScaledFrames：仅宿主保证同视场、等比例时映射高清输出；保留采样时间、250ms有效期并缓存映射。CameraX同ViewPort Demo显式开启。真人分割边缘和持续性能仍待验收。
- 场景手动调整是ViewModel会话状态，资源选择与美颜参数按现有文件保存；设备/UI证据以最新TEST_REPORT为准。

## 2026-10-28 Demo配置记忆补齐

宿主LabSceneStore持久化手动背景/运镜覆盖（含图片、焦点、虚化、倍率、周期与关键帧），绑定类别/主题资源选择；资源替换、重置及显式关闭同步更新。ResourceCenterModel共用进程级串行IO队列，退出完成已接受保存后再释放，新Model排队读取。原公共参数/资源状态仍由BeautyResourceStateStore负责，不改资源包协议或SDK渲染。Pixel 8相关14项通过；未验收强杀中断保存/磁盘故障、真人持续性能或真实直播引擎。详见最新TEST_REPORT。

## 2026-10-28 动态虚拟背景优化

MediaPipeForegroundMasks改为一个处理槽＋一个可替换最新待处理槽，Direct RGBA复用现有C++融合缩放/转正/RGB行对齐，RGB兼容入口复用缓冲。新增CPU/GPU/GPU_PREFERRED；公共默认CPU，Demo优先GPU且仅初始化失败回退CPU。Debug限频输出分割阶段耗时和即时蒙版年龄，不保存人像帧。保持真实时间戳、250ms过期和代次失效；没有蒙版运动预测或视频延迟补偿。Pixel8最终18项设备测试通过，独立1项CPU/GPU公开夹具短测通过，核心117/宿主13项JVM零失败；持续性能、真人快速移动轮廓与厂商推流未验收。证据见固定测试报告。

## 2026-10-28 C++蒙版后处理

NativeMaskConverter已替换分割输出Kotlin逐像素循环：借用FLOAT32原生缓冲，在C++完成量化与逆旋转/镜像，复用DirectByteBuffer。ForegroundMask新增ByteBuffer批量复制构造，发布不可变结果，避免复用覆盖跨线程渲染；不是零拷贝。新增细分耗时。Pixel8相关21项设备功能＋1项运动测量通过、核心118/宿主13项JVM通过；公开横移短测后处理44.72→4.46ms，中位蒙版年龄137.44→66.67ms，但P95仍162ms且有切边，未通过真人动态画质验收。Release/R8为显式无正式签名构建，具体native/16KB检查见测试报告。

## 2026-10-28 轮廓与运动对照事实

BeautySceneRenderer改为连续软置信度过渡，同尺寸R8纹理复用更新。新增实验性NativeMaskMotion整体平移校正及ForegroundMask alignmentTimestampNs/offsetX/offsetY，GL按p-offset采样；保持真实模型采样年龄及250ms有效期，早于校正时刻的视频帧回到同模型原始蒙版，避免提前应用位移。公开横移对照收益不稳定且有额外成本，因此SDK与Demo最终都默认关闭enableMotionAlignment。不是局部非刚性跟踪或发丝matting，快速转头问题仍未解决；不得将26项相关设备功能、2项测量执行通过描述为动态视觉通过。实际静态GL夹具图及实验失败/波动记录见TEST_REPORT。


## 2026-10-28 Demo默认检测交互更新

用户最新要求：相机Demo在人脸/手势均无特效时也默认开启检测观察，开关统一放在更多菜单，支持独立关闭；配置重建保留当前页选择。仅Demo显式请求，公共SDK手势默认关闭及按需人脸分析契约不变。长按原图仍暂停分析。诊断卡改固定宽度和固定行数，Pixel8两项交互/布局回归通过，不代表持续双检测性能验收。


## 2026-10-28 可选手势效果绑定

新增 GestureEffectArchive/GestureEffectPlayer/GestureEffectRenderer，独立 gestures.json + PNG 包，支持7种模型手势绑定一次透明图集动画或定点PNG。显式应用和按需分析后，在美颜/场景输出纹理上合成；只消费TRIGGER，播放中忽略、800ms冷却、结束消失。22种支持示意仍为只读目录，实验规则未接首版效果。Demo资源中心导入仅暂存，明确应用后替换并持久化；主线相机已接入，换源/原图/停用清理。最新验证见固定测试报告；不代表真人准确率、持续性能或厂商远端推流通过。早期“仅事件、宿主自行播放”仅描述分析模块，现可选使用公共播放器，默认SDK仍不自动播放。

## 2026-10-28 分类资源库与JSON展示配置

Web完整资源库按分类/名称__ID导出，新增library.json定义分类、资源展示、15项参数范围/步长/默认程度及中英文。Android BeautyResourceLibrary公共读取/校验/参数合并，Demo导入保存与返回刷新，分类/卡片/调参由清单驱动；旧平铺ZIP兼容，安装不自动启用默认搭配。Web49、资源JVM8及Pixel8相关13个不同设备用例通过，构建/lint和门禁通过。协议见resources/unified/library-v1/README.md；iOS未新增该完整清单消费、线上未发布、新控件横屏/字体缩放与持续真人性能未验收。证据见固定测试报告。

## 2026-10-28 手势动画纳入完整资源库

libraryVersion=2新增第9类GESTURE和gestureResources，完整本地ZIP含50份常规资源+4组实际透明图集动画，带预览、中英名称、触发规则及默认选择；已保存的自定义绑定随方案交付。Android公共资源管理器验证/安装/组合手势素材，Demo资源页独立第9类选择、互斥、删除、记忆及显式默认应用，旧独立手势包兼容。常规8类枚举/BeautySettings不变，catalog网络协议仅覆盖常规资源。Web53项、资源JVM8项、Pixel8相关18项通过，构建/lint/UI/i18n/架构门禁通过。未发布网页；iOS完整库、真人观感/准确率与持续性能未验收。协议及证据见resources/unified/library-v1/README.md和固定TEST_REPORT。


## 2026-10-28 完整交付目录规范

新完整ZIP统一beauty_resources/外层，内部英文分类/稳定ID，JSON名称与多语言不参与文件路径。Web/Android归一化新外层并保留旧中文分类/平铺根包，拒绝混合根；Demo识别新旧catalog入口。Web55、资源JVM8、Pixel8相关12项通过，构建/lint/架构门禁通过；详见固定测试报告。网络baseURL须对应外层目录内部内容，不代表网站已发布或iOS已支持。


## 2026-10-28 Android assets资源加载

BeautyResourceManager新增installDeliveryFromAssets（完整ZIP）与installDeliveryDirectoryFromAssets（解压目录），复用完整校验/安装回滚，不自动应用或联网。目录有界收集，默认beauty_resources。开发接入见doc/ANDROID_SDK_USAGE.md及首页链接。资源JVM8、Pixel8相关14项及构建/lint/架构门禁通过；厂商/真人/性能仍待验收。


## 2026-10-29 Demo默认资源对接修复

主APK内置最新完整ZIP（50常规+4手势）及library.json，启动通过公共assets API安装一次；默认效果控件读取内置JSON，不再要求手动导入。用户导入清单优先，参数/选择/旧素材删除保留；相机异步清单更新同步切换控件模式。Pixel8相关13项通过，含不导入的真实相机参数生效；构建/lint/UI/架构通过。证据见固定测试报告，不代表真人/持续性能通过。
## 2026-10-29 效果面板交互

用户最新要求：相机效果弹框默认展示预设，切换分类与关闭重开回到预设；微调保留单项参数强度调整。两种模式固定同一高度，参数状态更新复用滑杆，避免主题展开时重建。Pixel 8 本轮 8 项相关设备回归通过，逐帧位置/尺寸断言通过；不代表横屏字体完整矩阵、真人视觉或持续性能通过，详见测试报告。

## 2026-10-29 SDK/CMS收敛

用户明确CMS仅个人使用，直播引擎暂不接入。参数契约由CMS parameter-contract.json生成双方定义；Web/Android共享清单夹具。目录V2包含普通和手势，安装不启用；V1继续兼容。发布脚本生成不可变快照并同步APK三份内容与marker，preBuild检查一致；本地生成不等于网站部署。Demo弹框窗口职责抽到EffectPanelHost，分类排序以已应用defaults为准。个人版本页保留最多5个版本/64MiB，无账号或服务端。验证与短测限制以最新TEST_REPORT为准，不宣称真人质量/长时性能/iOS已完成。
## 2026-10-29 商业候选版源码增量（未运行）

用户要求先处理源码、不运行。四个SDK模块新增统一本地Maven发布配置，samples/standalone为不依赖Demo/WKQ的独立消费者验证样例（有意使用平台Activity验证SDK依赖边界，既有宿主仍遵循WKQ约定）。新增候选打包脚本、可选SignedResourceDelivery发行方验签入口、CMS来源凭据记录及长测有效人脸覆盖率门槛。未执行新增构建/测试/签名/打包/真机；不能沿用旧通过基线。资源签名不是客户付费授权，不提供可信时间或防回滚。范围及剩余项以doc/COMMERCIAL_READINESS.md为准；直播引擎仍不接入，CMS仍为个人使用。

## 2026-10-29 真机回归与相机手势入口

用户随后授权真机测试及修复。资源控制器在宿主赋值后显式start；相机效果面板新增第9类手势入口，选择使用既有播放器与独立检测开关。Pixel8最终22项设备回归通过，覆盖相机卡片/参数同步、资源管理恢复、发行签名拒绝篡改及真实ES3动画换帧/过期/旋转镜像。测试逐项唤醒避免相机退出后锁屏影响，保留原断言。结果见最新TEST_REPORT；合成TRIGGER动画测试不等于真人识别准确率或持续性能验收，商业候选打包与正式网络发行源也未由此通过。


## 2026-10-29 授权逻辑（0.2.0）

用户明确授权实现联网商业鉴权，覆盖此前不引入联网鉴权的默认约定。新增核心 BeautyLicensing 与统一入口 BeautySdk.initialize；Release 默认拒绝，源码 Debug 为开发模式。RSA 签名文件绑定包名/当前 APK 签名/主版本/功能，默认 24 小时刷新、最长 7 天租约，缓存与退避持久化、请求合并；视频线程只检查内存，不联网。Node 个人服务和发行 CLI 位于 services/license，CMS 不持有私钥，无账号支付/设备配额。发行构建使用 beautyLicenseConfig，商业打包拒绝空/不匹配公钥配置。当前未配置生产密钥或部署服务，本地 Release 默认关闭效果；Debug GPU 7 项通过不代表 Release 正向激活通过。完整 JVM/Node/R8 证据见最新 TEST_REPORT，协议与部署见 doc/LICENSING.md。直播厂商仍不接入。

## 2026-10-29 限定试用收敛

当前能力与限制统一见doc/PRODUCT_STATUS.md；主推基础美颜/调色/静态贴纸，其他能力保留实验体验。resources/commercial/trial-release.json登记待关闭资格，候选打包检查范围与JVM签名基线。首建基线不代表历史版本兼容已验收，javap检查不覆盖Kotlin元数据/native ABI/行为。独立示例补资源准备与调参源码；CMS手机试用和Demo本地完整包导入提供SHA256核对，不代表当前参数仍为默认或真人质量通过。真人调校、多设备长测、外部开发者试用与正式发行待执行，结果以TEST_REPORT为准。直播厂商继续不接入。


## 2026-10-29 官网与登录管理系统

用户授权将独立 BeautyStickerWorkshop 改为官网＋个人管理系统并要求管理登录。新增服务端会话/CSRF/限频、官网草稿与发布、完整ZIP归档/发布下载/删除保护和审计。Node本地SQLite/文件与Pages Worker的D1/R2共用处理代码；原编辑器草稿仍在浏览器。验证与部署配置见独立项目ADMIN_DEPLOYMENT.md及.site-audit/validation.md；未部署线上、未创建远端存储、未改Android资源协议，不代表多人协作、实时草稿云同步或商用验收。


## 2026-10-29 CMS 授权签发与下发

用户新增要求授权生成和下发，独立BeautyStickerWorkshop后台新增/admin#licenses、服务端RSA签发、SQLite/D1合同登记、一次性领取链接、续期/撤销/恢复，/v1/lease读取同一登记。私钥仅在服务端运行时，浏览器不持有；此前“CMS不持有私钥”限于旧静态CMS。本地RSA3072签发器已初始化，未部署正式HTTPS、未写入SDK受信配置；原CLI JSON与CMS数据库不同源不自动同步。Web94项、最终后台/授权10项与workerd集成1项通过，SDK原Node9项通过且签名逐字节兼容；浏览器流程与360px验证通过。本轮不改Android运行时，不代表客户真机激活或商业画质验收，详见独立项目ADMIN_DEPLOYMENT.md与.site-audit/licensing-validation.md。


## 2026-10-29 CMS生产初始化

用户授权后已完成beautystickerworkshop.pages.dev线上后台初始化：独立D1/R2、0001/0002迁移、管理员与签发Secret、HTTPS端点及实际绑定配置。原账号登录、签发器自检、公钥配置下载、CSRF/退出及实际浏览器验证通过。未创建客户记录，未改SDK信任配置或执行客户APK激活，密钥仍为本机初始化密钥；CMS上线不代表SDK商业资格通过。最新事实与证据见doc/LICENSING.md和相邻CMS的production-verification.json。
