# BeautySDK 开发代理入口

## 2026-10-30 唇部局部运动补偿

NativeFaceMotion新增显式enableLipTracking（默认false、保留无参构造），整脸160灰度相似变换保持原算法；开启时附加最长边320灰度，20个口红网格点在整脸预测附近±4像素局部匹配，弱纹理/搜索边界/前后向不一致拒绝。至少14点有效才插值缺失点，三角网格翻折或面积比异常整组拒绝，失败保持原整脸结果。仅模型参考到真实新帧的图像匹配，不按时钟外推，不延长150/300ms边界，无新增模型或图像落盘。MotionImage仅开放继承用于后端私有细节图，构造不变。Demo显式开启，但仍尊重现有实验跟踪开关及3D互斥；公共SDK默认不启用。不是唇部分割、三维侧脸跟踪或真人动态画质验收，实测状态见最新TEST_REPORT。

## 2026-10-30 美妆动态跟随延迟优化

Demo局部美妆（口红/腮红/睫毛）与3D自动优先GPU人脸检测，自动GPU仅初始化失败回退CPU；公共MediaPipe默认CPU不变。只在需求/执行偏好跨状态时重置，正数强度变化和原图对比不反复切换执行偏好。GlTextureAnalysisBridge新增显式同GL线程poll，仅轮询已完成fence，不重新采样；Demo按实际采集帧尝试采样（intervalNs=0，避免略短于33.33ms的输入误降为隔帧），每新视频帧排一次8ms提前轮询，不额外绘制高清画面，换源/停止/context变化撤销。TrackedFaceAnalysis跟踪支路改为一执行槽＋一可替换最新待处理槽，不加深队列、不改真实时间戳或150ms/300ms有效期。仍是整脸二维相似变换，未实现嘴唇局部非刚性/三维姿态跟踪；不把短测加速或固定图案回归描述为真人快速转头质量通过。证据以最新TEST_REPORT为准。

## 2026-10-30 贴纸再次点击取消

用户最新交互要求：相机2D贴纸卡片首次点击启用，再点同卡取消该槽位；判断在宿主资源串行IO队列中执行，快速连续两次点击最终关闭。3D六款控件保留RadioGroup互斥/程序化恢复，用户再次点击或无障碍点击已选款式走统一清除入口，同时清除暂停的2D，避免关闭3D后冒出旧贴纸；取消状态持久化。资源管理页显式应用和手势重复点击语义不变。仅Demo交互，无SDK/资源包协议修改；验证以最新TEST_REPORT为准。

## 2026-10-30 高清贴纸坐标输入修复

相机Demo从合成前同一OES纹理/normalize变换，经GlTextureAnalysisBridge生成最长边320的小RGBA供人脸、手势、背景分析；不再消费独立CameraX RGBA。原因是双机实测独立流即使sensor crop/宽高比一致，实际内容尺度仍不一致，OPPO高清约1.33倍。双PBO/fence非等待轮询，换源清空；GL同上下文释放，保留真实采样时间及既有有效期。早先“相机独立RGBA、不启用桥接器”的描述为历史。公共SDK仍由宿主显式选择输入，不意味着零拷贝或持续性能通过。本轮验证状态以最新TEST_REPORT为准。

## 2026-10-30 OPPO分割GPU崩溃兼容

OPPO OP46B1/PCAM00 Android11(API30)的MediaPipe0.10.32 GPU置信度蒙版读取原生SIGABRT，公开夹具已复现。MediaPipeForegroundMasks在该设备GPU_PREFERRED提前选CPU并记录实际后端，显式GPU报不支持；其他设备路径保留。软置信度、250ms和后台生命周期不变。双机各20项模型/三维/场景/实际相机回归、构建/lint通过；本机背景就绪与CPU日志确认。不是上游SO修补或所有机型验收，真人帽子闪烁仍未解决/验收，见最新测试报告。

## 2026-10-30 三维重复帧开销优化

GlSticker3DRenderer新增单条姿态缓存及遮挡顶点上传复用，每帧先验真实150ms年龄；无脸/未来/过期及关闭三维清除，几何和参数变化重新计算。核心146项JVM（1跳过）、双机各10项3D回归和构建/lint通过。上一版GPU优先候选用户确认仍闪烁；本轮减少重复开销但未取得真人改善证据，不得描述闪烁已解决，见最新测试报告。

## 2026-10-30 三维帽子闪烁候选修复

真人复现日志出现有脸但结果年龄160.54ms超过150ms而隐藏；OPPO公开同图短测GPU低于CPU。Demo三维人脸分析改优先GPU，仅初始化失败回退CPU；二维和SDK公共默认不变，150ms及无脸/过期失效契约保留。feature JVM15项、双机各12项设备回归和OPPO执行后端短测通过；用户随后确认新版仍闪烁，候选未通过真人验收；待同步复现定位，持续性能及回退故障注入未验证，不得宣称解决，见最新TEST_REPORT。

## 2026-10-30 配置记忆与手势卡片

用户要求配置哪个下次恢复哪个。相机人脸/手势检测开关新增私有偏好持久化；显式选手势动画开启检测，重复点击已选卡片不再关闭，用“无”显式停用；相机详情不提供删除，资源管理保留确认删除。新增配置恢复真机用例（新Activity/ViewModel、不传Bundle）；Pixel13项整批通过，OPPO最终中断批次前9项通过、剩余4项隔离通过，不能称OPPO整批稳定。保存中强杀、真人识别/帽子消失未验收，见最新TEST_REPORT。

## 2026-10-30 包头三维棒球帽

新增内置 baseballCap 网格与显式头体积遮挡，使用已有 MediaPipe 额头/脸宽估算贴合及独立椭球深度通道；旧模型行为和构造兼容。Demo新增款式并记忆。核心Debug JVM 145项（1项跳过）、3D定向8项；最终OPPO13项/Pixel8十项设备回归通过，构建/lint通过。六视角背面仅合成姿态，不代表完全背对镜头仍可检测；真人佩戴、头发/手部遮挡、持续性能及本轮Release/R8未验收，见最新TEST_REPORT与SDK_3D_STICKERS。

## 2026-10-29 实验性刚性3D贴纸

lib_beauty新增可选FaceGeometry3D/StickerMesh3D/GlSticker3DRenderer，MediaPipe显式output3D保留相对Z与源姿态矩阵；沿用原模型和SO，无新增渲染依赖。首版单人脸、不透明顶点色网格、弱透视头部跟随及轮廓扇形近似深度遮挡，内置眼镜/礼帽。Demo相机贴纸页选择并记忆，开启期间显式暂停二维贴纸/二维美型/二维块匹配，旧参数保留，基础美颜/贴妆/背景/运镜可组合。旧构造/copy/factory JVM签名保留；不等于完整Kotlin/native/行为兼容。GLB/PBR/纹理/骨骼动画、CMS三维资源协议、多人脸、真人快速运动/长时性能、iOS尚未实现或验收；深度不是物理距离。接口与限制见doc/SDK_3D_STICKERS.md，本轮证据以最新TEST_REPORT为准。

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


## 2026-10-30 Demo 效果与资源体验

效果面板保留分类/重开默认预设和预设微调同高，新增手柄展开/拖动关闭；素材用RecyclerView，JSON微调为参数选择＋固定单滑杆，参数回切读取最新值。3D使用时显示普通贴纸/美型暂停并保留选择。独立资源页按已安装/在线两列浏览，详情后显式使用、停用与确认删除；主题替换提示，下载仅安装，更多菜单收纳网络/默认/摘要。手势保留触发说明及互斥选择；无效果同步清理宿主旧贴纸/工坊覆盖。仅Demo展示与交互，不改SDK资源协议或渲染算法；测试范围与失败修复见最新doc/testing/TEST_REPORT.md，说明见doc/DEMO_UI_EXPERIENCE.md。

## 2026-10-30 3D饰品质感

内置眼镜/礼帽重做曲面与装饰，GL上传时生成平滑法线，固定方向主辅光/高光改善体积表现；公共网格API和原模型/SO不变。仍为弱透视顶点色风格化饰品，不是3D美型/PBR/透明镜片，脸部遮挡仍近似扇形。核心143项通过、1项预期跳过，双机各8项通过及构建/lint通过；真人佩戴/持续性能未验收，证据见最新TEST_REPORT。

## 2026-10-30 Demo紧凑布局

效果卡片默认四列/54dp、资源中心三列/80dp，大字体减列；短标题/普通状态不空占行，网格内8dp/行尾12dp、外侧与16dp内容对齐。微调选项固定48dp，默认面板约42%且两模式同高；保留展开与原交互，不改SDK渲染。最终OPPO17次通过，Pixel大字体横屏3/3、竖屏2/3且失败项原断言隔离1/1；竖屏批次弹框启动问题未完全定位，不宣称整批稳定。范围与限制见最新TEST_REPORT。

## 2026-10-30 崩溃风险加固

手势组合逐包使用剩余像素预算，在超限包Bitmap/ARGB解码前拒绝，原loadZip入口保持并新增预算重载。分割诊断/错误回调隔离，关闭finally清理；预览UI观察者及错误回调独立保护，GL释放逐项继续。缩略图LRU最多32项/8MiB，不主动recycle仍由View持有的Bitmap。核心/资源/feature JVM173项（172通过、1既有跳过），Debug构建/lint及配置检查通过；OPPO与Pixel最终各23项整批通过，包含回调故障注入、预算边界、原3D/手势/资源回归，用户文件与偏好恢复。旧测试本地页签与销毁等待前置条件已修正，保留失败证据。不是进程总内存硬上限，底层close故障、长时热稳定性及真人3D闪烁仍未验收。详见最新TEST_REPORT。


## 2026-10-30 SDK纹理内存与GPU诊断

GPUPixel中间RGBA按需分配并复用已消费差分槽，新实例纯美白/调色两张、磨皮四张，完整贴妆可五张；关闭效果不立即清空缓存。背景/3D/美颜派生配置按不可变实例缓存。新增显式GPU计时/纹理字节诊断：四槽EXT查询默认关闭、不等待，宿主活动查询跳过、disjoint丢弃并计数；数据只覆盖该Renderer，不填充为Pipeline全链路GPU时间。两机各29项/24组像素摘要、核心146（1跳过）及GPUPixel4项JVM、Debug/unsigned Release-R8/lint与Native静态对齐通过；短测耗时有升有降含回退，确认内存收益，未证明FPS改善。真人3D闪烁、联合长测/温升与厂商仍待验收，见doc/testing/PERFORMANCE_OPTIMIZATION_20261030.md。


## 2026-10-30 Demo分辨率测试入口

相机更多菜单新增640×480/1280×720/1920×1080采集请求；CameraX实际协商与共同视场crop可能不同，主画面显示请求/实际/分析输入尺寸。分析保留320×240原生请求，再以共同ViewPort裁出同视场，避免OPPO原生16:9分析流最低720p导致放大检测输入。切换显式重绑、失效旧代次，选择保留配置重建，冷启动默认SD；原图暂停时不展示旧分析尺寸。此功能测试真实采集与渲染尺寸，不是固定像素的强制缩放模拟，也不证明跨分辨率画质强度一致。验证见doc/DEMO_RESOLUTION_TEST.md与最新TEST_REPORT。


## 2026-10-30 跨分辨率优化与切换验收

相机更多菜单可请求640×480/1280×720/1920×1080并真实重绑，选择持久化于Demo私有偏好；独立低分辨率分析经共同ViewPort裁剪，主画面显示请求/真实视频/分析尺寸。GPUPixel磨皮按输出短边480归一采样间距，不扩大检测输入；诊断卡可滚动并支持自然换行。双机28次常规＋OPPO横屏/Pixel2.0字体横竖屏3次，共31次设备执行通过，JVM177通过/1跳过，Debug/测试APK/无正式签名Release-R8/lint及四份ELF/两份APK静态16KB对齐通过。用户文件与专项系统设置恢复；未保存相机帧。只证明合成纹理尺度与列明功能，不代表真人肤质/3D闪烁、长时性能、全滤镜强度一致或真实推流验收。当前事实见doc/DEMO_RESOLUTION_TEST.md及TEST_REPORT最新章节。


## 2026-10-31 相邻帧跟踪增量

TrackedFaceAnalysis实验跟踪改为相邻已处理帧接力，最多8张独占灰度图在worker复用；晚到模型经有界重放到当前分析帧再校正，失败不冻结旧成功位置，保留真实模型时间及150/300ms期限。无新增模型/依赖/native或视频延迟缓冲，不是金字塔光流/专用唇部分割，当前视频与异步结果仍可能有时差。核心147通过1跳过；Pixel45/45，OPPO完整44/45（切后摄超时一次），同源码分辨率单独3/3通过；累计92通过1失败，偶发超时和历史MediaPipe GPU SIGBUS根因未关闭。Debug双机安装与私有配置恢复、Release/R8无正式签名及静态对齐通过；真人快速运动/嘴唇贴合、隐藏频率与持续性能未验收，见最新TEST_REPORT。


## 2026-10-31 完整唇轮廓贴合

GPUPixel口红以MediaPipe完整外20/内20轮廓控制覆盖，内侧1输出像素羽化，素材只供色相，保留口腔和alpha；复用原FBO/VBO及脸颊/睫毛偏移。NativeFaceMotion局部匹配同步40点，至少28点及四个嘴角通过才校正，同内/外轮廓插值；时间戳/失效契约不变。双机各43项共86次设备执行通过，核心147通过1跳过、GPUPixel4通过，Debug/lint/无正式签名Release-R8及自有native静态16KB通过。生成像素轮廓通过不等于真人唇缘精度；40段Shader额外GPU成本未做持续对照。旧GPU重建SIGBUS及OPPO切分辨率偶发超时未关闭，具体证据见最新TEST_REPORT。


## 2026-10-31 唇部动态退化修复（覆盖上一轮实现）

用户反馈40点贴合版快速移动更差。对照复现单角遮挡时四角硬门控整组回退，以及OPPO720p合成口红GPU中位8.42ms。现恢复20点局部匹配预算/原14点有效门槛，保留匹配质量/前后向与完整40点几何检查，残差沿各环邻接传播至全部40点；不是降为20点绘制。完整轮廓改为120顶点环带三角网格，每片元两边距离，替换40边遍历；无新增FBO/等待/模型/公共API。新增单角遮挡及连续8帧64px接力测试，最终设备和构建证据以最新TEST_REPORT为准。合成夹具性能/误差不等于真人快速侧脸已验收，历史SIGBUS及分辨率偶发超时未关闭。


## 2026-10-31 口红程序化RGB染色

按用户要求，GPUPixel口红改为完整唇部网格内直接RGB uniform着色，默认(.90,.32,.38)，沿用强度、原视频明度/唇纹/alpha及内侧羽化/口腔排除。mouth.png移至非打包third_party参考，运行时仅四LUT＋腮红共5纹理，JNI尺寸同步10；来源导入/验证脚本和NOTICE同步。无新增模型、跟踪、色号UI/公共API；本轮设备/构建结果以最新TEST_REPORT为准，不能宣称仅去PNG即可解决动态贴合。

## 2026-10-31 直播热路径精简

NativeFaceMotion参考块统计只计算一次，整数候选累计且保持原搜索/拒绝条件，减少Kotlin点列表/索引分配；GlTextureAnalysisBridge移除中间CPU暂存复制，PBO只读借用在同步submit复制后立即unmap，各worker仍独占缓冲，不是零拷贝。Demo仅用实际Pipeline分析快照。双机最终各30项通过，OPPO/Pixel生成连续运动短测估计均耗时分别3.204→2.776/2.970→2.106ms，既有真值误差一致；不是真人动态、持续直播或最高性能保证。未新增模型、帧延迟缓存、光流或同帧同步；历史风险及构建/配置恢复证据见TEST_REPORT。

## 2026-10-31 大位移跟踪增量

用户真人跟随仍差后，在公开人像24px相邻横移复现旧±12单尺度搜索失效。NativeFaceMotion全局改为细层捕获＋最多3层有界稀疏零均值LK式光流；局部唇部仍保留20点块匹配/40点传播，不增加模型/依赖/等待或延长有效期。首版生成纹理回归已记录并修正；最终双机各32项通过，12/24/36px及平面旋转缩放夹具通过。不是专用唇部分割/三维跟踪，也不证明真人快速转头已解决；新版用户确认改善但仍拖尾，证据与失败阶段详见TEST_REPORT。


## 2026-10-31 美妆预览完成后补画

用户确认光流改善但仍拖尾。Demo对当前视频同采样的成功分析完成按需requestRender，每采样最多一次，原图/关闭/过时结果不唤醒；补画复用公共onRedraw，不再次检测或刷新时间戳。仅预览调度，不能用于未经约定的直播异步重复交帧；额外GPU合成成本、真人观感及端到端时延待测。双机各13项、核心148通过/1跳过、feature19通过及构建/lint证据见TEST_REPORT；新版已安装，不宣称动态质量通过。


## 2026-10-31 睫毛缩小可见度与参数保存

用户确认口红改善，另报图片睫毛不显示/重开效果强度丢失。GPUPixel图片睫毛仅缩小时按UV足迹有界补偿mipmap平均alpha，透明底/放大/口红保持原逻辑；公开小眼区三款由近乎不可见提升到31/49/37明显变化像素，非真人自然度验收。Demo相邻等待中的参数按字段合并，资源选择/开关/重置封闭批次，减少滑动IO积压；阻塞IO退出后重开恢复最终值与真实滑杆/图片选择回归通过，不保证落盘前强杀。双机各30项、资源JVM12、构建/lint/Release-R8及自有GPUPixel双ABI16KB静态通过，配置恢复与边界见TEST_REPORT；未改口红跟踪或新增模型。


## 2026-10-31 口红与睫毛隔离

美妆局部draw改为颜色/覆盖率固定功能RGB混合，保留原视频alpha、恢复宿主混合因子/方程，继续复用FBO且不读回；口红仍直接染色，不加眼睑跟踪或新模型。资源组合按旧LEGACY底层、独立槽位覆盖，最后用户参数；旧资源无eyeMakeup不清掉已有材质。Demo美妆已选卡片再次点击独立关闭并清零该scope手动强度，整类“无”仍关闭全部美妆。新增组合像素/透明重叠/宿主状态/单项关闭恢复/旧包顺序回归。OPPO复测出现MediaPipe drishti_gl_runn SIGBUS，另一次全图形用例长时间未完成被主动停止；不能因隔离回归通过声称生命周期稳定性解决。精确结果与边界见最新TEST_REPORT。


## 2026-10-31 睫毛整副闪烁

新增Debug限频显示条件诊断；聚合“无可用face”不能当作模型无脸。受控复现辅助跟踪失败会否决比最后成功匹配更新的有效真实模型；TrackedFaceAnalysis现仅允许该真实模型在原maxAgeNs内继续使用，不复活旧匹配、不更改采样时间/有效期，不改口红局部跟踪或alpha算法。新增JVM故障边界及三款×16步公开人像位移GL回归。真人是否完全改善、相机测试启动未完成和既有MediaPipe稳定性均以最新TEST_REPORT为准。

## 2026-10-31 美妆预设/微调对象联动

用户报告点击口红却调到睫毛。OPPO真实面板回归复现：停在睫毛微调、点击口红预设后，微调仍绑定旧睫毛滑杆；直接点参数按钮的三字段隔离断言通过。ResourceCenterController按预设单参数scope通知ResourceParameterPanel聚焦，后者在渲染时按同一ID切换，并让已有参数按钮使用最新绑定回调。SDK渲染/检测策略未由本增量改变；最终设备结果、配置恢复及未覆盖范围见固定TEST_REPORT。

## 2026-10-31 腮红保色/区域约束

腮红固定浅粉素材只提供分布蒙版，改为零加权RGB亮度色差染色并统一按色域余量收缩，避免白底RGB柔光提亮皮肤。鼻点1、外缘234/454、眼睛和下巴限制每侧内外宽度/上下高度；单侧脸颊点无效清该侧旧顶点。保留每侧两个三角形、原FBO/VBO/PNG/强度契约，无新模型/分割/稠密网格/色号API。Pixel8五项设备回归和GPUPixel七项JVM通过，OPPO本轮离线；Release/native及证据以固定TEST_REPORT为准，不代表真人自然度、侧脸遮挡或持续性能通过。

## 2026-10-31 腮红妆形增量

腮红在鼻/外缘/眼线约束内向外上方延展、收窄上下厚度；平方根分布展开低覆盖率柔边、降低峰值，仍保留前轮加权RGB亮度保护及用户强度。Pixel8实际染色主轴比例旧1.32→新2.16，五项设备/七项GPUPixel JVM通过；这不是自然度或真人转头验收。没有新增模型/网格层级/全屏pass/等待，仍为每侧两个三角形。公开人像对照、Release/native及限制见最新TEST_REPORT；OPPO离线未安装验证。

## 2026-10-31 设置隔离与胡子方向

新增BeautyResourceEdits纯状态操作、ResourceEffectApplication类型字段隔离和唯一合成顺序；Demo滑杆显式提交字段，普通重绘不写全量参数。删除激活美妆同步中性化对应scope。NativeFaceMotion保持原转正语义嘴角身份，不按原始x重排，修复镜像/旋转胡子翻转。36项JVM及Pixel8 12项设备回归通过；OPPO离线，历史MediaPipe SIGBUS、启动阻塞、长期性能/真人完整验收仍待处理。详见最新TEST_REPORT，不代表全部商业验收完成。

## 2026-10-31 胡子拖后增量

嘴部贴纸中心不再叠加35ms位置低通，眼部/头饰原策略及嘴部角度尺度平滑保留。Demo按生效UPPER_LIP素材/胡子开启既有GPU优先与同采样补画，不要求同时开启口红；主题/工坊覆盖和3D互斥不变。32JVM、Pixel8设备12项通过，OPPO离线；真人跟随/抖动与长期性能、历史原生崩溃未验收。详见TEST_REPORT。

## 2026-10-31 独立妆色与初始化守卫

口红/腮红独立RGB配置贯通Android公共API、GPUPixel、makeup.colors.v1资源能力及Demo选色/恢复；状态V2读取兼容V1、旧SDK不读V2。缺失睫毛仅关闭对应强度；初始化前后代次检查防止过期任务额外推理。JVM193通过/1跳过、Pixel8十八项通过、2387 JVM签名保留、本地五制品及独立消费者Debug/Release R8通过。OPPO离线；历史MediaPipe原生崩溃、真人动态画质与长测未关闭。CMS/iOS妆色、精细分割/遮挡、通用3D等未实现，详见doc/SDK_OPTIMIZATION_PLAN.md及最新TEST_REPORT；不得描述为全部优化完成。

## 2026-10-31 跟踪参考图发布锁优化

TrackedFaceAnalysis将检测侧motion.prepare移到worker发布锁外，锁内只提交准备好的参考，沿用AsyncFaceAnalysis代次检查；不改有效期、推理数量或配置。旧阻塞夹具先超时；新增reset/close两项及完整核心/宿主JVM174通过、1跳过，Pixel8十二项通过、私有507文件恢复一致。OPPO离线，真人延迟收益/持续性能及历史MediaPipe崩溃未关闭；本轮未重新发行Maven，详见TEST_REPORT。

## 2026-10-31 双级跟踪灰度预处理

NativeFaceMotion唇部跟踪双级灰度合并一次JNI；整数嵌套格复用细节灰度取点，其余保持原采样，同尺寸共享一个自有缓冲。Pixel8三项新增逐字节/边界/短测及十二项消费者共15通过，私有507文件恢复。Debug步骤短测329.45→272.33μs（约17%，非SDK/FPS收益）；宿主JVM20本次通过，核心155命中增量缓存含1跳过。双ABI AAR、unsigned app Release/R8及自有ELF/APK静态16KB通过；OPPO/16KB运行/Release运行/历史崩溃与真人长测未验收，未重新发布Maven。详见最新TEST_REPORT。

## 2026-10-31 静止背景时序稳定

MediaPipeForegroundMasks新增默认开启enableTemporalStabilization，C++转换内融合RGB/置信度门控，当前alpha偏差最多8/255；代次/geometry/150ms断层或关闭失效。三张不可变蒙版按视频真实时间选取，250ms过期不变。OPPO/API30本轮19项设备回归、双ABI AAR/unsigned Release R8/静态16KB通过，503私有文件恢复；受控alpha波动232→57不代表真人验收。Pixel8测试前离线。发丝matting、真人运动/静止质量、持续成本及历史MediaPipe崩溃仍未关闭，详见TEST_REPORT。
