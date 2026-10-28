# 虚拟背景与数字运镜

公共入口是 `BeautySettings.scene` 与 `BeautySceneRenderer`；位于独立 `core/lib_beauty`，不依赖相机、厂商引擎或GPUPixel。分割由可选 `core/lib_beauty_mediapipe` 的 `MediaPipeForegroundMasks` 提供。基础GL和GPUPixel均可作为delegate。两项功能默认关闭，不修改贴纸跟随算法。

## 流程与生命周期

原始帧 → 独立异步人像分割；渲染线程：有效蒙版背景合成 → delegate美颜/美型/贴纸 → 最后数字运镜 → 输出2D纹理。输出分辨率、rotation/mirrored不变，transform采样后为identity；背景图片先按显示方向居中裁切，再与原始坐标蒙版合成。后续引擎按原元数据展示，不重复旋转。运镜是统一裁切与放大，不是物理相机移动，也不生成不存在的画面。

分割输入支持借用RGBA和小RGB图。一个处理槽和一个可替换的最新待处理槽，不积压、不等待推理，不保留借用缓冲；Direct RGBA在提交回调内通过现有C++融合缩小/旋转/镜像/RGB行对齐，最大边256，worker推理并把mask逆变换回原始空间。普通堆缓冲保留兼容转换。没有逐帧Bitmap。固定本地SelfieSegmenter模型249537字节，SHA256和来源随assets打包；本模型单sigmoid通道表示person，不能照搬其他双分类模型的通道索引。

mask采用小R8纹理，对象变化才上传；GPU背景合成1个全分辨率输出FBO，运镜另外1个，需要时才创建，不因配置滑动逐帧重新分配。背景图片仅对象变化时上传。BLUR为25采样近似虚化，不是人像光学景深，新增全分辨率GPU采样有成本。所有效果叠加尚未经过持续性能或热稳态验收。

蒙版有效期250ms（视频采样时间），默认严格拒绝尺寸/旋转/镜像不一致、未来和过期的结果。宿主保证同一裁剪视场时可显式设置`MediaPipeForegroundMasks.allowScaledFrames=true`，允许等宽高比小图映射高清输出；保留真实采样时间，不拉伸mask或延长有效期，缓存同一映射对象避免重复上传。CameraX Demo通过同一ViewPort提供分析/输出裁剪，因此显式启用；不同crop或留边不能使用此开关。没有延长人脸有效期。分割和人脸检测各有worker，可能竞争CPU；头发细节、快速运动边缘、低光和遮挡画质仍需真人验收。首次/过期时恢复原背景，不能当作隐私遮挡保证。输入alpha保留，背景RGB视为不透明，图片alpha忽略。

## 接入示例

```kotlin
// GL线程、有current ES3上下文；masks本身可在宿主线程创建。
val masks = MediaPipeForegroundMasks(context) { error -> /* 宿主诊断，不保存人脸 */ }
masks.setEnabled(true)
val renderer = BeautySceneRenderer(GlBeautyRenderer(), masks)
// 可替换为GpuPixelBeautyRenderer(context)，分析仍复用原MediaPipe人脸运行时。
val pipeline = BeautyEnginePipeline(renderer=renderer, factory=faceFactory)
pipeline.settings = BeautySettings(scene=BeautyScene(
    background=BackgroundMode.BLUR,
    motion=CameraMotion.FACE_FOLLOW,
    zoom=1.25f))
// 原始、同视场RGBA分析回调内，借用缓冲返回前完成复制；忙时替换最新待处理槽，相同/倒退时间戳拒绝，换时间线需reset。
masks.submit(rgbaFrame)
// 原始纹理交给现有Connector/Pipeline；输出按既有租约/同步规则归还引擎。
```

只有纹理的宿主可在GL线程创建 `GlForegroundMaskBridge(masks)`，在美颜前调用 `submit(rawTexture, scene.needsSegmentation)`。它复用公共双PBO桥，GPU缩至256边长、66.7ms采样间隔，查询fence不主动等待，只读回小RGBA；不是零拷贝，不承诺驱动调用无阻塞。已有RGBA时不用这个桥；同时开人脸与分割的纯纹理输入目前可能各有一次小图读回，尚未合并共享采样。

同尺寸换源、裁剪变化、重启需同时 `masks.reset()`、`renderer.reset()` 和引擎pipeline/resetSource；有桥时调用其reset。停用背景调用 `masks.setEnabled(false)`。停止提交后，GL线程close桥和renderer，宿主close masks；close不等待推理，模型在所属worker顺序释放。BeautySceneRenderer拥有并关闭delegate，masks由宿主另行拥有。GL上下文丢失后禁止在新context close旧对象，应丢弃旧Java GL实例并新建，旧GL资源由EGL回收。

直接把非中性scene传给基础CPU/GL/GPUPixel渲染器会明确拒绝，必须用组合器，避免顺序错误或假生效。库只支持纹理场景合成，尚无CPU场景合成器。没有新增厂商SDK适配、编码、推流或跨context同步。

## 参数与Demo

背景：NONE / BLUR / COLOR / IMAGE；IMAGE传入已解码不可变StickerImage，按显示方向center-crop。运镜：NONE / PUSH_IN（周期推近复位）/ PAN（显示空间水平摇移）/ FACE_FOLLOW（人脸框中心有限范围跟随）。倍率1..2，周期1000..60000ms；均匀缩放保持比例，输出尺寸不变。跟随中心180ms指数平滑，缺少有效脸逐渐回中，不沿用无限期旧框。该平滑只作用于整幅运镜，不改贴纸定位。

相机 → 美颜弹框 → 背景/运镜下拉框与倍率滑杆；自动居中自动选MediaPipe。选原图停止分割与人脸分析；美颜总开关只控制美颜，场景控件独立。重置同时关闭背景与运镜。示例图片为代码生成渐变山景，初始化一次，不是相机帧Bitmap转换。

官方参考：[Android Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android)、[模型卡](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Selfie%20Segmentation.pdf)。本轮测试结论见TEST_REPORT，不以官方基准推断本项目实时性能。

## 2026-10-27 Demo入口收敛

首页资源中心是独立ResourceCenterActivity，不创建相机或模型；本地导入、HTTPS目录配置/下载、删除、默认配置在此管理。下载或导入仅安装，点击已安装卡片才选择效果。HTTPS目录地址显式配置并记住，未自动发布在线目录。

相机主画面默认显示效果，小窗显示原图，点击对比可交换。底部八分类只提供参数和已安装效果，参数先于素材；背景/运镜有独立控件，统一提交ResourceCenterModel生成最终BeautySettings。重置只影响当前分类；素材时间线的倍率由关键帧控制，单独倍率滑杆禁用。手动场景调整保留在当前Activity的ViewModel会话中，重新创建进程后恢复已保存的资源选择与美颜参数，不宣称手动场景参数已持久化。

背景页显示分割等待/就绪状态；无有效蒙版继续旁路。人脸分析异常不再锁死独立美颜、背景和运镜。实际覆盖与限制见固定测试报告。

## 2026-10-28 Android轮廓合成调整

不确定蒙版区使用原图颜色引导的四邻域重建（按蒙版像素中心），软过渡0.35～0.70，完整前景直接返回；背景虚化对可信背景采样加权，降低人物颜色向轮廓外扩散。没有增加Bitmap、读回、额外FBO或延长蒙版有效期。此为Android公共场景合成策略；iOS尚未同步。新增颜色/蒙版采样有成本，未作持续GPU计时；真实细发丝、低对比度、分割漏检和旧蒙版拖影不能保证解决。设备像素断言和模型回归见固定测试报告最新段落。

## 2026-10-28 Demo配置记忆

美颜/美型/美妆参数、启用状态和资源选择继续使用公共`BeautyResourceStateStore`。宿主新增`LabSceneStore`保存手动背景与运镜覆盖：模式、颜色、图片、虚化半径、焦点、倍率、周期与关键帧。存档在私有`beauty_resources/scene-state.json`，通过AtomicFile替换；背景资源图按SHA-256保存到`scene-images`，变化时才编码，滑杆更新复用图片。不是摄像头帧保存，也不改变资源包协议。

场景存档绑定对应类别与主题的资源选择；资源页修改该类别后旧覆盖失效，其他类别仍恢复。选择新主题、重置和关闭操作也更新存档，显式NONE同样记忆。所有ResourceCenterModel共用进程级串行IO队列，旧页面已接受的保存先完成，新页面再读取；退出页面不跳过已接受操作，UI结果仍受关闭/代次检查保护。异常强杀恰好发生在异步保存前、磁盘失败时不能保证最后一次操作落盘，错误会交给页面显示；没有把用户偏好写进SDK渲染器。

## 2026-10-28 动态背景分割延迟优化

`MediaPipeForegroundMasks`使用两个独占复用小图槽；推理读取active，采集回调只写pending，较新帧覆盖尚未推理的pending。reset/停用清除待处理帧、结果和时间线；旧代次任务不发布。RGBA复用MediaPipeRgbInput的原生转换及RGB四字节行对齐，RGB兼容入口复用转正数组，无逐帧Bitmap。以上适用于公共相机/引擎输入，不是相机专用分割器。

新增显式Execution.CPU/GPU/GPU_PREFERRED。公共库默认CPU；Demo使用GPU_PREFERRED，在worker初始化GPU失败时回退CPU，运行期推理错误仍上报，不承诺所有错误自动恢复。GPU仍有输入复制及置信度输出，不等于纹理零拷贝。onTiming提供复制、排队、准备（首次含模型创建）、推理、后处理和结果间隔；Demo Debug限频记录BeautySegmentation及即时BeautyMaskAge，不保存图像。

Pixel 8短测固定公开人像、各5次预热及20次采样，CPU/GPU推理中位数约89.54/42.26ms；顺序执行、温度及负载未控制，不是持续直播对比。最终Demo短日志确认GPU，某窗口推理均值16.32ms、结果间隔最大115.70ms，单点可用蒙版年龄41.60ms；不能据此宣称稳定30FPS或所有帧低延迟。蒙版仍保留真实采样时间和250ms有效期；没有位置预测、蒙版运动补偿或延迟整个视频。快速移动、细发丝、遮挡和持续温升仍待真人验收。

## 2026-10-28 蒙版原生后处理

分割输出改用NativeMaskConverter直接借用MediaPipe FLOAT32原生缓冲，一次C++循环完成有限值校验、0～255量化、逆旋转和逆镜像；非对齐切片通过memcpy安全读取浮点，不持有模型指针。目标DirectByteBuffer由分割worker独占复用，尺寸变化才重分配；非Direct来源保留批量拷贝兼容路径，无Kotlin逐像素转换。仍使用原始采样时间、250ms过期、最新pending与代次检查。

ForegroundMask新增ByteBuffer构造入口，按position..limit一次批量复制到独立不可变快照，不更改调用者游标；原ByteArray构造入口保持兼容。保留这一份结果复制，防止下一帧原生输出复用覆盖跨线程渲染中的旧蒙版，不宣称全链路零拷贝。没有改变GL坐标契约、模型、厂商依赖或上传人脸图像。

ForegroundMaskTiming增加extractionNs/conversionNs/packagingNs，分别覆盖结果提取、原生转换入口（含缓冲准备）和结果快照；原postprocessNs仍是合计。Demo Debug日志限频输出这些细分阶段。

同一Pixel8公开横移夹具短测，后处理均值44.72→4.46ms，完成间隔83.84→38.70ms，蒙版年龄中位137.44→66.67ms，P95 200.00→162.17ms。前后并非温控交替实验，不能推导其他机型或持续性能；局部切边仍可见，动态画质未验收通过。细分与设备证据见最新TEST_REPORT。

## 2026-10-28 软轮廓与运动补偿对照（实验未默认启用）

场景GL保留当前颜色引导的四邻域重建，将窄阈值改为0.08～0.92连续软过渡，降低剪纸边；不混合前后帧alpha，避免人为增加运动拖尾。同尺寸R8蒙版通过glTexSubImage2D更新，仅尺寸改变才重新分配纹理。公开人像已通过实际BeautySceneRenderer导出图检查；分割置信度不是精确发丝alpha，不能保证细发丝、遮挡和低对比度自然。

新增可选enableMotionAlignment，公共SDK与Demo最终均默认false。实验支路在分割worker复制最新同几何pending小图，在C++面积缩小到最大48、前景纹理块双向匹配、平移一致性校验；匹配失败不补偿，不根据人脸框移动整个人像。只匹配已采样的120ms内新图，不预测未来，不累积历史位移，不延长250ms有效期。匹配仅能处理近似整体平移，不能替代局部非刚性光流/视频matting。

ForegroundMask.info.timestampNs保持模型原采样时间；alignmentTimestampNs记录校正目标输入时刻，offsetX/offsetY为原始归一化空间位移。pixels()保留原始模型像素，消费方需按p-offset采样；BeautySceneRenderer已支持。原ByteArray/ByteBuffer调用继续兼容。forScaledOutput保留位移、校正时刻及真实采样年龄。若当前视频纹理早于校正小图，运行时使用同一次模型的withoutMotionAlignment只读共享快照，避免提前应用未来位移或闪回原背景；新模型结果尚未采样到时仍严格拒绝。

对照结果有波动，补偿有额外CPU成本，未稳定改善延迟，因此没有默认启用。最终一对短测模型结果年龄约66.66→99.97ms，校正目标画面年龄66.66→66.24ms，二值IoU约0.710→0.722；更早一对观察到0.700→0.759与目标年龄66.67→33.35ms，但不能挑选较好一轮作为稳定收益。此IoU以同模型当前位置为参考，非人工真值。真正的快速转头、头发摆动及遮挡仍未通过视觉验收；详见测试报告。
