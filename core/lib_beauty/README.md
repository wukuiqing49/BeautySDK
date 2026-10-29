# 共享美颜库

完整主线接入、Android assets资源加载和JSON效果展示见[Android SDK 使用说明](../../doc/ANDROID_SDK_USAGE.md)。本文件保留公共帧API及基础渲染参考，GPUPixel/MediaPipe能力请以主线说明和模块文档为准。

`lib_beauty` 提供统一帧 API、异步人脸分析调度、CPU/ES3 渲染，不依赖相机、直播引擎或宿主基础框架。

## 最小接入

```kotlin
// 一路视频使用一个会话；下面的所有生命周期和 process 调用在同一视频线程执行。
val beauty = BeautySession(
    factory = faceAnalyzerFactory, // 可为 null；基础磨皮、美白、色调无需人脸模型
    renderer = RgbaBeautyRenderer(),
    analysisIntervalNs = 100_000_000L, // 最多约每 100ms 尝试采样，不限制视频输出频率
    analysisMaxSide = 320,
    onError = { error -> /* 记录错误；此回调可能来自分析线程 */ },
)
beauty.settings = BeautySettings(smoothing = .4f, whitening = .3f, glasses = true, moustache = true, hat = true)
beauty.start()
// 引擎提供 RGBA8888；时间戳为同一单调时钟的纳秒值，逐帧严格递增。
val output = beauty.process(RgbaFrame(info, buffer, rowStrideBytes))
// 把 output 交回选定引擎的编码前处理接口。
// 切摄像头、重启视频源：stop/start；结束时 close。
```

## 输出与资源

- CPU 渲染产生独立 RGBA 缓冲；无效果时允许返回借用输入。不得在使用旁路输出前回收原帧。
- `GlBeautyRenderer` 要求宿主已持有同线程 ES3 EGL 上下文，支持 2D/OES；输出纹理只有效至下一次渲染或关闭，需要异步编码时由宿主复制或同步消费。
- 纹理处理不隐式读回 GPU。宿主用 `submitAnalysis` 提供与纹理逻辑画面一致的 RGBA 副本，方向、镜像、时间线及裁剪视场须一致；显式 allowScaledAnalysis=true 后允许分析图尺寸不同，可先提交分析图再处理同时间戳纹理。
- 分析提交时批量复制到复用 RGBA DirectByteBuffer，回调返回后宿主可回收源帧；缩图/转换/推理在单 worker 内执行；统一运行时模型支路最多保留一张最新候选，跟踪支路忙时在复制前丢帧。MediaPipe 使用原生融合转换。方向、镜像和不兼容视场变化会失效旧结果，同尺寸切摄像头仍需 stop/start。
- 超过 `maxResultAgeNs`、无脸或缺少真实锚点时隐藏挂件。设置对象可跨线程整体替换，生命周期和渲染仍须同线程。

当前是基础磨皮、美白、暖冷色调及程序化眼镜/胡子/礼帽原型，另支持 PNG 图片贴纸，不包含精细瘦脸、大眼、分割遮挡、LUT 或在线素材管理系统。CPU 实现用于参考验证；持续直播速度须按真机测量。

## 人脸挂件

`BeautySettings` 的 `glasses`、`moustache`、`hat` 独立控制眼镜、胡子、礼帽；可叠加，`animateStickers` 控制轻微周期缩放。礼帽为蓝色程序化 2D 形状，使用双眼间距缩放、眼线角度旋转，并在镜像画面中修正朝向；它不是头部分割或精确头顶测量。

MediaPipe 后端提供三种效果所需锚点。丢脸、结果超时或缺少对应锚点时隐藏挂件，基础美颜继续处理。

Demo 的静态图和相机页均提供三种开关，选择 MediaPipe 后端后对准人脸测试。当前不含可下载 PNG 素材包、3D 模型或遮挡分割系统。

## PNG 素材接入

CPU 和 GL 渲染器共用 `BeautySettings.stickerPack`，最多三层，按列表顺序 source-over 合成。

```kotlin
// BitmapFactory 解码后，用 getPixels 获取非预乘 ARGB；不要直接拿预乘缓冲区当非预乘 RGBA。
val colors = IntArray(bitmap.width * bitmap.height)
bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
val image = StickerImage(bitmap.width, bitmap.height, colors) // 构造时复制，可随后回收 bitmap
val glasses = ImageSticker(image, StickerAnchor.EYES, widthInAnchorSpans = 2.5f)
session.settings = BeautySettings(
    smoothing = .4f,
    stickerPack = StickerPack(listOf(glasses)),
)
```

`StickerAnchor.EYES` 以双眼中点、眼距和眼线方向定位；`UPPER_LIP` 以真实上唇、嘴角距离和嘴线定位。图片按显示坐标摆放，再对应到原始视频，支持旋转和镜像，左右不对称素材不会因前摄镜像而错误倒置。素材自身 pivot 默认为中心，帽子可用 `(0.5,1.45)` 将图案上移。宽高比保持素材原始比例，画面外部分裁切；缺锚点或结果过期隐藏对应层。

素材像素和包列表均有防御性复制；同一 `StickerImage` 应缓存复用，不要逐帧解码/创建。CPU 输出独立缓冲，透明底按非预乘 source-over 累计 alpha。GL 缓存最多三张素材纹理，仅在对象变化时上传，变更到空槽的下一次实际渲染会释放旧槽，关闭时统一释放；纯旁路时缓存可保留到关闭。恢复纹理单元 0—3、sampler 和像素解包状态。

CPU/GL统一使用双线性采样及半像素中心/边缘钳制规则；没有隐式 GPU 读回。输出纹理有效期与原 GL 管线一致，只到下一次 render/close，尚未完成 OES 与上下文丢失矩阵验收。

Demo 将六张本地素材及 manifest 放在 feature_app assets，通过 `DemoStickerCatalog` 按需读取和缓存；用户选择两款眼镜、礼帽/皇冠/猫耳，胡子为独立开关。Demo 已切换为 PNG 效果；程序化布尔开关仍保留供旧调用方式使用，不要同时启用两套相同挂件。


## 分析诊断与实时页

BeautySession.lastFrameAnalysis 表示本次真正交给渲染器的检测快照，包含状态、结果年龄、分析耗时和错误；渲染后的新检测结果不能冒充已经绘制的结果。SDK默认异步及150ms有效期保持不变。

宿主相机页现改为独立OES/GPU适配，检测结果有效期150ms，纯纹理渲染与分析线程独立；不是改变SDK通用会话的默认值。具体实现和未验收项见 `doc/GPU_CAMERA_PIPELINE.md`，静态图仍使用CPU参考路径。


## 统一相机与直播引擎输入（2026-10-26）

SDK只消费帧，不打开相机、不管理推流。帧来源是 CameraX、ZEGO、声网或文件，不改变检测、结果选择与合成算法。当前支持的标准输入为RGBA8888及OES/2D纹理；I420/NV21或仅纹理且无分析图的来源，仍需要格式/检测输入适配，不宣称已有厂商适配器。

```kotlin
// 在宿主当前 ES3 EGL/视频处理线程创建。模型在 SDK worker 上创建和调用。
val session = BeautySession(
    factory = MediaPipeFaceAnalyzer.factory(applicationContext), // 来自独立后端模块
    renderer = GlBeautyRenderer(),
    allowScaledAnalysis = true, // 仅限检测小图与输出纹理确实共享同一裁剪视场
    onAnalysisTiming = { timing -> /* SDK提供复制、排队、分析和结果更新间隔 */ },
)
session.settings = BeautySettings(smoothing=.4f, whitening=.3f, glasses=true)
session.start()
// 同一时间线、同方向/镜像；尺寸填写各自真实尺寸。
session.submitAnalysis(RgbaFrame(smallFrameInfo, smallRgbaBuffer, smallRowStride))
val output = session.process(TextureFrame(videoFrameInfo, textureId, textureTarget, textureMatrix))
// 由厂商适配层在规定时机交回 output；不要在这里猜测厂商API。
```

### 两个回调线程的输入

相机的分析回调和GL回调可以分属不同线程：两者共享公共 `BeautyAnalysisRuntime`，GL端通过 `BeautySession(sharedAnalysis=...)`合成。相机Demo已使用该组合，不再在页面侧单独选择人脸并直接合成。

```kotlin
val analysis = BeautyAnalysisRuntime(
    factory = MediaPipeFaceAnalyzer.factory(applicationContext),
    maxSide = 320,
    allowScaledFrames = true, // 必须同裁剪视场、同宽高比
    motionEstimator = NativeFaceMotion(), // 可选实验跟踪
)
analysis.setTrackingEnabled(true)
analysis.setEnabled(settings.requiresFaceAnalysis)
// 相机/引擎采集回调：捕获当前代次，返回前完成有界复制。
val token = analysis.generationToken()
analysis.submit(smallRgbaFrame, token)
// 当前ES3渲染线程：一路视频对应一个会话。
val session = BeautySession(renderer=GlBeautyRenderer(), sharedAnalysis=analysis, allowScaledAnalysis=true)
session.settings = settings
session.start()
val output = session.process(textureFrame)
// 同一采样帧仅因滑杆变化重绘：session.redraw(textureFrame)，不改写时间戳。
// 关闭顺序：停止采集 → session.close()（所属GL线程）→ analysis.close()。
```

- 独占运行时的普通Session按 `settings.requiresFaceAnalysis`自动开关分析；只开基础美颜不创建模型。只想检测的调用方直接使用公共运行时并显式启用。
- 借用运行时的Session不代替宿主管理分析需求、跟踪开关和最终释放。停源/换镜头必须显式reset运行时；映射更新会reset并作废在途结果。
- 运行时统一模型采样间隔，限频时仍允许轻量图像跟踪。maxSide同样传给跟踪组合内的模型检测器。
- 一路视频不能混入其他轨道的时间戳。重复分析时间戳返回false；process仍要求严格递增，redraw只接受最近采样帧的相同几何和时间戳。
- 最多一张检测执行、一张最新待处理，跟踪忙时丢帧；没有无界图像队列。分析分辨率不改变输出分辨率。
- 输出纹理仍只有效到下一次render/close。异步编码需要适配层纹理池及fence/租用协议；仅加fence不能防止纹理被下一帧覆盖。
- 当前不提供I420/NV21转换或ZEGO/声网适配，不把GPU delegate称为纹理零拷贝。无分析图仍可基础美颜。

### 最新待处理帧调度（2026-10-26）

`AsyncFaceAnalysis(keepLatestPending = true)` 可用于低分辨率分析支路：最多一帧推理、一帧待处理；忙时新帧覆盖待处理帧，推理完成后立即消费最新候选。两个 DirectByteBuffer 交替复用，提交返回前已复制源数据，reset/close 清空候选。默认 false 保留原先忙时丢帧语义；相机 GPU Demo 显式启用。

该策略减少推理结束后的等待，但增加忙时的帧复制，候选也可能产生等待时间。它不保证降低所有场景的结果年龄，不降低模型推理耗时，不改变 150ms 默认过期限制，也不是光流/帧间跟踪。应对比相同输入下的结果年龄和过期占比后决定是否在厂商适配中启用；不要为此复制完整高清纹理到 CPU。


## 不同分辨率和视场的显式映射（2026-10-26）

同视场小分析图仍可使用 `allowScaledAnalysis=true`。不同裁剪/留边/方向应显式配置 `AnalysisFrameMapping`，不靠宽高比猜测视场：

```kotlin
session.start()
session.setAnalysisMapping(AnalysisFrameMapping(
    source = FrameGeometry(320, 240),
    target = FrameGeometry(720, 1280),
    // 示例：4:3完整检测图中央裁成9:16；必须与引擎实际裁剪相符。
    sourceRegion = FrameRegion(.2890625f, 0f, .7109375f, 1f),
))
session.submitAnalysis(smallRgbaFrame)
val output = session.process(engineTextureFrame)
```

- source/target分别描述真实缓冲尺寸、顺时针旋转和随后水平镜像。region位于各自转正/镜像后的归一化空间。默认完整区域只适用于两支路同视场；targetRegion可指定留边画面中的有效内容区。
- 映射只转换定位结果，不裁剪输出像素、不生成检测图；宿主必须保证实际输出逻辑画面与声明的变换一致。TextureFrame.transform是输出逻辑UV到纹理采样UV的独立矩阵，不应重复套在贴纸坐标上。
- 映射模式必须显式submitAnalysis；process即使收到RGBA也不重复分析输出。优先提供低分辨率分析帧，避免先复制完整1080p再缩小。仅有高清纹理时的低分辨率读回支路仍由宿主适配，SDK没有暗中新增读回。
- 映射结果保留源采样时间，150ms有效期不延长；同一结果只转换一次，缓存映射后的478点。被裁剪掉的锚点移除；整脸在裁剪外时隐藏，不能钳制到画面边缘冒充真实位置。
- 动态尺寸、裁剪、方向或摄像头改变时，在提交新帧前重新setAnalysisMapping。每次调用都清空旧结果/候选并使在途结果失效，即使映射值相同也可用于同尺寸切源。当前采用保守失效策略，不承诺切换时连续显示；尚未发布新结果前隐藏贴纸。
- setAnalysisMapping、submitAnalysis、process、latestFaces及生命周期都在session所属线程使用；相机分线程宿主也可组合AsyncFaceAnalysis和AnalysisFrameMapping.map，但须自行绑定映射代次并缓存结果。
- stop清空映射，restart后重新配置；setAnalysisMapping(null)恢复旧模式。未先更新配置的尺寸/方向不匹配会明确拒绝，不能复用旧坐标。
- OpenGL输出保持输入像素尺寸，分辨率变化重新分配FBO；输出只有效至下一次render/close，引擎不得异步持有旧纹理。该契约没有因映射而改变。

当前没有新增NV21/I420输入、自动厂商裁剪识别或分辨率相关美颜强度归一化。磨皮跨分辨率视觉一致性和真人贴纸完整矩阵仍需专项验证。


## 可选图像跟踪（2026-10-26，实验）

公共BeautySession可传入 `motionEstimator`，或分线程宿主直接使用 `TrackedFaceAnalysis`。接口位于lib_beauty，C++实现NativeFaceMotion暂随lib_beauty_mediapipe打包；核心库不依赖MediaPipe类型。

```kotlin
val session = BeautySession(
    factory = MediaPipeFaceAnalyzer.factory(context),
    renderer = GlBeautyRenderer(), // 在所属EGL上下文创建/使用
    allowScaledAnalysis = true,
    motionEstimator = NativeFaceMotion(),
)
```

检测和跟踪各使用独立有界worker；跟踪忙时直接丢帧，检测保留最多一张最新候选。模型analysisIntervalNs限频不会同时限制轻量跟踪。两支路各自复制借用RGBA以保证所有权，非零拷贝；宿主应提供小分辨率分析图，不能把此实现当高清纹理读回方案。

NativeFaceMotion将原始方向图缩到最长边160的灰度图，以16个关键点做局部零均值块匹配，检查纹理、前后向误差、相似变换一致性，并用一致点细化平移/平面旋转/缩放。每次直接匹配最近模型参考图，避免多帧链式漂移。跟踪当前灰度缓冲复用；模型参考灰度需要独占小快照，不能在另一线程读取期间覆盖。

这不是成熟三维姿态跟踪或稠密光流：大运动、侧脸、遮挡、低纹理可能匹配失败。原始检测年龄上限默认300ms，跟踪帧年龄仍受maxResultAgeNs（默认150ms）约束；仅图像匹配成功才允许在模型结果过期后提供位置，不无限保留坐标。模型无人脸/错误、跟踪失败且检测也已过期、校正超时、会话重置会隐藏。FaceAnalysisSnapshot.tracked标识来源，detectionTimestampNs保留真实模型采样时间，result.info.timestampNs表示被匹配的新帧。坐标映射保持这两个时间语义。

实时相机页默认勾选“图像匹配贴纸跟踪（实验）”，可在调试弹框关闭作对照。MediaPipe CPU＋基础OpenGL仍为默认组合。BeautyTracking新增tracked与maxDetectionAgeMs，BeautyMotion记录跟踪worker的全量窗口统计（含无参考/失败的快速返回，faceResults表示成功结果数）。尚未证明持续真人闪烁已解决，必须对照移动、遮挡、进出画面和温升。

连续有效检测更新参考图时，允许上一参考生成的更新跟踪帧完成交接；选择依据为采样时间，仍使用原始检测时间检查校正期限。无脸、错误、几何变化和reset会隔离旧跟踪段。此机制不等于人脸身份识别，也不保证匹配失败或模型校正时没有位置跳变。

### 贴纸更新稳定性

可选跟踪运行时仅将成功匹配写入有界位置历史；单帧失败不覆盖最近成功位置，也不刷新其时间戳。位置仍受调用方maxAgeNs和原始模型参考maxCorrectionAgeNs约束，明确无脸、错误、换流和reset仍撤销。

基础OpenGL在贴纸姿态层平滑中心、跨度和角度，时间常数35ms，仅处理三个姿态，不遍历网格、不处理图像。以锚点跨度衡量位移和缩放，变化达到8%或角度变化达到0.10弧度时直接跟随；小变化会引入短暂平滑延迟。无锚点立即隐藏，CPU参考渲染仍保持静态几何，不启用时序平滑。上述阈值是当前原型参数，真人效果尚待验收。

## 标准静态贴纸资源包

新增 `StickerResourcePack.loadZip`、`StickerResourceManifest` 和 `StickerLayerSpec`。v1 ZIP支持根目录配置、预览PNG和1～3层静态PNG，配置包含锚点、相对跨度、中心点、偏移与附加旋转。加载/导出由宿主IO线程执行，调参通过withManifest复用图片对象；CPU和GL共享布局计算。

制作规范、模板、6个样例和Android调用方式见 [贴纸制作与调试](../../doc/stickers/README.md)。相机Demo的“贴纸 → 素材资源工坊”支持样例、导入、逐层调试与JSON/ZIP导出；这不代表已接入第三方直播引擎。

## 公共诊断与素材内存（2026-10-26）

- `BeautyTimingWindow`记录复制、排队、分析、预处理、推理及结果间隔；均值/最大值覆盖窗口，P95/P99最多保留最近256样本并报告Samples，不是完整长期分布。
- `BeautyFrameWindow`区分新视频帧和同帧重绘，记录CPU提交P95/P99、结果年龄和失效状态。本地消费FPS不是远端编码FPS；没有GPU等待或读回，不能称为端到端延迟。
- 内置素材IO最多一个任务，只保留最新选择；最多缓存当前三层，逐个发布，坏素材不会阻止其他素材。布局调参不重复解码或上传同一图片。
- ZIP解码器将独占像素数组转交不可变StickerImage，省掉一次完整IntArray复制；公开StickerImage构造仍防御复制。工坊保留原PNG以支持导出；运行时仅持有StickerPack即可不保留编码副本。
- 工坊仅持久化应用私有的一份ZIP草稿，600ms防抖并原子提交；恢复默认不启用贴纸。不是素材商城/无限素材库，也不保存相机图像。

## 直播引擎公共输入与输出租约（2026-10-26）

`Yuv420Frame`是独立输入适配描述，不是新增Session原生渲染格式。支持I420、YUV_420_888；NV12/NV21用带position偏移、pixelStride=2的U/V平面描述。必须显式指定BT.601/BT.709及FULL/LIMITED，不能猜测颜色范围。底层缓冲借用至convert返回；构造平面时保留原buffer的position/limit，不修改宿主视图。

```kotlin
val converter = Yuv420RgbaConverter(maxSide = 320) // 在固定采集线程复用
// 从引擎的真实平面构造yuv，U与V的顺序不能互换。
val smallRgba = converter.convert(yuv)
analysis.submit(smallRgba) // Runtime复制后即可复用转换缓冲
// 同视场的高清纹理仍走Session；需要allowScaledFrames/allowScaledAnalysis及正确mapping。
```

此转换器先缩小、再采样转换，复用direct buffer，不创建Bitmap。它是Kotlin兼容路径，不是libyuv/SIMD；不支持RGBA转回YUV，也不代表高清YUV端到端管线已打通。返回帧借用至下一次convert，不能保留用于异步渲染。

`GlTextureOutputPool`提供1..8个有界RGBA 2D纹理槽位，默认3个。每次copy增加一次GPU拷贝；租约未归还前不会覆盖。池满返回null，不等待、不擅自丢弃宿主输入。尺寸变化只重建已空闲槽位。

```kotlin
// 在当前ES3上下文创建；所有copy/close都在相同线程和上下文。
val outputPool = GlTextureOutputPool(capacity = 3)
val rendered = session.process(textureFrame) as TextureFrame
// 仅TEXTURE_2D；原图旁路返回OES时应由宿主处理，不能直接copy。
val lease = outputPool.copy(rendered)
if (lease != null) {
    // 适配层交付lease.frame；消费者实际完成GPU读取后调用lease.close()。
    // 跨共享EGL上下文需生产fence+flush及消费等待，归还前也需确认消费完成。
    // 不共享的上下文不能使用纹理ID；同步回调返回不等于GPU消费完成。
}
// 停止生产 -> 等待全部消费者完成并归还 -> outputPool.close() -> session.close()
```

Lease.close可跨线程且幂等；不执行GL调用。frame归还后禁止再用；pool.close尚有租约时抛错，不强制销毁。池不创建线程/EGL/fence，不接管编码器；context丢失后的恢复和清理需宿主重建会话及池。默认原有Session契约未改变，只有显式copy得到的租约才延长输出有效期。

## 只有纹理时的可选分析桥接

`GlTextureAnalysisBridge`接收与Session相同的原始2D/OES纹理，GPU按采样矩阵缩成最长边320的小图；两个PBO槽位与fence轮询限制在途任务。无贴纸时不采样，默认最多约30次/秒提交读回；已完成小图复制到可复用direct缓冲，再交给已有BeautyAnalysisRuntime。没有Bitmap、不读取全分辨率像素，不调用glFinish或非零timeout等待；驱动的命令提交/映射仍可能有成本，不能称为零拷贝或绝对无阻塞。

```kotlin
// 全部GL对象在宿主固定ES3上下文/线程创建、使用及释放。
val analysis = BeautyAnalysisRuntime(factory, allowScaledFrames = true)
val bridge = GlTextureAnalysisBridge(analysis, maxSide = 320)
val session = BeautySession(renderer = GlBeautyRenderer(), sharedAnalysis = analysis,
    allowScaledAnalysis = true)
session.start()

// 每次原始输入采样帧（非UI同帧重绘）：
session.settings = settings
bridge.submit(inputTextureFrame, settings.requiresFaceAnalysis)
val output = session.process(inputTextureFrame)
// 交回方式由引擎适配层决定；异步消费2D输出时另用输出租约。

// 换同尺寸镜头/源/视场时：bridge.reset()；Session时间线重启须stop/start。
// 停止生产后，在创建GL上下文：
bridge.close()
session.close()
analysis.close() // 借用运行时由宿主释放，bridge不关闭它。
```

约束与取舍：

- **有独立RGBA检测小图时不使用此桥接器。** 现有相机Demo保持独立采集检测支路，未额外读回。
- 同一运行时只对应一个视频源，桥接器管理其enabled需求；不要同时与另一采集方提交混用。仅贴纸/依赖人脸效果开启时needsFaceAnalysis=true。
- `submit`返回true只代表前一采样小图被分析运行时接受；绝不表示当前纹理检测完成。初始几帧可能无结果，无新视频帧则不会继续轮询。
- 为避免等待，读回结果至少到后续submit才取回，会增加分析年龄；默认100ms内的已完成小图才投递。检测结果仍按原采样时间过期，不把轮询时间伪装成采样时间。
- rotation/mirrored保留为元数据，桥接器不重复物理旋转；采样矩阵已烘焙到小图像素。其空间与GlBeautyRenderer相同，宿主需保证原始纹理FrameInfo及transform准确。
- 尺寸、方向、镜像或采样矩阵变化会隔离旧读回并重置分析；同几何换视频源需显式reset。GPU未完成时槽位不复用，不用等待换取每帧检测。
- 小图FBO/PBO及CPU缓冲复用，关闭取消本桥接器待取结果但不销毁共享analysis；停止整条链路时宿主须关闭或禁用analysis，防止沿用旧源结果。
- OES要求设备支持ESSL3外部纹理扩展。上下文丢失后由宿主重建桥接器、渲染器和Session；不跨不共享context使用纹理ID。
- 该能力不是厂商适配器，尚未实现真实引擎回写、跨context同步、远端画面及持续性能验收。

## 统一引擎帧回调入口

业务需要的“取出引擎帧 → 封装SDK帧 → 处理 → 写回引擎”由`BeautyEngineConnector<F>.onFrame`统一完成。`BeautyEnginePipeline`组合已有BeautySession/BeautyAnalysisRuntime/可选GlTextureAnalysisBridge，不另起算法管线。不依赖任何直播厂商。

```kotlin
// 以下在已选定的固定ES3线程/上下文中创建、启停、调用、释放。
// engineAdapter是该引擎的一次性薄封装，实现read/write/passthrough。
val pipeline = BeautyEnginePipeline(factory = mediaPipeFactory)
pipeline.settings = BeautySettings(smoothing = .4f, whitening = .3f, glasses = true)
val connector = BeautyEngineConnector(engineAdapter, pipeline, onError = { error ->
    // 记录可诊断错误；不在这里同步调用引擎设置或UI操作。
})
connector.start()

// 引擎每帧回调只调用这一处；回调返回值/发送接口由厂商封装翻译。
val delivery = connector.onFrame(engineFrame)
// delivery.accepted表示引擎是否接受，不代表检测完成或远端已显示。

// 换同尺寸摄像头/采集源前，停止旧源送帧，在执行线程调用：
connector.resetSource()
// 注销/停止引擎送帧后，在执行线程关闭：
connector.close() // 释放pipeline、Session及其自有分析和GL资源。
```

厂商薄封装`BeautyEngineFrameAdapter<F>`仅有三个方法：

| 方法 | 责任 |
| --- | --- |
| read(frame) | 从真实引擎帧取出缓冲或纹理，封装VideoFrame；统一时间戳单位、方向、采样矩阵和颜色语义。输入仅借用至onFrame返回。 |
| write(frame, output) | 使用引擎的真实写回API或帧替换接口；返回是否接受。不允许返回后还无保护地消费借用纹理。 |
| passthrough(frame) | 解包或处理失败、尚未写回时，将原始引擎帧旁路交回；具体做法由引擎契约决定。 |

写回一旦尝试，不再自动旁路重试，防止一次回调双重提交。write抛异常/返回false均为REJECTED；接入方可做诊断，不能将它解释为一定可再次送同帧。原图模式仍调用write交回原帧。PROCESSED/BYPASSED按处理结果是否原对象区分，不表示远端成功。

`BeautyEnginePipeline`默认GL纹理路线，按需管理模型、缩小检测图和合成。factory与sharedAnalysis均为空时只做基础美颜。已有独立RGBA检测支路时设textureAnalysis=false，在同处理线程调用pipeline.submitAnalysis；RGB帧可配RgbaBeautyRenderer用于兼容/参考，不代表高清CPU实时推荐路径。settings可由UI整体替换；渲染时读取一次快照。

资源与线程边界：

- connector拥有processor；使用后不要同时从另一入口直接process/stop同一pipeline。onFrame不支持递归调用。
- 生命周期/错误线程调用直接拒绝，不在错误线程调用引擎旁路。普通处理错误才尝试一次passthrough。
- 本入口是同步处理边界，不自动创建GL线程、不异步返回引擎回调。声网可变回调线程等情况仍需适配层建立固定执行环境并保证输入有效期，不能盲目post后结束回调。
- 同一上下文同步消费可直接交回；异步消费须由write显式复制/租用输出、保持共享上下文并完成GPU同步，将租约归还绑定到真实GPU消费结束。不能把write返回当作GPU已读完。
- 纹理桥接失败报告后清除旧检测，基础美颜继续；普通渲染失败由Session旁路。CPU/纹理输出类型需与选用renderer匹配。
- 此入口尚不是声网/ZEGO实现，未注册其回调、未引入其依赖、未完成远端推流验证。厂商版本固定后，只补薄封装，不重写公共算法。


### 相机与引擎共用入口

相机Demo现在也通过Connector/Pipeline处理OES输入并同步消费输出，不额外读取预览纹理。CameraX的独立RGBA小图仍由采集线程直接提交给共享BeautyAnalysisRuntime；GL线程构造`BeautyEnginePipeline(textureAnalysis=false, sharedAnalysis=analysis)`。借用模式下，enabled、换源reset及最终close均由宿主管理；管线切后端/启停不销毁共享模型。不要同时使用factory或开启textureAnalysis。

只因UI调参重绘最新采样帧时调用`connector.onRedraw(frame)`：要求FrameInfo等于最近成功处理帧，更新设置并统一read/write，但不推进采样时间线、不重复提交分析。新视频帧仍调用onFrame。借用输入纹理必须仍有效；相同元数据不代表可以复用已被生产者覆盖的旧图像。

相机适配器仅在当前onDrawFrame保存输出引用，立即绘制后清除；没有将借用FBO存入异步队列。CameraX采集、裁剪矩阵及主副画面仍归Demo，SDK不接管相机。实际直播厂商仍需各自薄封装和同步协议。


### 快速运动与直播帧共用跟踪

`BeautyEnginePipeline(factory=mediaPipeFactory, motionEstimator=NativeFaceMotion())`可为自有分析启用轻量图像跟踪；NativeFaceMotion由独立mediapipe模块提供，核心只依赖FaceMotionEstimator接口，不引入CameraX或厂商类型。外部RGBA支路配置textureAnalysis=false并提交同视场/同时间基准的小图；仅纹理输入保持可选小图桥接，跟踪与检测均使用实际分析采样时间。借用sharedAnalysis时不传motionEstimator，由运行时所有者配置，避免重复worker。

NativeFaceMotion保持原单尺度局部块匹配及前后向/相似变换校验；新增的两级搜索在用户报告真人跟随退化后已撤回。它补偿短时2D运动，不能代替3D姿态或遮挡。

共享姿态平滑已恢复原约35ms小幅平滑及大位移直接跟随；此前新增连续增益已撤回。输入时间戳需转换为同一单调纳秒基准；方向/镜像/裁剪变化或换源须reset，原有150ms结果期限与300ms检测参考期限不变。此优化不自动解决引擎回调、跨EGL同步和远端推流。

## 可选场景效果

BeautySceneRenderer组合背景虚化/纯色/图片替换与数字推近/摇移/人脸居中；BeautySettings.scene默认关闭。分割通过独立ForegroundMaskProvider注入，核心不绑定MediaPipe；纯纹理可选小图消费者与双PBO桥，非零拷贝。接入、所有权与成本见[场景说明](../../doc/SDK_SCENE_EFFECTS.md)。CPU场景合成未提供。

可选手势运行时和事件API已加入（默认关闭）；它不自动播放动画。官方模型与实验规则、输入/线程/时效契约见[手势接入](../../doc/SDK_GESTURES.md)。
