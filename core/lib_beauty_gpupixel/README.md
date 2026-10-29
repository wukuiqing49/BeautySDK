# GPUPixel 美颜适配库

本模块复用 GPUPixel 的美颜 Shader、radius=4 box blur、高通差分和四张 LUT，使用独立 C++/JNI 纹理适配器接入 BeautySDK。**不是完整 GPUPixel 运行时的二进制依赖**，不包含其独立 EGL 线程、人脸检测器、模型。局部口红/腮红使用同一固定提交的贴图与multiply混合，网格由本适配器映射MediaPipe478点。新增美型借鉴其固定版本曲线形变公式，显式映射MediaPipe478点，并非直接套用原106点下标。

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

- 口红：固定上游 mouth.png、规范脸UV和20个唇环三角形，显式映射20个MediaPipe语义点，排除口腔内部；不是嘴部矩形涂色。
- 腮红：固定上游 blusher.png 左右半图，分别绑定50/280号脸颊，以双眼连线、下巴和实际像素宽高构建局部四边形。这是适配网格，不是上游106点全脸网格的直接移植。
- 两项共用一次背景复制和局部网格绘制（最多再2次draw），复用差分FBO槽3；不增加全分辨率FBO。固定288个float顶点数组和native VBO复用，逐帧只上传约1.1KiB几何数据。无glReadPixels、逐帧Bitmap或新检测器；两张小贴图只初始化时解码/上传。
- 混合保留原输入alpha，支持2D/OES与输入采样矩阵；贴妆在贴纸/美型之前，后续统一变形。缺少478点、唇部越界或退化时只旁路该区域，不用框猜五官。
- 当前只有上游默认红唇、粉色腮红两种内置贴图，没有自定义色号、遮挡分割或3D侧脸补偿。红润rosy仍是全图调色，不等于局部腮红。
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
