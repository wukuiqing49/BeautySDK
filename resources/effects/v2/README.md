# 场景与美颜资源 V2

贴纸继续使用 `resources/stickers/v1`，V2独立描述 BACKGROUND / MOTION / BEAUTY。网页工具： https://beautystickerworkshop.pages.dev/effects.html。

ZIP只含根目录 manifest.json、preview.png及引用的静态PNG，无脚本、路径或网络URL。总文件16MiB、单图4MiB、最长边2048、总像素4194304、配置64KiB。原图保留，导入时解码一次；禁止逐帧加载。各类型完整字段见JSON Schema；运行时还校验图片CRC、引用、数值、首帧和循环闭合。

- BACKGROUND：mode=BLUR/COLOR/IMAGE。IMAGE必须有image文件名，其他模式image=null。color=#RRGGBB、blurRadius=0..32、focus=[x,y]为图片归一化焦点，按显示比例cover裁切，焦点自动夹取到合法范围。
- MOTION：mode=PUSH_IN/PAN/FACE_FOLLOW/KEYFRAMES；zoom=1..2、periodMs=整数1000..60000。只有KEYFRAMES允许timeline，其他为null。timeline.loop为布尔、frames为2..32帧，timeMs整数0..60000、严格递增且首帧0。循环首尾x/y/zoom必须相同。x/y=0..1正向显示中心（x向右、y向下，左上为0/0）；easing=LINEAR/SMOOTH描述到下一帧的插值，SMOOTH为u²(3−2u)。时间轴以末帧为时长，忽略基础periodMs/zoom；非循环保持末帧。输出宽高不变，不执行真实镜头控制。
- BEAUTY：smoothing/whitening=0..1，filter=NONE/WARM/COOL；adjustments为完整参数。brightness=-1..1、contrast/saturation=0..2（1中性），其余0..1。Android扩展参数需要GPUPixel，美型需要MediaPipe。参数语义可跨端，渲染器视觉结果不保证相同。

## Android

在IO线程 `EffectResourcePack.loadZip(input)`，配置变化时 `pack.applyTo(baseSettings)`；只替换该类别。多个类型可依次apply，顺序不影响互不相干类别。场景必须使用BeautySceneRenderer组合；背景需ForegroundMaskProvider，并按scene.needsSegmentation调度分割。美型按requiresFaceAnalysis调度。库不绑定相机或直播引擎。`requiresExtendedBeauty`供宿主选择后端，不能静默丢弃不支持的参数。

Demo工坊提供效果ZIP导入/清除，分类AtomicFile保存并下次恢复。导入预设优先于对应手动控件；清除后回到手动配置。旧贴纸不受影响。坏包保留旧选择。

## iOS

Swift包提供EffectResourcePack、MotionTimeline、MetalSceneRenderer。加载在IO队列，setPack/encode在同一渲染队列。Metal输入为已旋转镜像的正向RGBA8纹理，输出同尺寸独立shaderWrite纹理。宿主负责有效R8分割mask、时间戳和人脸中心；没有mask则保留真实背景。组合贴纸时使用三个独立pass：`encode(stage:.sceneOnly)`处理背景/美颜 → MetalStickerRenderer合成 → `encode(stage:.motionOnly)`裁切整个结果。每个pass输入输出纹理不同，按commandBuffer顺序执行，避免贴纸与底图裁切不同步。不组合贴纸可直接stage:.all。

iOS Metal实现基础磨皮/美白/调色和背景/运镜，不含分割模型或人脸检测；slimFace/bigEyes非零时setPack明确失败，宿主可读取config交由自己的美型后端。Windows未执行Swift编译/Metal验证，不视为完整iOS美颜SDK。

## 验证

网页正式打包器生成background/motion/beauty夹具，Android与Swift测试共用。真机视觉、持续分割性能及直播厂商写回尚需分别验收。
