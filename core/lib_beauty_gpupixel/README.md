# GPUPixel 美颜适配库

本模块复用 GPUPixel 的美颜 Shader、radius=4 box blur、高通差分和四张 LUT，使用独立 C++/JNI 纹理适配器接入 BeautySDK。**不是完整 GPUPixel 运行时的二进制依赖**，不包含其独立 EGL 线程、人脸检测器、模型。局部口红采用程序化RGB染色，腮红从固定素材提取覆盖蒙版，使用保留加权RGB亮度的血色染色，网格由本适配器映射MediaPipe478点。新增美型借鉴其固定版本曲线形变公式，显式映射MediaPipe478点，并非直接套用原106点下标。

- 上游：<https://github.com/pixpark/gpupixel>
- 固定提交：`ef552bf8ce2d0d41fa9b979bfb5c7cf79374ca88`，不跟随 main 自动更新。
- 来源和 SHA256：`third_party/gpupixel/SOURCE.json`；原始参考文件保留版权头，许可证 `third_party/gpupixel/LICENSE`。
- 生成脚本：`python scripts/import_gpupixel.py <固定提交的本地源码目录>`。脚本核验提交，只复制限定文件；运行构建不访问 GitHub。
- 美颜主 Shader 保持上游 GLES 字符串原样；box blur 特化为 radius=4、spacing=4，差分 delta=7.07。
- 相同均值支路共享两次 blur 结果；只美白跳过 blur/difference；只贴纸跳过 native 美颜。
- Android API 24+ / ES3，arm64-v8a、armeabi-v7a；NDK/CMake 版本在根版本目录。
- 不依赖 UI、相机、WKQ、MediaPipe 或直播引擎。`api` 依赖 lib_beauty，供公开 BeautyRenderer/TextureFrame 类型使用。

## 调用与所有权

```kotlin
// 在宿主当前 EGL context/处理线程创建，之后始终在此 context/thread 调用。
val renderer = GpuPixelBeautyRenderer(context.applicationContext)
val output = renderer.render(inputTextureFrame, settings, latestFace) as TextureFrame
// 在下一次 render/close 前消费 output；需要异步消费时由宿主复制并同步。
renderer.close()
```

输入接受 OES/2D。首阶段应用输入采样矩阵，输出保持原宽高、时间戳、旋转、镜像元数据，采样矩阵变为 identity。逻辑坐标沿用 lib_beauty 的原始帧左上原点；不额外旋转或镜像人脸坐标。

视频主链路为 `输入纹理 → 归一化 FBO → 水平/垂直 box blur → 高通差分 → 美颜/LUT → 可选局部贴妆 → 可选原有贴纸 → 可选美型 → 输出纹理`。不执行 glReadPixels，不逐帧上传像素。LUT 首次创建时解码上传，FBO 尺寸不变时复用；标准磨皮美白五次 native 绘制，只美白两次，贴纸另计。

上下文丢失后调用 `abandonAfterContextLoss()` 仅释放 native 元数据，GL 资源由旧 EGL 回收；此时禁止普通 close 删除新上下文的同号资源。Demo 已分别处理正常 close 和上下文丢失。宿主需保证旧渲染已停止，不能并发 render/close/abandon。

## 参数与限制

- smoothing、whitening 对外均 0..1，传入上游 blurAlpha/whiten；不同后端同一数值不保证同一视觉强度。
- 保留输入 alpha；扩展锐化由独立合并调色Shader执行，美白使用上游 LUT。上游白化分支包含固定调色步骤，0 和非零强度的差异不保证线性，需视觉评估。
- 保留原有暖冷色调、PNG/程序化贴纸，顺序在美颜之后，不重复磨皮、美白。
- 没有新皮肤分割模型、姿态跟踪或遮挡。本模块不会解决异步关键点的滞后。
- 五张全分辨率 RGBA 中间纹理理论占用 `width × height × 20` 字节（640×480 约 5.86 MiB），另有 LUT、驱动开销和贴纸输出，不能把此值当进程内存。
- 不创建/切换 EGL 上下文，不接管宿主纹理；保存恢复本模块触及的 GL 状态。共享上下文/异步交回仍须引擎适配层处理同步。
- Apache-2.0 许可证及适配声明随 assets 一起打包；没有引入上游第三方检测模型或预编译依赖。

## 验证范围

本地构建、打包、ELF 检查以 `doc/testing/TEST_REPORT.md` 为准。Pixel 8 设备夹具已验证基础及扩展效果改变、alpha、矩阵、纹理复用、2D/OES 与 GL 状态；页面联动测试通过。夹具测试不代表真人画质验收。OES 真机画质、横竖屏、生命周期、持续性能、Zego/声网和远端结果仍未验收。GL 提交时间不是 GPU 执行时间。


## 扩展调色与美型

公共BeautySettings.adjustments使用BeautyAdjustments：rosy 0..1、brightness -1..1（实际RGB偏移最大±0.25）、contrast/saturation 0..2且1中性、sharpening/slimFace/bigEyes/lipstick/blusher 0..1。中性参数无额外pass；基础CPU/GL渲染器明确拒绝非中性扩展参数，接入方选择本渲染器。调色不触发检测；美型requiresFaceAnalysis=true，沿用Session有效快照，过期/缺少478点直接跳过，不能以框或旧坐标冒充有效五官。

新增代码为BeautySDK适配：调色/邻域锐化合为一pass；红润用简单肤色倾向权重，不是局部腮红/皮肤分割。瘦脸/大眼采用上游curveWarp/enlargeEye公式（缩小参数幅度），9组MediaPipe下颌/目标点和2组眼睛圆域；原文件及哈希保留于third_party，仍按Apache-2.0随包声明。映射为实验性2D美型，不等于上游106点识别效果，也没有3D遮挡或侧脸透视。

顺序：归一化 → 可选磨皮/美白 → 可选调色/锐化 → 可选口红/腮红 → 原有贴纸合成 → 可选美型。美型最后让贴纸与底图一起形变，避免坐标错位，但贴纸图案也可能被拉伸；美型不是人脸追踪算法，不承诺改善快速运动。画面坐标来自输入FrameInfo/transform；距离按真实宽高比计算，与CameraX无关，可由公共EnginePipeline接入2D/OES。

已有5个FBO槽复用，不增加全分辨率纹理数量；美型复用已消费的均值槽，调色复用水平blur槽。只调色2次native绘制，只有有效人脸美型1次（OES可直接采样）；磨皮/美白原5次上另加调色1次、美型1次，贴纸另计。开启更多效果有GPU开销，未做持续性能或热稳态验收。无逐帧Bitmap、无glReadPixels、无新检测模型、无新EGL线程。

```kotlin
val renderer = GpuPixelBeautyRenderer(context)
val pipeline = BeautyEnginePipeline(factory=mediaPipeFactory, renderer=renderer)
pipeline.settings = BeautySettings(smoothing=.3f, whitening=.2f,
    adjustments=BeautyAdjustments(rosy=.2f, contrast=1.05f, saturation=1.05f,
        sharpening=.15f, slimFace=.2f, bigEyes=.2f, lipstick=.3f, blusher=.4f))
// 在正确EGL线程走既有Connector；异步输出消费仍需租约与GPU同步。
```

Demo当前固定使用MediaPipe+GPUPixel，隐藏基础OpenGL选项。效果面板默认展示预设；微调由完整资源库JSON提供名称、范围和默认程度。基础OpenGL仅用于SDK内部对照。

## 局部口红与腮红

`BeautyAdjustments.lipstick`、`blusher` 为0..1，默认0。`hasMakeup` 与 `hasReshape` 独立，只有贴妆时不执行无效美型。仅开启贴妆也会令 `requiresFaceAnalysis=true`，经公共Session的有效快照门控；直接使用renderer时，宿主负责传入当前有效的原始帧坐标观测。

- 口红：MediaPipe官方完整外唇20点＋内唇20点控制覆盖。环带三角网格排除唇外/口腔，Shader仅计算本段内外两条边的距离，边缘向内羽化1个输出像素；RGB uniform直接提供唇色，保留原唇明度，不读取口红PNG或材质UV。闭嘴内环退化时保留窄唇缝。
- 腮红：固定上游 blusher.png 左右半图只作覆盖蒙版，分别绑定50/280号脸颊。双眼连线确定方向，鼻点1、外缘234/454及下巴152限制各侧内外宽度和上下高度；单侧脸颊无效时清掉该侧顶点。仍是局部四边形近似，不是稠密皮肤网格或三维遮挡。
- 两项共用一次背景复制和局部网格绘制（最多再2次draw），复用差分FBO槽3；不增加全分辨率FBO。复用贴妆数组前288个float和native VBO（睫毛另占后续槽），逐帧只上传约1.1KiB几何数据。无glReadPixels、逐帧Bitmap或新检测器；腮红贴图只初始化时解码/上传，口红没有图片解码/纹理上传。
- 混合保留原输入alpha，支持2D/OES与输入采样矩阵；贴妆在贴纸/美型之前，后续统一变形。缺少478点、唇部越界或退化时只旁路该区域，不用框猜五官。
- 当前口红为内置玫红RGB颜色，腮红使用默认玫瑰血色及固定分布蒙版，没有自定义色号、遮挡分割或3D侧脸补偿。红润rosy仍是全图调色，不等于局部腮红。
- **资源协议边界**：旧V2仍保持原字段；扩展美妆通过V3/V4及统一资源包交付，CMS已支持制作、Android已支持消费。主题编辑经composer拆分为子资源后，显式参数覆盖对应效果；iOS新增能力仍未完成Apple环境验收。

设备验证见固定测试报告；生成纹理/公开人像夹具不能替代真人侧脸、张嘴、遮挡或热稳态画质/性能验收。

## 自定义扩展美型

除上游公式适配的瘦脸/大眼外，追加 **BeautySDK自定义** 三项二维局部形变：

| 参数 | 范围/中性值 | 行为 |
| --- | --- | --- |
| `slimNose` | 0..1，0关闭 | 沿脸部横轴局部收窄鼻部 |
| `chinLength` | -1..1，0关闭 | 负值缩短下巴，正值拉长 |
| `mouthSize` | -1..1，0关闭 | 负值缩小嘴部，正值放大 |

区域由MediaPipe鼻翼98/327、鼻尖1、鼻梁168、下巴152、嘴角61/291及唇中13/14构建。像素等距坐标中以鼻梁→下巴确定脸部方向，椭圆区域采用紧支撑羽化；实际原始纹理旋转与镜像不靠CameraX特判。区域缺点/退化分别关闭，不因鼻部缺点让有效的下巴一起失效。超过Session有效期的结果仍遵守既有人脸快照门控。

三项与瘦脸/大眼合并在同一次美型采样，复用槽2；不增加draw次数、全尺寸FBO、检测器或逐帧图像转换。原有美型独立开启时跳过扩展区域计算，参数全0时不进入形变pass。复用56浮点几何数组与5浮点强度数组。它们不是GPUPixel上游原生产品能力，不声称3D骨骼、遮挡处理、真人自然度或持续性能已验收。

```kotlin
val settings = BeautySettings(adjustments=BeautyAdjustments(
    slimFace=.15f, bigEyes=.1f,
    slimNose=.25f, chinLength=-.15f, mouthSize=.1f,
    lipstick=.3f, blusher=.4f
))
// 使用现有BeautySession/EnginePipeline处理宿主纹理；需要MediaPipe稠密关键点。
```

Demo选择GPUPixel后，参数选择器追加「瘦鼻（扩展）」「下巴长短（扩展）」「嘴部大小（扩展）」。下巴/嘴部滑杆显示-100..100，0为中性，重置归0。当前V2资源包仍仅支持原七项：本节三项与上一节口红/腮红仅通过SDK API与Demo配置，Web/iOS资源链路未扩展。


2026-10-27用户最新入口要求：Demo隐藏基础OpenGL/后端选择控件，默认GPUPixel，扩展参数直接可见。此前默认基础OpenGL的描述仅为历史约定；SDK仍保留基础实现及对照测试。


## 2026-10-27：内置程序化睫毛与贴妆融合修正

`BeautyAdjustments(eyelashes=.65f)`启用自然款上睫毛。每眼8根、每根3段细带跟随MediaPipe上眼睑；眉部决定外伸方向，开合眼调节长度而非整体压扁。两眼分别校验，缺点/过小/无人脸旁路。复用贴妆FBO和VBO，顶点数组1440 floats（5.625KiB），增加一次局部draw，不增加检测模型、全尺寸FBO或逐帧Bitmap。Shader使用导数平滑细带边缘，保持原视频alpha。美型在贴妆后统一执行。

Demo路径：相机实验室→底部设置→美妆→睫毛。0..100调节强度，0关闭；公共接口可用于直播引擎纹理帧，不依赖相机采集实现。

本次是程序化内置款，没有导入外部PNG睫毛、Web制作器/资源包协议扩展或iOS实现；V1/V2/V3资源包保持原定义，不能导出此参数。没有手/头发遮挡分割、3D侧面睫毛或经过真人自然度验收；眨眼联动为几何近似。

同轮口红改为保留原唇明度的染色，腮红采用柔光/椭圆衰减并按每侧鼻颊距离限制宽度；瘦脸/大眼影响边界使用平滑曲线。没有增加推理或GPU同步等待，不能由此推断持续性能或商业效果达标。


### 睫毛自然度与无效开销修正

开合眼改为眼轴法向投影并使用平滑响应，水平点位漂移不再增加开眼程度。每根睫毛混合局部眼睑法线、采用独立长度分布，避免平行等长排列；保持低分辨率像素宽度下限。上述几何在原始帧像素空间计算，单元测试覆盖非方形旋转/镜像、闭眼水平漂移和复用数组清理。单开睫毛跳过唇/颊网格生成；关闭睫毛时JNI与VBO只传原288浮点妆面，不传1440全量。无新模型或全尺寸FBO，没有新增平滑队列、GPU等待或逐帧图片。侧脸主要随投影眼宽缩放，尚无深度遮挡或真人自然度保证。


## 后续补齐：V4图片睫毛资源

上述“没有外部PNG/Web/iOS”的表述仅记录此前阶段。现在BeautySettings.eyeMakeup可消费透明PNG与9点根部，覆盖程序化内置款；三款样例、Web编辑/主题导出、Android内置/导入/网络资源链路已补齐，iOS有Metal源码但Windows未编译验证。换素材时上传并生成mipmap，逐帧仅更新复用网格，复用贴妆FBO；Pixel8相关13项通过。详见 resources/effects/v4/README.md 和最新测试报告。


## 2026-10-30：中间纹理与可选GPU诊断

全尺寸RGBA目标按首次使用分配：新实例仅美白/调色两张、磨皮四张；完整美妆可到五张。尺寸相同复用，关闭某个效果不立即释放已缓存目标。`intermediateTextureBytes()`仅统计本实例缓存中间RGBA像素字节，不含LUT、其他渲染器/模型和驱动开销。组合配置按不可变设置实例缓存，参数变更立即更新。

```kotlin
// 均在创建Renderer的GL线程和同一当前EGL上下文调用。
val supported = renderer.setGpuTimingEnabled(true) // 默认关闭；扩展/计数位不支持时false
val output = renderer.render(frame, settings, face)
val gpuNs: Long? = renderer.latestGpuExecutionNs() // 最近已完成结果；未完成/disjoint时null
val disjointEvents = renderer.gpuTimingDisjointCount()
val intermediateBytes = renderer.intermediateTextureBytes()
renderer.setGpuTimingEnabled(false)
```

计时使用四槽EXT查询，只轮询已完成项；不glFinish、不主动flush、不等待，遇宿主同类活动查询跳过。GPU休眠/频率变化造成的disjoint会丢弃结果并计数。时间仅涵盖本Renderer，不含外层背景/3D、模型、显示与编码，不回填为核心Pipeline全链路GPU数据。实测与限制见`doc/testing/PERFORMANCE_OPTIMIZATION_20261030.md`：纹理字节量下降及像素一致性通过，耗时短测有升有降，不代表持续帧率提升。


### 2026-10-30 磨皮采样尺度

默认磨皮的均值支路以输出短边480为原有基准，采样间距=4×min(width,height)/480像素。640×480保持原间距；同视场升/降分辨率时保持相对邻域，横竖屏交换宽高不改变半径。不依赖独立检测小图大小，不增加绘制或FBO。新版720p/1080p磨皮输出会与旧版固定4像素间距的摘要不同，前轮摘要仅属于旧实现，不能沿用为新版像素基线。锐化仍为像素细节操作；合成纹理一致性不等同于真人、不同视场或不同人脸占比的完全一致观感。


### 2026-10-31 完整唇轮廓

口红内部打包布局保持1440 floats总容量与脸颊/睫毛偏移：前24 floats保留旧包围框布局（新绘制不消费），24..103为40点归一化轮廓；脸颊仍从240开始，睫毛从288开始。JNI签名、资源格式、公共参数不变。顶点Shader以gl_VertexID生成120个环带顶点，片元最多两条局部边缘距离，替换上一版40边遍历；不增加VBO容量、FBO或纹理读回。OPPO合成夹具短测见最新TEST_REPORT，尚无持续热稳态性能结论。当前仍依赖模型点精度，不包含专用唇部分割、遮挡估计或人工标注真人唇缘准确率验收。


### 2026-10-31 程序化口红直接染色

口红在完整40点环带网格内由`lipColor` RGB uniform直接着色，目前默认(.90,.32,.38)，原0..1强度接口继续生效；取原视频亮度保留唇纹/明暗，内侧1像素羽化、口腔留空及视频alpha保留。不读取口红材质纹理、不使用口红UV、不截图。构造时仅上传四张LUT和一张腮红图，JNI内部同步为5缓冲/10尺寸。`mouth.png`移至third_party/gpupixel/src/res，仅作来源核对，不打进AAR/APK；导入/验证脚本同步维护。现有睫毛和腮红仍可使用各自纹理。

本轮没有新增色号选择UI或公共颜色API，没有新模型/唇部分割/跟踪算法；由关键点决定的边界和异步时序限制仍在，不能承诺直接RGB着色自动解决快速侧脸。验证见最新TEST_REPORT。


### 2026-10-31 图片睫毛缩小可见度

图片睫毛仍使用原眼睑网格和mipmap；只在纹理缩小时按UV足迹对细毛alpha作有界不透明度补偿，避免小脸时细线平均后近乎不可见。透明底保持0，原尺寸/放大不增益，输入视频alpha不变；口红直接染色和跟踪不受此分支影响。新增眼周强变化断言与APK真实三款夹具通过，真人自然度、遮挡与持续GPU开销仍待验收，详见TEST_REPORT。


### 2026-10-31 美妆局部叠加隔离

口红仍为40点直接RGB染色，图片/程序化睫毛仍为独立眼睑网格。共用固定输出FBO/VBO，但各局部draw输出目标颜色及覆盖率，以SRC_ALPHA/ONE_MINUS_SRC_ALPHA叠加RGB，ZERO/ONE保留原视频alpha；透明网格不再把先前妆面写回原图。腮红→口红→睫毛的顺序明确，颜色目标仍从美妆前视频取明暗，不在输出纹理上反馈采样。2D/OES共用此逻辑，无新全尺寸FBO、读回或模型；混合因子/方程仅在美妆调用保存并恢复。实时性能及真人眨眼/侧脸质量仍需独立验收，未增加眼睑局部跟踪。


### 2026-10-31 睫毛闪烁诊断

Debug宿主每秒聚合BeautyEyelash请求/无可用face/缺点/无效双眼/提交次数与像素眼宽范围，不记录坐标或图像。无可用face同时包含无脸/过期/匹配屏障等上游情况，不能直接解释为模型NO_FACE。三款交付图片的16步亚像素位移夹具逐帧检查可见像素；并未增加图像缓存或改alpha算法。公共TrackedFaceAnalysis修复辅助匹配失败否决更新真实模型的问题，原始有效期/时间戳及旧位置失败屏障保留。真人复测及持续性能以最新报告为准。

## 2026-10-31 腮红保色与区域约束

旧浅粉白底RGB柔光会额外提亮皮肤；当前素材RGB只用于提取分布覆盖率。固定玫瑰色差在(.2126,.7152,.0722)加权RGB亮度下为零，并按原像素色域余量共同缩小，避免分别裁剪通道引起明暗变化。该权重应用在现有编码RGB空间，不声称物理线性光照或肤色分割。强度仍为0..1，色号API/资源协议不变；仍保留素材纹理的初始化与采样，无新模型、全屏pass、FBO、读回或逐帧分配。

位置按每侧鼻颊、外缘和眼颊/颊下巴投影距离收敛。仍使用每侧两个三角形，不能当作完整曲面网格、侧脸遮挡、手/发丝分割；转头和真人自然度待验收。具体JVM/设备、原始失败及静态native对齐记录见固定测试报告。

## 2026-10-31 腮红妆形展开

在前轮保留加权RGB亮度染色的基础上，内外宽度分别放宽至鼻/外缘约束内，上下厚度收窄；轮廓侧额外向上抬起，并限制在眼颊间距内。沿颧骨方向铺开而非圆形集中。素材色差改为平方根分布，保留更低覆盖率的柔边，椭圆衰减平缓区扩大，峰值覆盖从0.70降为0.48；不改变用户保存的强度或资源。

仍复用每侧两个三角形、固定缓冲与原贴妆FBO，不增加模型、pass、纹理读回或平滑等待。实际Pixel8控制纹理颜色变化分布的主轴比例由1.32变为2.16；这是形状测量，不是自然度评分。公开人像满强度对照已查看，真人脸型、转头遮挡与持续直播效果仍待验收。
