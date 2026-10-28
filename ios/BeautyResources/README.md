# iOS 静态贴纸资源组件

用Xcode添加本目录为本地Swift Package，iOS13+。这是资源加载、几何和Metal贴纸合成组件，不是完整iOS美颜/MediaPipe/直播SDK。ZIPFoundation固定0.9.19，MIT，源码/许可：https://github.com/weichsel/ZIPFoundation/tree/0.9.19 。首次解析依赖需要网络。

```swift
// 后台IO队列，不在视频回调里读ZIP。
let pack = try StickerResourcePack.load(url: bundleURL)
// 回到宿主串行Metal处理队列，一次性上传，失败保留旧资源。
let renderer = MetalStickerRenderer(device: device)
try renderer.setPack(pack)
// 每帧：宿主检测器将眼/嘴语义点转换成原始帧像素；缺失或过期则不给pose。
let eyePose = StickerPose.from(left: leftEyeRawPixels, right: rightEyeRawPixels)
var poses: [StickerAnchor: StickerPose] = [:]
poses[.eyes] = eyePose
poses[.upperLip] = StickerPose.from(left: leftMouthRawPixels, right: rightMouthRawPixels, center: upperLipRawPixels)
try renderer.encode(input: inputTexture, output: outputTexture,
                    commandBuffer: commandBuffer, poses: poses, mirrored: mirrored)
// 宿主提交commandBuffer；完成/同步后再把output交还引擎。
```

输入/输出为独立同尺寸同格式MTLTexture（RGBA8Unorm/BGRA8Unorm，非sRGB），输出usage含renderTarget，宿主拥有纹理/commandBuffer/队列及同步责任。输出预乘alpha；直播不透明帧alpha=1。不同设备纹理不可混用；CVPixelBuffer可用CVMetalTextureCache桥接，由宿主保留至GPU完成，不使用UIImage逐帧转换。Metal与OpenGL纹理ID不互通。

资源包列表顺序叠加，缺锚点隐藏层。停止时宿主清除pose、串行调用setPack(nil)，在途commandBuffer完成后释放自己的输入/输出。资源加载不持有相机、不做检测和过期管理，不保存人脸。

测试：Apple开发环境执行`swift test`；iOS宿主还需对六样例、旋转镜像、alpha边缘、GPU生命周期做真机验收。当前Windows环境无法运行Swift/Metal，因此这些代码和XCTest不记为编译/运行通过。共同几何夹具与Android/网页相同。


## 场景资源 V2

新增 `EffectResourcePack` 严格加载 BACKGROUND/MOTION/BEAUTY，`MotionTimeline`执行关键帧；`MetalSceneRenderer`消费正向RGBA8纹理及宿主提供的有效分割mask。通过sceneOnly → StickerRenderer → motionOnly三pass组合，输出纹理/commandBuffer由宿主管理。美型slimFace/bigEyes非零明确拒绝，交宿主美型后端读取config处理。基础美颜实现与Android GPUPixel不保证同样视觉结果。详见 ../../resources/effects/v2/README.md。新增Swift代码和XCTest尚未在Apple环境编译或运行。

## 完整主题

新增 BeautyThemePack 与 MetalThemeRenderer；协议及宿主使用约定见 ../../resources/themes/v1/README.md。先加载与检查全部能力，再在串行渲染队列准备新纹理并整体切换。Windows未编译Swift/Metal，新增ThemeResourceTests须在Xcode/Swift Package环境执行。

## V3 美颜资源

解析器兼容 V2 和 V3 BEAUTY。V3 新增口红、腮红、瘦鼻、下巴与嘴型参数，保留有符号数值。当前 MetalSceneRenderer 对非零美型与美妆明确拒绝，不会忽略字段后显示成功；需另接具备相同语义的渲染实现。Windows 环境没有执行 Swift 编译或 Apple 真机验证。

## V4 图片睫毛

`EffectResourcePack`读取相同BEAUTY V4 ZIP；`MetalEyeMakeupRenderer.setPack`一次上传透明PNG并预生成缩小级别，每帧`encode`接收原始纹理归一化坐标的478点（0…1，左上角原点）。`MetalThemeRenderer.encode`新增可选`landmarks`参数，顺序为场景→图片睫毛→贴纸→运镜。有效点不足对应眼旁路，宿主负责检测时效；保留输入alpha。资源关闭调用setPack(nil)。

```swift
let pack = try EffectResourcePack.load(url: url)
let lashes = MetalEyeMakeupRenderer(device: device)
try lashes.setPack(pack)
// 在宿主串行Metal队列调用；输入/输出独立、同尺寸/格式，宿主提交与同步。
try lashes.encode(input: inputTexture, output: outputTexture,
                  landmarks: rawNormalizedLandmarks, commandBuffer: commandBuffer)
```

新增EyeMakeupTests覆盖三个Web样例、全量跨平台几何向量和可用Metal设备上的绘制/alpha断言。Windows无Swift/Xcode，本轮未编译或执行这些测试；必须在Apple开发环境运行swift test后再接入生产。其他V3非零美型、口红、腮红仍明确拒绝，不能将新增睫毛能力视作完整iOS美颜。制作说明见 resources/effects/v4/README.md。


## 统一资源V1

新增UnifiedResource/UnifiedResourceManager/MetalResourceRenderer及17个共享示例XCTest。支持元数据、翻译、默认/保存状态、安装摘要、目录缓存、套装拆分、引用保护及Metal组合桥；下载由宿主注入受限HTTPS传输。

Windows未编译，必须在Apple环境运行swift test/宿主Metal验收。Metal执行器尚不支持美型、口红、腮红及无图片程序化睫毛，会在配置切换前拒绝；与Android的解析协议相同不等于执行能力相同。详见../../resources/unified/v1/README.md。
