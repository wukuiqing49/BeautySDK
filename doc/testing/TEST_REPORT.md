## 2026-10-31 静止虚拟背景轮廓闪烁

用户明确反馈“人不动时边缘也抖、忽明忽暗”。本轮增加内容约束的时序稳定，并修正分析小图超前视频时的蒙版选择；未将受控夹具结果描述为真人画质已经解决。

- NativeMaskConverter在原C++量化/逆旋转循环内融合时序滤波。只在RGB颜色与置信度变化较小时使用历史，修正相对当前观测最多8/255；明显颜色/置信度变化、0/255端点立即跟随。缓存只属于分割worker；geometry/代次变化、倒退或超过150ms的采样间隔以及关闭滤波清除历史。提供enableTemporalStabilization默认true，可false对照；实验性全局运动校正仍默认false。
- MediaPipeForegroundMasks原来只保存最新结果，分析采样可能超前当前视频而把仍有效旧结果挤掉，渲染因此退回原背景。现在最多保留3张不可变小蒙版，选不晚于视频的最新实际采样；位移尚未来临时使用同次未位移蒙版。仍严格250ms过期、换源/停用清空，不伪造时间戳。
- OPPO PCAM00/API30设备19项通过：新稳定3、NativeMask方向/容量/不可变快照3、兼容和回调3、实际GL/场景7、调度/重置3。新夹具包含静止噪声、颜色/大置信度变化、时间/代次/镜像失效、旋转对照及超前结果/容量限制。类别独立进程；公开portrait与生成输入，不采集真人帧。受控静止总alpha波动232→57（约降低75.4%），不代表真人边缘或全SDK性能收益。
- Pixel8开始设备测试前离线、未成功执行或安装本轮版本；OPPO覆盖安装，503份原私有文件/偏好恢复摘要一致。已知GPU置信度不兼容设备按现有规则选CPU；没有绕过保护运行明确GPU的运动基准。
- Debug/test APK/lint、MediaPipe双ABI Release AAR、显式无正式签名app Release/R8通过；新增JNI名称/双ABI导出静态保留。自有4份Debug/Release ELF与两APK静态16KB通过，不代表Release或16KB系统运行。本轮未推进无关JVM历史基线、未重新发布Maven。
- 剩余：真人静止复测、快速运动拖影/头发/遮挡/低光与持续CPU/热开销未验收；不是光流或神经视频matting。历史MediaPipe崩溃仍未关闭。无有效蒙版仍旁路，虚拟背景不是隐私遮挡保证。

证据：[结果与源码摘要](evidence/background-stability-20261031/results.json)、[稳定回归](evidence/background-stability-20261031/5ef48cf2-ForegroundMaskStabilityDeviceTest.txt)、[完整设备](evidence/background-stability-20261031/5ef48cf2.txt)、[静态对齐](evidence/background-stability-20261031/native-alignment.json)。源码指纹d9681c0439e015749a1b53c2009d1f13be9b7589ef9d90cf29c705e4ce4239ef。

## 2026-10-31 双级跟踪灰度预处理优化

结论：开启唇部细节跟踪时，两次RGBA转灰度合并为一次JNI入口。整数嵌套采样格从细节灰度直接取原采样点；不嵌套的奇数/取整尺寸保持原RGBA采样路径。原采样位置、亮度公式、时间戳、方向/镜像和跟踪质量阈值不变。最长边不超过160时，两级同尺寸只保留一个自有灰度缓冲。不跨检测/跟踪线程共享可写缓存，不新增模型或线程。

- MotionPreparationDeviceTest三项通过：六种大小（640×480、320×240、319×241、241×319、120×80、1×1）、padding与非零源position、两级输出逐字节对照旧单级JNI、源图不改写、旧参考独立和显式复用、同一估计器双线程独立缓冲；非法小目标/heap源在写入前拒绝。
- Pixel8/API36 Debug原生步骤短测：640×480生成RGBA，160×120整脸+320×240细节；预热100对，9轮交替各200次，含反射/JNI。两次原入口中位329.45089μs→新入口272.32605μs，减少17.3394%（约57.12μs/次）。仅此设备/Debug/步骤测量，不是整体SDK、Release、真实直播FPS或端到端收益。
- Pixel8共15项通过：新预处理3、连续移动/旋转缩放3、嘴部/唇形7、真实相机配置恢复1、公开portrait GPU妆面1。类别独立进程运行；新版覆盖安装，507份私有文件/偏好恢复一致，不保存用户画面。OPPO离线。
- 宿主20项JVM本次通过；核心155项任务命中UP-TO-DATE（含1既有跳过），作为同源码历史校验，不记作本次新执行或推进核心基线。Debug/test APK/lint、MediaPipe双ABI Release AAR、显式无正式签名app Release/R8通过。R8产物静态保留新方法名及双ABI JNI导出。
- 自有libbeauty_rgb_input Debug/Release双ABI共4份ELF与Debug/unsigned Release APK静态16KB对齐通过；未执行16KB系统或Release APK运行。未重新发布本地Maven或远程发行。历史MediaPipe SIGBUS/启动阻塞、真人动态画质与持续热稳态仍未关闭。

证据：[结果/源码摘要/短测](evidence/motion-gray-20261031/results.json)、[预处理测试](evidence/motion-gray-20261031/39061FDJH00D3J-MotionPreparationDeviceTest.txt)、[设备全记录](evidence/motion-gray-20261031/39061FDJH00D3J.txt)、[静态对齐](evidence/motion-gray-20261031/native-alignment.json)。源码指纹58729f042b1eb7877610d66040d610d5047e6d9660f78bc5ca31b7a003a8e1a4。

## 2026-10-31 跟踪参考图发布锁优化

结论：检测侧生成灰度/唇部细节参考图从AsyncFaceAnalysis发布锁内移至同一检测worker的同步分析阶段。锁内只提交已准备参考，代次校验仍由原AsyncFaceAnalysis执行；不增加worker、视频队列、推理、外推或结果有效期。不更改公共API、native、Shader、资源协议或效果设置。

- 根因：TrackedFaceAnalysis原onResult在分析发布锁内调用motion.prepare；渲染snapshot、采集submit和reset必须等待这项图像转换。新阻塞夹具旧代码确定发生Future TimeoutException，不依赖平均耗时猜测。
- 两个并发回归覆盖阻塞转换时snapshot/submit/reset或close可返回，换源/关闭旧结果不发布，下一代真实结果正常且旧后端由worker释放。中间测试曾错误期待reset立刻释放模型，实际契约为下一任务延迟释放；修正测试等待顺序后通过，保留中间失败记录。
- 核心155项（154通过、1既有跳过）与宿主20项通过，总计174通过、1跳过，无失败；Debug/test APK/lint通过。跟踪全10项覆盖连续接力、失败屏障、新模型恢复、代次与关闭。不沿用本轮未执行模块测试或旧发行结果作新基线。
- Pixel 8/API36共12项通过：AdjacentMotion3、LipMotion7、真实相机妆色/强度恢复1、公开portrait GPU妆面1；各类独立进程。新版覆盖安装，507份私有文件/偏好恢复摘要一致，不保存用户相机帧。
- 这是消除已复现的一处锁等待；未测真人端到端延迟/FPS收益、热稳态或长时直播。OPPO离线、历史MediaPipe SIGBUS/启动阻塞未关闭。本轮未重新发行Maven、未执行Release/R8，不引入新的发布结论。

证据：[结果及源码摘要](evidence/tracking-lock-20261031/results.json)、[旧版超时](evidence/tracking-lock-20261031/before-jvm.log)、[中间测试假设修正](evidence/tracking-lock-20261031/intermediate-jvm.log)、[设备](evidence/tracking-lock-20261031/39061FDJH00D3J.txt)。源码指纹8439d69e4bb1089ef0a17107bdb10ffc3c53affa665a06fb5bdfb6a948b1e005。

## 2026-10-31 SDK整体优化：独立妆色与可靠性增量

本轮完成一批可回归优化，不代表全部功能缺口或商业资格关闭。完整待办见[执行清单](../SDK_OPTIMIZATION_PLAN.md)，选色API及协议见[独立妆色](../SDK_MAKEUP_COLORS.md)。

- 口红/腮红新增独立RGB颜色，公共配置、GPUPixel/JNI、资源能力声明、Demo预设/自定义色号与恢复贯通。使用原有绘制通道；改色不隐式开启效果，不修改另一部位、强度或睫毛。资源必须声明makeup.colors.v1并限制所属部位。CMS/iOS编辑消费仍待补齐，未增加光泽质地或分割模型。
- 状态文件写入V2，兼容读取V1；升级后旧SDK不能读取V2。读取改为64KiB有界输入。缺失睫毛资源只中性化该项，避免恢复成程序化睫毛，不重置其他效果。
- AsyncFaceAnalysis在模型创建前后验证关闭状态/代次，避免初始化期间关闭或换源后额外执行旧任务。两个阻塞工厂并发回归通过；不等于历史MediaPipe SIGBUS/启动阻塞已解决。
- 初次API检查发现PreparedResource旧构造描述符被改变，已恢复；保留BeautyAdjustments/BeautyResourceState旧构造与copy路径。检查器仅允许final到open的兼容放宽并排除编译器WhenMappings，新增4项测试；最终2387条JVM基线签名保留。不代表Kotlin元数据/native ABI/行为全面兼容。
- JVM共194项：核心153（152通过、1既有跳过）、资源21、宿主20，无失败。Pixel 8/API36共18项通过：唇跟踪7、贴纸3、妆色资源/迁移2、相机恢复/字段隔离2、实际GPU分区选色/肤色强度/公开人像/OES4。各组独立进程执行，不能替代连续生命周期测试。
- Debug/test APK/lint、本地五制品Maven发布通过；独立单入口消费者Debug与Release/R8通过。GPUPixel Debug/Release双ABI ELF和Debug APK静态16KB检查通过，不等于16KB系统运行。API守卫、源文件许可校验及架构/UI/i18n/代理结构门禁通过。
- Pixel 8覆盖安装，507份私有文件/偏好恢复摘要一致。OPPO离线，未安装；不采集保存用户人脸帧。真人动态画质、多效果长测、热降级、历史原生崩溃及其他未实现功能仍待关闭。

证据：[最终结果与源码摘要](evidence/commercial-upgrade-verified-20261031/results.json)、[设备日志](evidence/commercial-upgrade-verified-20261031/39061FDJH00D3J.txt)、[静态对齐](evidence/commercial-upgrade-verified-20261031/native-alignment.json)。源码指纹 c62813d5ac4ae1a37a5f9fcf78b6f7b7b922122509f43a46c42e1f3a8c72a724。

## 2026-10-31 胡子移动拖后专项

用户反馈眼部正常、胡子移动拖后且静止才对齐。本轮修正两处可确认的额外延迟来源，不宣称真人拖尾全部解决。

- StickerPoseSmoother只将嘴部中心改为直接使用最新锚点，角度/尺度微抖抑制及眼睛/头饰策略保留，无脸仍隐藏、不延长有效期。新增连续小位移测试在旧代码失败，新代码通过，同时断言眼部平滑未取消。
- PreviewRequest根据最终生效的UPPER_LIP图片或程序化胡子判定需求。单开嘴部贴纸在GPUPixel主线也优先GPU分析并允许已有检测完成同采样补画；空工坊覆盖、完整主题替代、原图、关检测及3D互斥均保留。每帧最多一次补画、不缓存排队视频、不等待模型。需求名改为needsFreshFaceRedraw，口红/睫毛原路径保留。
- JVM32项通过：核心贴纸几何/平滑12、宿主需求/调度20；Debug/appAndroidTest/lint通过。Pixel8设备12项通过：唇部跟踪7（含16方向组合）、贴纸开关/隔离/恢复3、真实面板字段隔离1、公开人像GL美妆1。未新增C++、Shader、模型或改变嘴唇局部跟踪算法。
- 新版已覆盖安装Pixel8，507份私有文件/偏好摘要恢复一致；未采集保存用户画面。OPPO离线，未安装；真人快速移动尾延迟/抖动权衡、长期性能和历史MediaPipe崩溃仍待验收。

证据：[结果](evidence/moustache-follow-final-20261031/results.json)、[设备](evidence/moustache-follow-final-20261031/39061FDJH00D3J.txt)、[旧平滑回归失败](evidence/moustache-follow-final-20261031/before-jvm.log)。源码指纹 `566a17786b5a5ab2f38b909c74254a84f62c7cccf4684f7cdcd95c1f9eb03079`。

## 2026-10-31 设置隔离重构与胡子翻转修复

结论：本轮设置隔离与胡子方向修复通过资源/宿主36项JVM及Pixel8/API36共12项设备回归。未宣称全部SDK优化或商业验收完成；OPPO离线，历史MediaPipe SIGBUS/启动阻塞与持续性能仍未关闭。

- 新增BeautyResourceEdits：纯状态操作，参数类别校验；关闭/删除按目标scope中性化，保留其他类型。Demo去除updateEffects全量参数反写与Controller基线推断，滑杆只提交显式字段。
- 新增ResourceEffectApplication：贴纸、背景、运镜、美妆与旧协议各自处理所属字段；主题和独立资源共用LEGACY优先/slot排序，避免主题拆分后覆盖顺序变化。不增加逐帧线程、模型或GPU通道，不更改公共BeautySettings构造及资源文件格式。
- NativeFaceMotion曾在原始坐标按x重排嘴角，破坏转正空间左右身份。新增设备测试修复前确定失败：rotation=0、mirror=true、local=false，方向点积-1（翻转180度）。修复后保留源锚点身份，4旋转×2镜像×2跟踪模式共16组合方向/跨度通过；不延长有效期。
- 设备12项：LipMotionDeviceTest 7项（含上述方向与局部/连续跟随）；StickerToggleDeviceTest 3项（含胡子保留、调妆、删除睫毛中性化和重开）；真实面板字段隔离1项；公开portrait GL妆面1项。设备用例分组执行，不作为长时连续生命周期验收。
- 构建Debug/appAndroidTest及lint通过；架构门禁通过，4条独立示例/测量工程提示；agent结构门禁通过。未新增native C++改动，未本轮执行Release/R8或16KB运行。
- Pixel8覆盖安装，新版保留；私有files/shared_prefs共507份恢复摘要一致。测试只用生成图/公开夹具及配置，不保存用户相机帧。OPPO本轮未安装。

证据：[最终结果](evidence/settings-isolation-final-20261031/results.json)、[设备日志](evidence/settings-isolation-final-20261031/39061FDJH00D3J.txt)、[修复前翻转](evidence/settings-moustache-before-20261031/39061FDJH00D3J-LipMotionDeviceTest%23moustacheOrientationSurvivesTrackingInEveryRotationAndMirror.txt)。源码指纹 `ca7f74f2a102fb2111fe1107c43f780dba7818aa42715ff2a76d6be1d2197a55`。

## 2026-10-31 腮红圆斑妆形优化

结论：保留原强度及加权RGB亮度保护，将妆形向外上方延展、展开柔边并降低集中峰值。Pixel8五项设备与GPUPixel七项JVM本次全部通过；Debug/testAPK/lint、GPUPixel Release AAR成功，自有双ABI Debug/RelWithDebInfo四个ELF及Debug APK静态16KB通过。OPPO仍离线，未给其安装新版；不将这些结果等同于真人自然度或持续性能验收。

原因与改动：

- 旧腮红在局部四边形内受小范围高峰蒙版限制，颜色集中成圆斑。MakeupGeometry将内/外宽度上限改为0.32/0.42眼距，仍受鼻侧/外轮廓限制；上下厚度限制为0.12/0.15眼距，轮廓端向上抬起最多0.10眼距且不超过眼颊间距的0.22。仍用每侧两个三角形，不宣称曲面网格。
- Shader将覆盖色差由smoothstep改为平方根分布，保留较低覆盖率的外沿，椭圆羽化平缓区从0.18扩大到0.40，覆盖峰值0.70→0.48。固定玫瑰染色、加权RGB亮度/统一色域约束、用户0..1强度与资源状态保留，不新增模型/检测/逐帧分配/pass/等待。

本次验证与证据：

- 修复前Pixel8新增实际染色像素形状回归失败：主轴长短比1.3175，要求>1.8；[旧版失败记录](evidence/blush-shape-before-20261031/39061FDJH00D3J-GpuPixelDeviceTest%23blushPreservesSkinToneLuminanceAndStrengthOrder.txt)。最终同输入主轴比2.1579通过。RGB(150,125,100)满强度变化像素496→554，说明不仅是整体降低强度；这是控制纹理测量，不是自然度评分。
- 保留深/中/浅/近白四肤色×三强度、黑/白各三强度，共18组亮度、暖色变化、强度递增及alpha断言。另跑局部口红/腮红宿主GL状态、OES契约、真实公开portrait与三款睫毛组合、相机三参数/预设绑定/新页面保存恢复，共5个方法，0失败。
- GPUPixel7项JVM实际执行通过，含边界、单侧失效/旧顶点清除、口红睫毛缓冲保护、旋转镜像；未改变上轮核心跟踪策略。
- 已查看公开portrait满强度对照：build/functional-test/blush-shape-final-20261031/public-blush-comparison.png（左原图/中旧/右新）；集中粉红斑点减弱、外上方分布更宽。该图仅为公开fixture，不保存用户帧，不能据单张正脸认定真人动态通过。
- lint 0 Error/214 Warning；结构门禁/空白检查通过。构建日志build/blush-shape-{build,release}.log；Release为库AAR，不将历史宿主Release/R8升级为本轮通过。Pixel8设备原507个files/shared_prefs文件hash恢复一致，最终Debug已安装。

[最终结果/源码指纹](evidence/blush-shape-final-20261031/results.json)、[Pixel8五项](evidence/blush-shape-final-20261031/39061FDJH00D3J.txt)、[静态对齐](evidence/blush-shape-final-20261031/native-alignment.json)。源码指纹 fcde2b9dcba2cf263639602eb9a54d5118c9b3d55373c484713439d75024eb0c。真人不同脸型/肤色、快速转头与遮挡、持续性能/温升、OPPO、实际直播厂商、16KB页系统及既有MediaPipe生命周期风险仍未由此轮关闭。

## 2026-10-31 腮红保色与覆盖范围优化

结论：最终GPUPixel JVM7项、Pixel8设备5项全部通过。OPPO在执行前断开，仅记录未执行，本轮未给OPPO安装新版。Debug/testAPK/lint及GPUPixel Release AAR通过；自有GPUPixel Debug/RelWithDebInfo双ABI四个ELF与Debug APK静态16KB检查通过，不代表16KB系统运行或真人效果验收。

变更：

- beauty_bridge.cpp：原浅粉白底素材RGB不再作为柔光目标，仅由其色差与alpha提取分布蒙版，配合椭圆羽化。固定玫瑰色差保持(.2126,.7152,.0722)加权编码RGB亮度，按原像素色域余量共同收缩后混合，避免白底提亮、各通道裁剪或黑/白区域产生假色块；这是编码RGB权重，不宣称线性光照校正/肤色分割。
- MakeupGeometry.kt：在双眼/下巴方向基础上，分别使用鼻点1与外缘234/454约束内外宽度、眼颊/颊下巴投影约束上下高度。单侧脸颊点无效清空该侧旧顶点，另一侧仍可绘制。仍是每侧两个三角形，不是稠密曲面/3D遮挡，不新增局部跟踪或时间平滑。
- 原公共强度/资源协议、固定1440浮点缓冲、FBO和局部绘制数保留；PNG仍只初始化上传一次，没有逐帧Bitmap/新模型/读回/同步等待。不增加可选色号或自动换资源，不以此声明持续性能通过。

证据与验证：

- 修复前Pixel8真实Shader控制纹理回归失败，变化像素平均加权亮度误差11.2329/255，保留[旧实现断言失败](evidence/blush-before-20261031/39061FDJH00D3J-GpuPixelDeviceTest%23localMakeupPreservesMouthInteriorAlphaAndHostState.txt)。最终该原回归通过，要求平均误差<1.5，同时保留强度递增、视频alpha、GL混合状态、纹理变换及局部边界检查。
- 新增blushPreservesSkinToneLuminanceAndStrengthOrder：四种受控肤色×0.25/0.6/1.0强度、纯黑/纯白各三强度，共18组；四肤色均有可见暖色变化，强度递增，黑白完全不变，平均亮度误差范围0.1729～0.2709/255。不能把合成颜色范围当作真人肤色验收。
- Pixel8另执行OES输入、公开portrait实际MediaPipe+组合渲染与相机口红/睫毛/腮红字段隔离/保存回归，总计5个设备方法。OES夹具补充与眼睛/下巴上下分离的有效脸颊点，以实际进入腮红分支，保持原契约断言。公开人像对照仅保存在build/functional-test/blush-final-20261031/public-blush-face.png，已查看，无用户画面。
- 新增BlusherGeometryTest3项：鼻/眼/外缘边界、口红睫毛缓冲保护、单侧失效清旧网格、90度/镜像非方帧等价；既有4项睫毛JVM通过。初版测试夹具尝试构造NaN FacePoint被公共契约拒绝（3项失败），改为合法有限但越界点后重新执行7项全通过，未放宽生产校验或断言。
- lint 0 Error/214 Warning，结构门禁、diff空白检查通过。构建日志build/blush-verified-build.log、build/blush-release-final.log。首轮Release/lint进程无最终成功信息即退出；一次重试因PowerShell拆分-D参数而失败，最终使用--max-workers=2 --no-parallel完成；不沿用失败进程作为通过证据。
- 设备原files/shared_prefs共507文件hash恢复一致，最终Debug留在Pixel8。测试只处理生成纹理/公开portrait，不记录保存真人图像；原配置不清空。

[最终结果与源码摘要](evidence/blush-final-20261031/results.json)、[Pixel8五项](evidence/blush-final-20261031/39061FDJH00D3J.txt)、[静态对齐](evidence/blush-final-20261031/native-alignment.json)。源码指纹 0a7ac3efbd797ca9ebb9fb4d7f744f8de4ff5ad390ca5229d5032b2c208948dc。旧MediaPipe生命周期SIGBUS/启动阻塞、真人快速转头/遮挡自然度、持续性能/温升、OPPO本轮验证、宿主Release/R8、实际直播厂商和16KB页系统仍未由此轮完成。

## 2026-10-31 美妆预设/微调错绑修复

结论：修复前 OPPO 在真实相机面板复现“先调睫毛、再点击口红预设，微调仍绑定睫毛”的断言失败。修复后 OPPO/Pixel 8 各3项、合计6项通过，0失败；用户真人复核尚未完成，不能据此把之前的闪烁或生命周期风险标记解决。

原因与修改：预设仅修改资源，ResourceParameterPanel保留旧selection，没有按预设scope切换微调对象；用户再次拖动时实际修改上一次的部位。ResourceCenterController点击单参数预设时传入相同参数ID，面板在下一次渲染绑定对应按钮/标签/滑杆/重置；已有参数按钮使用当前绑定回调，避免持有首次render闭包。直接点击口红/腮红/睫毛参数按钮的ID与数值隔离断言在修复前也已通过，没有证据表明native参数顺序接反。本增量仅修改两个Demo UI文件，未修改SDK着色器、检测或跟踪策略。

本次验证：

- 新增CameraConfigurationDeviceTest.makeupPanelEditsOnlyNamedFieldAndPresetFocusMatches：真实按钮+SeekBar用户输入，轮流调睫毛/口红/腮红/口红归零，逐次核对三个字段、相机最终options及材质。停在睫毛微调后实际点击口红预设、重新进入微调，确认绑定口红；再调整口红至0.66，睫毛0.43/腮红0.38与原睫毛素材保留。退出并创建新页面后再次核对三项。
- 既有makeupToggleAndDisablePreserveOtherEffectAcrossReopen、actualPortraitMakeupUsesDetectedLipAndCheekLandmarks通过，覆盖单项取消/恢复与公开人像GL组合、三款睫毛亚像素移动夹具。两机按方法独立instrumentation进程运行，不代表长时生命周期通过。
- Debug/testAPK与lint构建通过（lint 0 Error/214 Warning）；UI静态门禁0 warning；git diff --check通过。本轮不重复Release/native ABI验证，因为SDK/native/build配置未由此增量修改。
- 两机已安装最终Debug，设备私有files/shared_prefs恢复hash一致（OPPO503、Pixel8 507文件）。不卸载/清数据、不保存真人画面。构建日志build/makeup-controls-{final-build,test-build}.log。

证据：[结果/源码摘要](evidence/makeup-controls-final-20261031/results.json)、[OPPO](evidence/makeup-controls-final-20261031/5ef48cf2.txt)、[Pixel8](evidence/makeup-controls-final-20261031/39061FDJH00D3J.txt)、[修复前失败](evidence/makeup-controls-before-20261031/5ef48cf2-CameraConfigurationDeviceTest%23makeupPanelEditsOnlyNamedFieldAndPresetFocusMatches.txt)。源码指纹 13a15f62e58c0aecb1dc2484a5baf2094689fc8bc20a4d8641aeed553d989118。未覆盖：横屏/字体完整矩阵、强杀未落盘、真人观感和持续性能；既有MediaPipe SIGBUS/启动阻塞原因仍待处理。

## 2026-10-31 前轮睫毛闪烁验证补记

前轮已完成但被用户“美妆错乱”反馈中断汇报：TrackedFaceAnalysis新增边界回归先失败后通过；核心150、GPUPixel4、宿主19项JVM执行通过；最终两机各11项、共22项通过，证据见[evidence/lash-flicker-verified-20261031/results.json](evidence/lash-flicker-verified-20261031/results.json)。仅允许不早于最后有效匹配的真实新模型在可选匹配失败时继续使用，不延长模型年龄；没有新增眼睑跟踪。初次OPPO执行前11项通过后相机配置类启动未进入测试，主动停止并恢复，记录见evidence/lash-flicker-final-20261031。实时聚合日志不足以唯一归因用户的每次闪烁，用户尚未确认完全解决，保留该限制。前轮Release/R8及静态16KB验证不能替代真人/持续性能/16KB系统运行验收。

## 2026-10-31 口红与睫毛状态/合成隔离

结论：针对性回归最终双机各15项，共30项通过、0项断言失败，资源JVM13项通过；不能据此关闭生命周期稳定性。首轮实现双机全量各20项通过。最终同进程OPPO复跑21项时，20项已完成，资源类末尾发生MediaPipe SIGBUS；后续分离配置/图形类复跑时，图形类在1280×720唇轮廓输出后长时间未完成，主动强停并恢复配置，原因未确定。最终按类/关键GL方法独立进程验证，失败和未完成记录均保留，不覆盖或升级其通过基线。

变更：

- beauty_bridge.cpp：口红仍为40点直接RGB染色；腮红、图片/程序化睫毛各自输出目标RGB及coverage，以SRC_ALPHA/ONE_MINUS_SRC_ALPHA局部混合。alpha使用ZERO/ONE保留原视频。透明网格不再把已画妆面恢复原图。仅Makeup保存/恢复混合因子与方程，基础调色不支付新增查询；继续共用FBO/VBO，不新增全屏pass、纹理读回、模型或眼睑局部跟踪。输入支持既有2D/OES。
- BeautyResourceState/ResourceModels：旧LEGACY资源先作为底层，再应用独立槽位，最后用户参数，不依赖JSON插入顺序；旧资源没有eyeMakeup时保留已有材质。
- ResourceCenterModel/Controller：相机美妆已选卡片再次点击独立取消，并将该资源scope的手动参数置中性，避免清掉图片后仍以旧强度回退程序化睫毛；另一个美妆槽位保留。整类“无”仍明确关闭全部美妆。管理页显式取消同样清该scope；公共SDK保留无图片时可用的程序化睫毛能力。

验证：

- CameraConfigurationDeviceTest 5项：新增同开口红/睫毛、重复选睫毛取消、口红保留、新页面恢复；再次启用睫毛后单独取消口红、睫毛保留及恢复。另保留参数合并、选择顺序、真实SeekBar输入和手势/帽子记忆回归。新增单项取消验证通过Model入口，不冒充卡片实际点击/字体布局矩阵。
- GpuPixelDeviceTest 4个关键方法：actualPortraitMakeupUsesDetectedLipAndCheekLandmarks新增三款APK图片睫毛+口红同时开启的分区像素对照、回到口红单开无材质残留、人工透明眼睑网格覆盖唇部仍保留口红；localMakeupPreservesMouthInteriorAlphaAndHostState新增六项宿主混合因子/方程及开关恢复；另执行texturePipelinePreservesTransformAlphaAndHostState、oesReshapeAndColorUseSharedTextureContract。新增组合像素夹具为2D公开portrait.jpg，OES是既有状态/形变回归，不代表新增组合已覆盖全部OES矩阵。
- ResourceLibraryDeviceTest 6项：新增V3旧整套+V4独立睫毛两种插入顺序、显式参数覆盖；原有完整库与UI/兼容用例保留。资源JVM ResourceCompositionTest 9+ResourceCapabilitiesTest 4通过，新用例检查无眼材质旧资源不清掉已选图片。JVM初建实际执行，最终同源码构建UP-TO-DATE。
- Debug APK/test APK/lint成功，lint 0 Error/214 Warning；GPUPixel Release AAR及显式-PrequireReleaseSigning=false app Release/R8成功。GPUPixel Debug/RelWithDebInfo双ABI四个ELF LOAD对齐和Debug/Release APK zipalign静态16KB通过；未在16KB页系统运行。结构/config/UI门禁、git diff --check通过。
- 每轮先备份设备私有files/shared_prefs；最终OPPO503、Pixel8 507个文件hash恢复一致。最新Debug已安装到两机。无卸载/pm clear，不保存或采集真人画面；图形测试只使用公开人像/合成纹理。

证据：[最终结果与源码摘要](evidence/makeup-isolation-targeted-20261031/results.json)、[OPPO最终15项](evidence/makeup-isolation-targeted-20261031/5ef48cf2.txt)、[Pixel8最终15项](evidence/makeup-isolation-targeted-20261031/39061FDJH00D3J.txt)、[静态native对齐](evidence/makeup-isolation-targeted-20261031/native-alignment.json)、[OPPO失败记录](evidence/makeup-isolation-final-20261031/5ef48cf2.txt)、[MediaPipe堆栈](evidence/makeup-isolation-final-20261031/5ef48cf2-native-crash.txt)、[未完成图形类](evidence/makeup-isolation-verified-20261031/5ef48cf2-GpuPixelDeviceTest.txt)。源码指纹aaa08bf4fb77718d906ddf45b7527afc9e388c99231a44fd4778fa48eeea9780。构建原始日志在build/makeup-isolation-{build,final-build,release}.log。

限制：未修改检测器生命周期、唇部/眼睑跟踪；真人眨眼/侧脸贴合、长时性能、历史及本轮MediaPipe崩溃、图形长时间未完成原因、强杀未落盘、真实直播引擎、iOS和正式签名发行均未通过本轮验收。完整主题内部子资源仍按包声明顺序组合，本轮新增确定性顺序针对多独立槽位。

## 2026-10-31 睫毛可见度与调参保存队列

用户确认上一版口红效果很好，本轮仅处理“睫毛不显示；重开后效果或强度丢失”，保留口红直接染色、跟踪和同采样补画。

定位与边界：原有actualPortraitMakeupUsesDetectedLipAndCheekLandmarks只判断少量任意像素变化，归一化舍入也可能满足；加强为眼周RGB总变化大于24的可见像素，并增加APK resource_delivery.zip内真实三款素材。公开人像320×400的小眼区中，程序化睫毛有19个明显变化像素，图片自然款为0；纹理512px细毛在mipmap缩小时alpha被平均得过淡。shader图片睫毛分支现按UV导数计算缩小足迹，用clamp(sqrt(footprint),1,6)有界补偿alpha（1-pow(1-a,gain)），透明底仍0，原尺寸/放大不增益；不删除mipmap，不改素材、眼睑几何、口红、背景alpha或公开API。双机三款图片睫毛分别31/49/37可见像素，APK内置与旧独立测试包一致。仅公开人像夹具证明可见度提高，不等于真人自然度、遮挡或动态贴合通过。共享2D/OES shader编译及既有OES状态回归通过，新增眼周可见度测量为2D输入。

保存：普通选资源/模型参数/退出重开在旧代码已经通过，不能声称复现全部用户丢失路径。另以阻塞IO夹具确认81次参数更新原来产生81次loading发布及独立IO任务。ResourceCenterModel现只将等待执行的相邻参数操作按字段合并为最新值，worker取走后才形成下一批；选择资源、开关、重置等task封闭前一批，保持操作先后顺序。输入防御复制，退出后已接受批次仍由共享串行IO执行，新页面排队读；无主线程落盘或等待。新夹具验证81次输入合为一次loading，在IO阻塞时退出，再恢复IO并重开，最终口红0.80/睫毛0.61恢复；另验证参数→选睫毛预设→参数的边界顺序，以及真实fromUser滑杆输入0.63/口红0.72、图片睫毛选择、GPUPixel和人脸分析需求在新页面恢复。未模拟进程在IO落盘前被强杀，不能宣称任何时刻断电/强杀不丢配置。

失败记录保留：初次强阈值也施加程序化小眼区，19像素低于20，后限定强阈值于用户问题对应的图片睫毛；实际图片自然款仍为0明确失败。UI测试首次因控件已移到Dialog而取Activity空根NPE，修正测试定位和键盘步长后通过。alpha上限4时图片自然款16像素未通过既有20阈值，上限6后31像素通过，未降低图片阈值。保存合并夹具旧代码expected1/actual81明确失败。不是把以上失败统计成最终通过；证据分别在lash-visible-before、lash-ui-before、lash-fix-probe、makeup-save-before目录。

最终验证：OPPO PCAM00/API30和Pixel8/API36各30项，合计60次设备执行、0失败；CameraConfiguration4、GpuPixel10、MakeupCamera1、TextureBridge8、StickerToggle2、ResourceLibrary5。包含口红完整轮廓/口腔排除、GL状态/alpha/旋转镜像、快速调参、资源选择恢复与PBO生命周期。OPPO81.759秒，Pixel39.469秒；覆盖安装后APK摘要一致，私有files/shared_prefs原样恢复503/507文件，未卸载/清数据。公开夹具在内存生成/比对，未采集或保存用户画面。

构建：Debug/测试APK、lint0错误/214警告、GPUPixel Release AAR和显式无正式签名Release/R8通过。资源JVM12项本轮执行通过；GPUPixel4及feature19项任务UP-TO-DATE为历史有效结果未新执行；app:testDebugUnitTest为NO-SOURCE。自有libbeauty_gpupixel.so双ABI×Debug/RelWithDebInfo共4份ELF LOAD16KB与Debug/Release APK zipalign静态通过，非16KB设备运行/全部第三方库证明。额外片元操作仅图片睫毛区域，持续GPU成本/温升未测，不宣称零开销。

证据：[结果与源码摘要](evidence/lash-save-final-20261031/results.json)、[OPPO](evidence/lash-save-final-20261031/5ef48cf2.txt)、[Pixel8](evidence/lash-save-final-20261031/39061FDJH00D3J.txt)、[native静态检查](evidence/lash-save-final-20261031/native-alignment.json)。源码指纹0d0a7adb531320daf331341cafb3175b98dfd6029dcc2d2b0871f8397e6df33c。新版已安装，真人睫毛观感和用户原丢失场景仍待复测；实际直播引擎、长时温升、历史GPU重建SIGBUS及分辨率偶发超时未由本轮关闭。

## 2026-10-31 跟随拖尾：同采样完成后按需补画

用户确认上一版光流有改善但仍拖尾，本轮不将算法夹具通过当作真人通过。只读metadata复现记录中仅一个有脸聚合窗口（ready=17、tracked=2、stale=3，最大结果年龄44.60ms），其余多为无脸；不能将这些日志当完整15秒的有脸测量，也不能据此认定唯一根因。未采集或保存用户画面。

代码确认的额外等待：相机RENDERMODE_WHEN_DIRTY只由新帧/UI唤醒，模型/跟踪onTiming完成后只记录日志，即使当前采样的点已发布也要等待下一相机回调。GpuBeautyPreview现对有美妆需求、成功有脸且完成时间戳严格等于当前视频采样的结果requestRender；AnalysisRedrawGate合并两支路回调，每个采样最多补画一次，已绘制同采样结果不再唤醒。过时/未来结果、原图、关闭分析、非美妆、停止后均不补画。新帧正常优先消费；补画沿既有onRedraw路径，不推进视频时间、不再次检测或改写关键点时间戳。无新模型/native/队列或渲染线程等待。

这项变更属于Demo预览调度，公共SDK既有redraw能力通过受控延迟后端验证：初次合成无点→结果发布不自动改变已合成帧→显式redraw取得同时间戳新点、年龄0、检测仅一次。不能让直播引擎未经约定异步重复交帧，也不保证首遍输出同帧。额外补画增加高清合成工作，GPU增量/温升及真人端到端延迟尚未测量，不宣称零开销、专业级跟随或消除模型内部平滑滞后。

本轮验证：核心JVM148通过/1跳过，feature JVM19通过；新增同采样晚到结果契约1项与补画合并/失效3项。Debug/测试APK、lint通过（0错误、214警告）。OPPO PCAM00/API30、Pixel8/API36各13项通过，合计26次设备执行、0失败：美妆GPU/快速调参1、配置记忆1、贴纸开关2、PBO纹理桥8、回调异常隔离1。没有真人输入的设备冒烟不证明补画路径真人效果。两机APK摘要一致，私有配置503/507文件恢复一致；无卸载/清数据。本轮未改native，未重跑Release/R8或16KB审计，保留上一轮原生证据边界。

新版已覆盖安装；用户随后确认口红“效果很好”，并另报睫毛不显示、重开效果或强度丢失。此为用户观感确认，不是agent真人测量。证据：[本轮结果](evidence/live-follow-redraw-20261031/results.json)、[OPPO](evidence/live-follow-redraw-20261031/5ef48cf2.txt)、[Pixel8](evidence/live-follow-redraw-20261031/39061FDJH00D3J.txt)、[复现metadata](evidence/live-follow-redraw-20261031/oppo-user-tail-metadata.log)。源码指纹54692ac2d932c8b5b0f54ab062acfc5ea473d90dc99fa6e899c89459c3c49409。持续性能、真实快速三维转头/唇形变化、遮挡/模糊、厂商远端及历史GPU重建SIGBUS风险仍未验收。

## 2026-10-31 动态跟随：大位移捕获与金字塔光流

用户反馈：上一轮降低运算成本后真人跟随仍差；用户已按要求在OPPO复现。本轮先读已有metadata日志，不采集/保存画面，再建立失败夹具。未将上一轮微基准提速当作动态问题解决。

证据与边界：原生有脸稳态窗口的GPU推理均值约25.6～29.0ms，部分排队窗口最高约19～32ms；约25帧窗口只有7～13帧使用tracked结果。日志同时包含启动约10秒/无脸段，且初始采集包含logcat最后200行历史，不能将所有行精确等同于用户15秒动作，不用它独立证明唯一根因。metadata见[evidence/live-follow-diagnosis-20261031/oppo-metadata.log](evidence/live-follow-diagnosis-20261031/oppo-metadata.log)。可确定的受控缺陷：240×300公开人像相邻输入横移24px，旧全局160最长边、±12整数搜索返回null；旧版fastPortraitTranslationMustNotLoseTracking在OPPO明确失败，12px成功、24px失败。此失败保留，不放宽断言。

实现：face_motion.cpp的全局locate替换为有界细层捕获＋最多3层稀疏零均值Lucas–Kanade式光流。小运动先在原层±5捕获，候选落到边界或质量不足才进入粗层±6捕获；各层参考梯度/Hessian只计算一次，最多12次迭代，每次增量/纹理/矩阵条件受限，最终保留归一化残差、前后向误差1.6px、相似变换一致性及原缩放/角度限制。双金字塔使用固定栈存储（两份各2×80×80字节），最多16锚点，无全图稠密流、新模型、OpenCV/ONNX依赖、额外队列或渲染等待。局部唇部仍是原20采样点残差＋40轮廓传播，不把全局光流描述为专用唇部解析或三维非刚性跟踪。NativeFaceMotion仅更新实现说明，公共构造/JNI/API未改；颜色渲染、资源包及150/300ms有效期不改。

开发失败记录：首版纯粗到细在生成纹理连续运动step4回归，两机各1失败；据此增加原层可靠捕获，保持生成细节，而非删除测试/修改阈值。修正后两机8/8 probe通过。另一次编译因遗留未使用SEARCH常量触发-Werror，删除无用常量后构建通过。旧算法1次失败＋首版16次执行14通过/2失败＋修正probe16通过＋最终64通过，共97次执行94通过/3次历史失败；最终版本零失败，不能把历史失败统计成通过。

最终验证：OPPO PCAM00/API30与Pixel8/API36各32项，64次设备执行通过。覆盖LipMotion6、AdjacentMotion3、GpuPixel10、MakeupCamera1、TextureBridge8、内容对齐1、配置恢复1、取消2。fastPortraitTranslationMustNotLoseTracking在12/24/36px全部通过（33.33ms采样间隔），并拒绝完全黑色/弱纹理图；portraitRotationAndScaleStayAligned为±0.1rad平面旋转、1.04倍缩放及(12,-6)平移，完整40唇点平均误差0.201/0.253px，两机一致。这不是快速三维侧脸/遮挡或运动模糊测试。公开人像8帧接力最大鼻点误差2.3266→0.5361px。生成局部唇动连续8帧最大平均轮廓误差0.1304px（上一轮0.1292px），均在既有3px界内；不声称每个指标严格不变。最终估计均耗时OPPO1.603640ms、Pixel1.614878ms，仅短夹具，不代表全链路延迟；新大位移单次测量约2.25～3.93ms，保留噪声，不选择probe最好值替代最终。

构建/所有权：Debug/测试APK、lint0错误/214警告、MediaPipe Release AAR和无正式签名Release/R8通过；核心147通过/1跳过、GPUPixel4项JVM任务UP-TO-DATE，明确为历史有效结果未新执行。MediaPipe自有libbeauty_rgb_input.so双ABI×Debug/RelWithDebInfo四份ELF LOAD16KB、Debug/Release APK zipalign静态通过，不代表16KB设备运行或全部第三方库。最终OPPO64.127秒、Pixel37.503秒；两机已覆盖安装，APK摘要与本地一致，私有配置503/507文件恢复摘要一致；未卸载/清数据。公开人像变换只在内存生成，不读用户画面。

证据：[最终结果](evidence/live-follow-flow-final-20261031/results.json)、[OPPO](evidence/live-follow-flow-final-20261031/5ef48cf2.txt)、[Pixel](evidence/live-follow-flow-final-20261031/39061FDJH00D3J.txt)。失败在live-follow-failure-20261031、live-follow-flow-probe1-20261031；修正probe在live-follow-flow-probe-20261031。工作区指纹`efbca8c79d716b56135e737a7b658b046ce3aa935d08412a98282418e6f877ec`；仅最终32个方法/设备推进基线。命令：gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest；:core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug；:core:lib_beauty_mediapipe:assembleRelease :app:assembleRelease -PrequireReleaseSigning=false；adb -s <serial> shell am instrument -w -r -e class <八类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner。

真人验收：用户确认新跟踪算法“有改善，但仍拖尾”，动态质量未通过。严格同帧渲染、模型平滑造成的滞后、真实三维转头/嘴部非刚性/遮挡与模糊、长时温升及直播厂商远端仍未验收；历史GPU重建SIGBUS/分辨率偶发超时未关闭。本次修复一个可复现的捕获范围缺陷，不认定全部真人跟随问题已解决。

## 2026-10-31 直播热路径精简与跟踪效率

结论：完成现有直接染色链路的成本优化，未增加模型/推理依赖或公共API。最终OPPO PCAM00/API30与Pixel8/API36各30项、共60次设备执行通过，0失败；前置基线14次及首轮probe58次也通过，共132次执行，不是132个不同用例。新版Debug已覆盖安装，两机安装APK摘要与本地一致，私有files/shared_prefs恢复摘要一致（503/507文件），未卸载/清数据/保存用户画面。真人快速转头跟随仍未验收，不宣称“最高效率”或已解决动态贴合。

修改范围：
- NativeFaceMotion/face_motion.cpp：整脸与局部搜索共用ReferencePatch；每个参考块只提取一次49像素、求一次均值/方差，候选点用有界整数累加后保持原浮点评分；局部弱纹理提前拒绝。搜索范围、顺序、双向/低纹理/阈值/网格退化检查不放宽，不用时钟预测。Kotlin复用固定锚点索引、去掉478点map后的二次列表复制和嘴角临时排序列表；估计器仍无共享可写图像缓存。
- GlTextureAnalysisBridge：去掉中间cpu DirectByteBuffer及一次整幅小RGBA暂存复制，在PBO映射有效期内将只读借用缓冲交给同步复制型submit；回调返回/抛错后finally重新绑定对应PBO并unmap，释放槽位并恢复GL状态。每个异步worker仍独立复制到其有界所有权缓冲，不能称为零拷贝。未新增glFinish或等待推理；现有fence零超时轮询保留，驱动map/GL状态查询不保证零耗时。
- GpuBeautyPreview：删除每帧先查询analysis.snapshot再被覆盖的路径，状态统一使用实际公共pipeline.lastFrameAnalysis。错误/原图仍明确旁路。Session/Connector承担线程、借用、交回与生命周期契约，未为减少层数而删除这些边界。

对照：同一生成图像8帧连续平移＋局部唇动、相同320分析尺寸，OPPO meanEstimateMs 3.204010→2.776035（下降13.36%），Pixel 2.970098→2.106221（下降29.09%）。两机maxMeanContourErrorPx仍0.129159；局部平移、张嘴、旋转镜像、单角遮挡既有误差值与基线一致。首轮probe OPPO2.439277ms，最终2.776035ms，存在短测波动，不选择最好值代替最终结果。此为顺序执行短夹具对照，不是直播全链路延迟/热稳态/真人精度证据，未单独量化删除PBO暂存复制的收益。

验证：8类30项/机＝局部运动6＋公开人像相邻跟踪1＋GPUPixel10＋真实美妆相机1＋纹理桥8＋内容对齐1＋配置恢复1＋贴纸取消2。新增TextureBridgeDeviceTest#workerOwnsFrameWhileMappedPboSlotsAreReused：阻塞首帧worker，多次覆盖PBO和pending后读到首帧71及最新帧99，中间帧不积压；#borrowedPboFailureUnmapsAndLaterSamplesRecover：借用缓冲只读、消费者异常可见，随后3次投递恢复且GL无错误。既有2D/OES/变换/过期/启停/状态恢复保留。真实相机测试允许NO_FACE，不代表测试时有人脸或快速运动通过。

核心JVM148项（147通过/1跳过）、GPUPixel4项通过；Debug/测试APK、app lint0错误/214警告、MediaPipe Release AAR及无正式签名Release/R8构建通过。MediaPipe自有libbeauty_rgb_input.so双ABI×Debug/RelWithDebInfo共4份ELF LOAD16KB及Debug/Release APK zipalign静态通过，不代表全部第三方库或16KB设备运行。结构/config门禁及diff检查通过。

证据：[最终结果](evidence/live-motion-efficient-20261031/results.json)、[OPPO](evidence/live-motion-efficient-20261031/5ef48cf2.txt)、[Pixel](evidence/live-motion-efficient-20261031/39061FDJH00D3J.txt)；before与probe分别位于evidence/live-motion-before-20261031和live-motion-efficient-probe-20261031。工作区指纹`1954e401ec9846fc74e89ed55e5813ebfd3f8bc09e16e0a3238779e280b57d40`；基线仅推进最终30个实际通过方法/设备。命令：gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug；:app:assembleDebugAndroidTest（新增所有权夹具后重建）；:core:lib_beauty_mediapipe:assembleRelease :app:assembleRelease -PrequireReleaseSigning=false；adb -s <serial> shell am instrument -w -r -e class <八类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner。

剩余：未新增专用唇部分割、金字塔光流、视频帧延迟缓存或严格同帧合成；未解决模型/跟踪结果与当前视频天然存在的时间差。持续真人、温升/尾延迟、真实直播厂商远端、MediaPipe GPU重建历史SIGBUS和OPPO分辨率历史偶发超时仍待验证。Release未设备运行；不得把本轮成本降低表述为专业美妆动态质量验收。

## 2026-10-31 口红改为唇部网格直接RGB染色

结论：已按用户要求移除口红PNG的运行依赖，直接在40点唇部环带网格内由RGB uniform染色；默认(.90,.32,.38)，原强度滑杆继续生效。OPPO及Pixel每机21项、共42次相关设备执行通过，0失败；新版覆盖安装，两机安装SHA256与本地Debug一致，私有配置恢复。此轮改变颜色来源，不包含新跟踪/分割模型，不能承诺快速移动和真实唇缘误差自动解决。

实现：GpuPixelBeautyRenderer构造从六图片/12尺寸变为四LUT＋腮红共五图片/10尺寸；beauty_bridge.cpp同步创建/校验/释放数量，口红Shader以lipColor取代mouth.png采样，直接混合原视频亮度保留唇纹/明暗，保留强度、口腔留空、1像素内侧羽化与视频alpha。网格draw仍120顶点，无口红材质UV使用，无新FBO/截图/纹理读回/生产等待。共享Shader仍绑定腮红纹理维持采样器完整性，但口红分支不读取该纹理；腮红与睫毛各自纹理继续使用。没有新增颜色选择UI或公共颜色API，当前为内置玫红色。

资源闭环：mouth.png从src/main/assets移到third_party/gpupixel/src/res仅保留上游参考，原SHA256不变。scripts/import_gpupixel.py、verify_gpupixel_sources.py与NOTICE同步。来源验证17份文件/2个原始Shader通过，两Python脚本AST语法通过；未实际重新执行上游导入。Debug/无正式签名Release APK及GPUPixel Release AAR的ZIP成员均确认不含mouth.png、仍有blusher.png，见[打包核对](evidence/lip-direct-color-20261031/package-assets.json)。

验证：GpuPixelDeviceTest#fullLipContoursCoverInteriorWithoutLeakingAcrossEdges先断言目标APK内口红PNG不存在，再在真实缺图条件下渲染，三尺寸96×128/640×480/1280×720及张/闭嘴共6组Canvas参考覆盖通过；排除2像素边界后唇内>99%、唇外/口腔漏色0、alpha173。既有强度递增、输入变换、原视频明度、无脸/缺点旁路、肤色/美型/腮红/程序化与PNG睫毛组合、GL状态及OES路径回归通过。公开portrait.jpg检测贴妆通过，不保存用户帧，不代表真人观感。

设备：[OPPO PCAM00/API30](evidence/lip-direct-color-20261031/5ef48cf2.txt)21/21，62.14秒；[Pixel8/API36](evidence/lip-direct-color-20261031/39061FDJH00D3J.txt)21/21，38.3秒。六类为GPUPixel10、实际美妆相机1、纹理桥6、实际内容对齐1、配置恢复1、取消贴纸2。没有复跑未改的局部运动/3D/背景/分辨率切换/长测类，不计入本轮通过。生成720p短测OPPO完成绘制中位3.629ms/P95 5.084ms，GPU中位1.623ms/disjoint0；Pixel完成中位2.190ms/P95 3.273ms，GPU多次disjoint32不作有效GPU对照。这些是非配对短测，不宣称较上版提速或持续相机性能。

构建：GPUPixel JVM4/4；核心JVM任务UP-TO-DATE沿用有效148项结果（147通过/1跳过），不是新执行148项。Debug/测试APK、GPUPixel Release AAR、app lint（0错误/214警告）和无正式签名Release/R8构建通过。GPUPixel双ABI Debug/RelWithDebInfo四份自有ELF LOAD静态16KB、Debug/Release APK zipalign通过，不代表全部依赖或16KB设备运行。结构/workflow config门禁通过，diff --check无错误。Release未设备运行，未修改Gradle/manifest/公共参数或资源包。

配置保护：每机覆盖安装前备份私有files/shared_prefs并恢复摘要一致，OPPO503/Pixel507份；未卸载/pm clear，未保存/上传相机画面。剩余真人唇缘/快速转头/模糊/遮挡、长期温升、历史MediaPipe GPU重建SIGBUS及OPPO分辨率切换偶发超时均未关闭；直播厂商/正式签名/16KB运行未验收。

命令：`gradlew.bat :core:lib_beauty_gpupixel:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；`gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:assembleRelease :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false`；`python -B scripts/verify_gpupixel_sources.py`；设备`adb -s <serial> shell am instrument -w -r -e class <六类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。未提交工作区指纹`16b5698f5bb38ab2646c68c9511a5a71ce335f460c537ff03468149eed3c2030`（本轮纳入scripts目录），逐文件/方法、native和安装证据见[本轮记录](evidence/lip-direct-color-20261031/results.json)，基线仅推进实测设备方法。

---

## 2026-10-31 快速移动退化：对照复现与修复

结论：用户确认上一轮完整唇缘版快速移动退化。本轮用旧生产代码复现单嘴角遮挡导致局部校正整组撤销，并测到OPPO逐片元40边遍历的额外GPU成本；已修复这两处新增问题，完整40点唇缘仍保留。最终OPPO/Pixel各45项共90次设备执行通过，0最终失败，安装摘要一致且私有配置恢复。真人快速移动/转头尚未验收，不把该结论写成专业级动态问题已解决。

原因与实现：

- `NativeFaceMotion.kt`、`face_motion.cpp`：撤回上一轮40点全量块搜索及四个内外角必须同时成功的硬门控，恢复先前20点预算/至少14点的70%门槛。单点匹配的弱纹理/±4边界/前后向1.2px质量标准不变。未匹配的采样点使用原距离加权残差，其余20点按各自内/外环相邻采样点插值位移，保留模型原形和全局运动；所有40点仍参与最终翻折/面积保护。局部遮挡不再因一个角失败就必然撤销其他可靠形变，仍可能因证据/几何不足回全局结果。不是唇部分割或遮挡渲染保护。
- `beauty_bridge.cpp`：完整内外40点改为20个环带四边形、120顶点三角网格；顶点Shader以gl_VertexID从40点生成位置，片元仅计算本段内/外两条边距离，替换逐像素40条边的射线/距离循环。保留1像素内侧羽化、素材色相/原始明度、口腔/alpha、2D/OES采样矩阵及宿主状态恢复；脸颊/睫毛原偏移不变，VBO1440/FBO容量不变，无新增纹理读回、生产glFinish或等待。前24个float历史包围框保留布局但新绘制不消费。
- 模型、资源包、UI和公共API不变；不改相邻帧链、150/300ms有效期、真实时间戳、模型无脸/reset失效或有界调度。匹配预算减少不等于所有CPU阶段耗时稳定减半。

复现与对照：

- 同一`LipMotionDeviceTest#oneOccludedCornerKeepsSupportedLipDeformation`生成夹具，仅将单角19×19区域变无纹理、其他嘴部按已知展开；两机旧实现均失败：全部40点平均误差1.05324px，等于仅全局结果，说明局部整组被撤销。修复后同一断言（局部误差<全局65%）通过，平均误差0.05377px。原失败证据保留：[OPPO](evidence/lip-motion-regression-before-20261031/5ef48cf2.txt)、[Pixel](evidence/lip-motion-regression-before-20261031/39061FDJH00D3J.txt)。这不是标注真人误差，也不代表被遮挡点可准确识别。
- 720p同一合成口红夹具，8次暖机＋40次完成绘制，`glFinish`仅在测试内使用。OPPO旧→最终：完整Renderer GPU中位8.416→1.137ms，完成绘制中位11.881→2.908ms，P95 15.232→3.448ms，GPU disjoint均0。一次按旧→新顺序的短测，含背景复制/Renderer工作，非纯口红片元、持续热性能、相机FPS或曝光到显示时延；不能消除频率/负载变化影响。中间专项复测GPU1.076ms，方向一致。Pixel完成绘制中位2.884→2.246ms，但EXT查询多次disjoint（旧39/最终27），其少量GPU值不作为性能提升结论。
- 既有独立Canvas唇环覆盖测试6组（三尺寸96×128/640×480/1280×720、张/闭嘴）全部通过；排除2像素栅格边界后唇内>99%、唇外/口腔漏色0、alpha173。真实公开portrait夹具、腮红/睫毛/美型组合与输入变换回归通过，不是用户脸部视觉验收。
- 新增`fastTranslationKeepsFullContourAcrossAdjacentFrames`：8帧各8px、间隔33ms、合计64px平移，叠加嘴部0/2px独立运动，每步匹配相邻帧；两机最大逐帧40点平均欧氏误差0.12916px，鼻点<2px检查通过，估计均值OPPO3.736ms/Pixel3.340ms。生成纹理非快速真实侧脸/模糊。原平移/张嘴/旋转镜像/静止/弱纹理和相邻模型校正测试保留。

执行证据：

- 最终[OPPO PCAM00/API30](evidence/lip-motion-regression-fixed-20261031/5ef48cf2.txt)45/45，131.631秒；[Pixel8/API36](evidence/lip-motion-regression-fixed-20261031/39061FDJH00D3J.txt)45/45，86.135秒。14类范围同本轮计划，短测原图/检测/检测美颜各5秒，仅功能冒烟，无真人覆盖不能当持续性能。
- 修复前专项每机6项（5通过/1复现失败），修复后专项每机6/6通过，最终每机45/45通过。合计114次执行112通过/2次旧码预期复现失败，原日志不覆盖；不等于114个不同用例。基线只推进最终实测通过方法。
- 核心148项中147通过/1跳过，GPUPixel4/4通过；Debug和测试APK、两个后端Release AAR、app lint（0错误/214警告）及无正式签名Release/R8构建通过；结构/workflow config门禁通过。自有native双ABI Debug/RelWithDebInfo共8份ELF LOAD静态16KB、Debug/Release APK zipalign通过，不代表全部依赖或16KB页设备运行。Release未设备运行。
- 每轮覆盖安装均备份恢复私有files/shared_prefs，OPPO503/Pixel507份摘要一致，最终安装APK与本地Debug SHA256一致；未卸载/pm clear，未保存/上传相机画面。无UI更改，不借用字体/主题矩阵为本轮证据。

剩余：真人快速转头/运动模糊/口型及遮挡、重定位跳变、端到端时延/长期温升仍未验收；历史MediaPipe GPU重建SIGBUS和OPPO切分辨率偶发超时根因仍未关闭，本轮未重跑分辨率切换类。通用模型点位和实验二维块匹配仍有能力边界，未引入专用唇部分割或实际直播厂商。

命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；`gradlew.bat :core:lib_beauty_gpupixel:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false`；设备`adb -s <serial> shell am instrument -w -r -e class <14类/专项类> -e soakSeconds 5 com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。旧码专项不执行短测/不读取旧短测结果。未提交源码指纹`76744f7fbeeaf2594be484fe6a7c7c8b7fef7822d31ac0998a22ab08314672c5`，逐文件/方法、安装/native和前后量测见[本轮记录](evidence/lip-motion-regression-fixed-20261031/results.json)。旧生产/测试源码摘要见[evidence/lip-motion-regression-before-20261031/source.json](evidence/lip-motion-regression-before-20261031/source.json)。

---

## 2026-10-31 完整唇缘与嘴角贴合

结论：按用户确认“覆盖大部分但唇边/嘴角不对”优化现有MediaPipe＋GPUPixel，不新增模型/依赖。OPPO与Pixel本轮各43项、合计86次设备执行通过，0失败；新版已覆盖安装，两机APK摘要与本地Debug一致。不能把生成轮廓正确等同于真人模型唇缘准确率通过。

根因与修改：旧实现仅20个唇点（外12/内8），规范模板UV及mouth.png白底色度同时裁剪实际覆盖，容易限制嘴角和唇缘；局部跟踪也只更新该20点。现使用官方外20/内20完整轮廓，包围框仅限定片元范围，Shader以内外环有符号距离定义唇区、排除口腔并在内侧羽化1个输出像素；素材仅供代表色相，保留原唇明度。美妆仍在美型之前统一变形，并未把渲染顺序当成已证实的错误。JNI签名/公共参数/资源包不变，复用原1440数组、脸颊240/睫毛288偏移、FBO与VBO，无新增全分辨率中间图。

跟踪：局部匹配补齐40点，至少28点及全部4个内外嘴角须通过图像/前后向检查；失败点仅在同一内/外轮廓内插值，原翻折/面积检查继续保留。全局运动、相邻帧接力、真实时间戳及150/300ms有效期不变。局部证据不足回原全局结果，未实现语义唇部分割、遮挡或三维跟踪。

像素结果：新增GpuPixelDeviceTest#fullLipContoursCoverInteriorWithoutLeakingAcrossEdges，以独立Canvas偶奇填充为参考，覆盖倾斜非模板唇形、96×128/640×480/1280×720、张嘴/闭嘴6组；排除2像素栅格边界后，两机全部唇内覆盖>99.98%，唇外及口腔漏色0，alpha保持173。张嘴三尺寸分别402/402、19954/19954、63172/63172；闭嘴最差OPPO高清86809/86819，闭合退化内环唇缝存在少量差异，保留断言原门槛>99%，不宣称100%所有像素。现有公开portrait.jpg真实478点检测＋妆面合成通过；人工检查其对照图保留牙齿/口腔，但不代表用户人脸、侧脸或自然度验收。

局部运动生成夹具（不是实际模型准确率）：两机40点平移平均误差0.01292px，相对原全局结果0.16285px；局部张嘴0.16938px，相对1.05935px；旋转镜像、静止与弱纹理拒绝通过。估计均值OPPO约3.00～3.24ms、Pixel约2.72～3.87ms，单次320灰度合成短测，不代表持续CPU/GPU或端到端。新增40线段Shader只在嘴唇包围框执行，仍有额外GPU成本；未做热稳态性能对照。

执行：核心148项中147通过/1跳过，GPUPixel4/4通过；Debug及测试APK、两后端Release AAR、app lint通过（0错误/214警告），无正式签名Release/R8构建通过。两自有native双ABI Debug/RelWithDebInfo共8份ELF LOAD静态16KB及Debug/Release APK zipalign通过，不等于16KB设备运行或全部依赖认证。结构/workflow config门禁通过。无UI修改，不扩展到字体/主题矩阵。

设备：[OPPO PCAM00/API30](evidence/lip-contour-20261031/5ef48cf2.txt)43/43，用时130.515秒；[Pixel8/API36](evidence/lip-contour-20261031/39061FDJH00D3J.txt)43/43，用时82.762秒。14类包括相邻帧链、唇部、美妆执行与实际相机、纹理桥、整脸运动、GPUPixel、内容对齐、回调隔离、取消贴纸、背景兼容、三组各5秒功能冒烟、3D及配置恢复。冒烟缺真人有效覆盖，不能当长测。私有files/shared_prefs摘要恢复一致：OPPO503、Pixel507；未卸载/pm clear，未保存/上传相机画面。仅读取既有公开portrait夹具测试产物。

范围限制：上轮OPPO分辨率切换偶发超时未在本轮复跑或修复；历史MediaPipe GPU重建SIGBUS根因仍未关闭。真人唇缘、模糊/侧脸/遮挡、长时热性能、正式签名Release设备、16KB运行和直播厂商未验收。通用478点仍是当前精度上限，不能由轮廓渲染通过承诺专业级唇部分割效果。

命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；`gradlew.bat :core:lib_beauty_gpupixel:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false`；设备`adb -s <serial> shell am instrument -w -r -e class <14类> -e soakSeconds 5 com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。未提交源码指纹`d21e3f6d0b8edcbc11d7054a031f9f425ccf7135ebed644cfbdde553b8ab58cf`，逐方法/文件摘要、native及安装核对见[本轮证据](evidence/lip-contour-20261031/results.json)。仅推进本次实际通过基线。

---

## 2026-10-31 相邻帧接力跟踪与晚到模型校正

结论：本轮优化既有MediaPipe跟踪管线，没有新增模型/依赖/native/API或视频延迟缓冲。TrackedFaceAnalysis由旧模型参考直接匹配当前帧，改为相邻已处理帧接力；晚到模型通过最多8张独占灰度图重放到当前分析帧，成功才校正原连续链。明确匹配失败后不继续显示冻结旧位置，以STALE屏障等待新模型恢复。仍为实验性二维跟踪，不宣称真人快速转头已解决。

实现与边界：环形槽只在worker内复用，不覆盖仍作为相邻帧参考的内存；新模型每份最多尝试一次重放，保留有界单在途/最新待处理槽。校正失败可继续仍有效的原连续链，原链失败后停止；保留真实模型时间及默认300ms校正期限、调用者150ms结果期限。模型无脸/错误/重置/方向和几何变化保持失效语义，不在采集或GL线程等待。新增历史最多8张小灰度图，NativeFaceMotion方形320细节+160整脸合计约1000KiB槽容量，另有原模型参考及RGBA支路，非进程总内存实测。画面仍使用当前视频帧，异步结果可能落后，尚未实现严格同帧显示；失败屏障可能短暂隐藏，不能把隐藏当准确率提高。

源码：core/lib_beauty/src/main/java/com/beautysdk/TrackedFaceAnalysis.kt；对应JVM测试增加相邻小步累计位移、模型延迟重放、8槽复用、失败失效/新模型恢复，并保留原模型交接、无脸、reset、过期、背压及公共Session用例。新增app/src/androidTest/java/com/beautysdk/demo/AdjacentMotionDeviceTest.kt，用公开固定portrait.jpg的首帧真实MediaPipe关键点，8帧每帧横移4px、30ms间隔、后续不调用模型。每帧必须输出真实对应时间、模型仍为首帧，水平鼻点误差小于图宽1.5%。两机均累计32px位移、最大鼻点误差2.3266125px、末帧模型参考年龄240ms；不是唇部误差、真实速度测试或端到端时延。

本次执行：核心148项中147通过、1跳过、0失败；Debug/APK/androidTest、核心Release AAR、lint通过（0错误、214警告）；app无正式签名Release/R8通过、未设备运行Release。结构/workflow config门禁通过。自有native ELF双ABI静态16KB LOAD与Debug/Release APK zipalign通过，源码未改native，不代表全部依赖16KB设备运行验收。

设备执行与失败记录：

- OPPO PCAM00/API30（5ef48cf2）完整15类45项：44通过、1失败，用时187.564秒。失败为CameraResolutionDeviceTest#presetsSwitchRealFramesBothLensesAndCanReturnToSd在SD/BACK等待超时，仍收到FRONT旧统计；过程未发生此次应用崩溃。原始[完整执行](evidence/motion-chain-20261031/5ef48cf2.txt)保留，不写成完整通过。
- OPPO同源码单独重跑CameraResolutionDeviceTest，3/3通过，用时25.169秒，未改代码、超时或断言。[复测](evidence/motion-chain-isolated-20261031/5ef48cf2.txt)。单独通过不能关闭完整回归偶发切摄像头超时的根因。
- Pixel8/API36（39061FDJH00D3J）完整45/45通过，用时93.457秒。[执行结果](evidence/motion-chain-20261031/39061FDJH00D3J.txt)。

合计93次设备执行：92通过、1失败；不是93个不同用例。双机专项、既有唇部、模型/纹理桥、美妆相机、分辨率、贴纸、3D、背景兼容、配置恢复及3组各5秒原图/检测/检测美颜功能冒烟按上述范围执行。冒烟无有效真人覆盖，不能当持续性能。私有files/shared_prefs摘要恢复一致：OPPO503份、Pixel507份；未卸载/pm clear。最终Debug APK两机安装哈希与本机构建一致，见[安装核对](evidence/motion-chain-20261031/installed-apk.json)。未保存或上传相机图像。

剩余：真人快速移动/转头/张嘴/遮挡及弱纹理/模糊、模型校正跳变和失败隐藏频率、曝光到显示延迟/尾延迟、长时温升、多设备质量矩阵、既有MediaPipe GPU重建SIGBUS，以及本次OPPO切摄像头超时根因均未关闭。未引入金字塔LK、专用唇部模型或分割；未接实际直播厂商。UI未改，不冒用历史全方向/字体矩阵结果。

命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；`gradlew.bat :app:assembleRelease -PrequireReleaseSigning=false`；设备`adb -s <serial> shell am instrument -w -r -e class <15类或CameraResolutionDeviceTest> -e soakSeconds 5 com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。工作区未提交，源码指纹`f64fb82a32ec3088c491f9cd865c1d5977740727954645d8c88c5a7ce2e38a9d`，逐文件摘要和逐方法/设备证据见[本轮记录](evidence/motion-chain-20261031/results.json)。基线仅推进本次实际通过的方法，原始完整失败仍保留。

---

## 2026-10-30 唇部局部运动补偿（现有模型）

结论：按用户要求复用现有MediaPipe，新增嘴唇局部图像匹配，Demo显式开启，仍尊重实验跟踪开关及3D互斥。最终OPPO/API30与Pixel8/API36每机44项，共88次设备执行通过，0最终断言失败、0受阻，Debug覆盖安装，私有文件/偏好摘要恢复一致，未卸载/pm clear。重要剩余：首次完整OPPO执行在相机重建时发生MediaPipe GPU原生SIGBUS，后续单独重建及同源码OPPO完整44项重跑通过，根因仍未确定，不宣称崩溃已经修复。真人唇形/快速侧脸观感未验收。

实现：NativeFaceMotion保留原160灰度整脸相似变换，增加默认false的enableLipTracking及兼容无参构造。开启时每个独占MotionImage附加最长边320灰度，仅在整脸匹配成功后，对20个唇部网格点在整脸预测附近±4像素匹配，使用弱纹理/搜索边界/前后向1.2像素检查。至少14点通过才插值缺失点；三角网格翻折或面积比超.25～3整组撤销，失败回原整脸结果。亚像素峰值拟合保留参考点小数，避免静止时吸附到像素网格。模型直接校正到新图，不串联积累漂移，不按时钟外推，不改150/300ms年龄。仅修改唇部点和相关嘴部锚点，整脸/其他区域使用原结果；无新模型或依赖，没有相机像素落盘。

MotionImage开放继承以承载后端私有DetailedImage，保留原构造签名；公共NativeFaceMotion默认不开启局部补偿。新增灰度及局部匹配有额外CPU/内存成本，不宣称零成本、三维跟踪或唇部分割。大角度转头、模糊和弱纹理仍可能无法匹配。

测试：新增LipMotionDeviceTest四项，生成纹理验证嘴部独立移动、上下唇展开、物理旋转并镜像输入，以及静止小数坐标/弱纹理遮挡拒绝/异常大坐标安全旁路。对照原算法，误差须小于原算法65%，头部锚点不应用局部残差。该夹具证明已知局部运动下接口与算法有效，不代表真人纹理或模型精度。首轮两机各4项（新增最初3项＋原FaceMotion1）通过，最终每机44项包含完整新增4项、原13类40项，覆盖真实美妆相机、分析代次、GL状态/时间戳、实际内容/分辨率、3D/背景/贴纸及配置恢复。

局部估计数值（生成320纹理，误差为20个唇点平均像素差，时间为整体estimate含原整脸；每组8次）：

5ef48cf2: LIP_LOCAL deform=false rotatedMirrored=false baselineErrorPx=0.16284561157226562 localErrorPx=0.01336822509765625 meanMs=3.3756185000000003 maxMs=3.592344
5ef48cf2: LIP_LOCAL deform=false rotatedMirrored=true baselineErrorPx=0.16284561157226562 localErrorPx=0.01336822509765625 meanMs=2.7162824999999997 maxMs=2.857917
5ef48cf2: LIP_LOCAL deform=true rotatedMirrored=false baselineErrorPx=1.129889678955078 localErrorPx=0.06167526245117187 meanMs=2.639811 maxMs=3.001458
39061FDJH00D3J: LIP_LOCAL deform=false rotatedMirrored=false baselineErrorPx=0.16284561157226562 localErrorPx=0.01336822509765625 meanMs=2.7677052499999997 maxMs=3.310669
39061FDJH00D3J: LIP_LOCAL deform=false rotatedMirrored=true baselineErrorPx=0.16284561157226562 localErrorPx=0.01336822509765625 meanMs=2.555073875 maxMs=2.597982
39061FDJH00D3J: LIP_LOCAL deform=true rotatedMirrored=false baselineErrorPx=1.129889678955078 localErrorPx=0.06167526245117187 meanMs=2.662073625 maxMs=2.815796

三组原图/检测/检测美颜各5秒冒烟数值保留于同目录soak.json，无有效真人覆盖，不能当持续性能或快速唇形画质通过。实际模型/纹理读回对照只作为共享链路回归，不将固定图样测量当端到端美妆延迟。

执行`:core:lib_beauty:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`通过；核心147项146通过、1跳过、0失败，其他模块JVM本轮不冒用历史结果。`:core:lib_beauty_mediapipe:assembleRelease :app:assembleRelease -PrequireReleaseSigning=false`通过，Release/R8无正式签名、未设备执行。lint 0错误/214警告。代理结构与Android workflow config检查通过；布局未改，未新增全字体/方向/明暗UI矩阵。

Native：双ABI Debug/Release libbeauty_rgb_input.so LOAD静态16KB对齐和Debug/Release APK zipalign检查通过，结果见[静态对齐](evidence/lip-local-delivery-20261030/native-alignment.json)，不代表16KB页设备验收。原生新增循环有有界搜索、直接缓冲容量检查，无全图光流或深度模型。

失败与剩余：首次完整OPPO已完成25项后，在CameraResolutionDeviceTest#selectedResolutionSurvivesRecreationAndMenuRemainsChecked崩溃，原始栈位于MediaPipe drishti_gl_runn，SIGBUS，不在新增匹配代码；仅凭栈位置不能排除本次改动影响。[失败结果](evidence/lip-local-final-20261030/5ef48cf2.txt)与[崩溃栈](evidence/lip-local-final-20261030/oppo-crash.txt)保留，未推进通过基线；[单独重建复测](evidence/lip-local-recreate-20261030/5ef48cf2.txt)通过，随后同源码OPPO完整44项通过。该轮Pixel为42/44：分析桥接器初始化失败后，测试try范围未覆盖初始化，导致相机未finish并影响后续原图短测；已把finally扩展到初始化路径、保留原断言，加入JNI越界坐标拒绝后重新构建双机完整回归，最终88项通过。该偶发问题、真人快速转头/张嘴/遮挡、长时双分析性能及正式Release设备均未关闭资格，不因重试通过改变状态。

工作区含未提交源码，指纹`6406a546ab96956e1e0d67e2cd04f8013f9c484a5a35493757d83547f3da4f2f`；HEAD、逐文件摘要、APK摘要、逐方法/设备结果和恢复摘要见[最终结果](evidence/lip-local-delivery-20261030/results.json)。新增本轮独立基线，历史用例不覆盖；私有备份只在build/functional-test对应目录。设备命令`adb -s <serial> shell am instrument -w -r -e class <14类> -e soakSeconds 5 com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。

---

## 2026-10-30 美妆快速移动跟随：减少分析链路延迟

结论：针对用户确认的“移动/转头，口红跟不上嘴唇”，已优化等待与采样链路。OPPO/API30、Pixel8/API36每机40项，共80次最终设备执行通过，0失败、0受阻，Debug覆盖安装两机。两机私有文件/偏好摘要恢复一致，未卸载或pm clear；没有保存或上传相机画面。仍未通过真人快速侧脸/张嘴时的局部唇形贴合验收，不能把本轮通过表述为动态画质问题全部解决。

原因和修改：原PBO只在下一视频帧取回，增加一个采集周期；33.33ms采样门槛遇到约33.327ms帧间隔会误降为隔帧。GlTextureAnalysisBridge新增同GL上下文poll，Demo每个真实采集帧尝试小图采样并在8ms后非等待轮询，不额外高清绘制。双PBO、检测及跟踪单执行＋单可替换最新待处理槽提供背压。局部美妆/3D自动优先GPU检测，自动GPU仅初始化失败回退CPU，公共默认CPU不变；正数强度变化及原图切换不反复重建检测偏好。保持真实采样时间与150ms/300ms年龄边界，不使用生产glFinish或等待推理，不修改原生运动算法。

验证：新增跟踪队列替换、GPU需求判断JVM；两模块163项中162通过、1跳过、0失败，跳过不推进通过基线。设备13类40方法/机，包含公开人像CPU/GPU对照、真实相机GPU及20次强度变化不重建分析代次、TextureBridge6、FaceMotion1、GPUPixel9、内容对齐1、分辨率3、回调隔离1、贴纸取消2、背景兼容3、三组短测1、3D10、配置记忆1。早轮询夹具验证真实时间戳、GL状态恢复、禁用/reset/过期和33,327,109ns间隔三帧均采样；测试glFinish仅用于确定性功能断言，不进入耗时测量。双机前后摄三档内容比对严格同时间戳，缓存最多12份RAM灰度，相关性>.985/尺度改善<.01阈值不变。

数值对照见下方原始摘要。READBACK使用640×360生成纹理缩至320小图、14份样本；只是桥接阶段，不是端到端嘴唇延迟。MAKEUP_EXECUTION使用公开256×320人像，CPU/GPU各25次去前5次；短样本受设备状态影响，不能保证所有手机相同加速。相机原图/人脸手势/人脸手势美颜三组各5秒，明确同GPU、跟踪关，无有效真人覆盖，故仅作为启动/运行冒烟，不能证明持续性能或真人负载FPS。

5ef48cf2: MAKEUP_EXECUTION backend=CPU samples=20 meanMs=123.82722654999998 p50Ms=126.402135 p95Ms=141.302865
5ef48cf2: MAKEUP_EXECUTION backend=GPU samples=20 meanMs=44.5307006 p50Ms=42.677135 p95Ms=54.213021
5ef48cf2: READBACK mode=next_frame samples=14 meanMs=34.93502242857143 p50Ms=34.955417 p95Ms=35.456615
5ef48cf2: READBACK mode=early_poll samples=14 meanMs=12.223236714285715 p50Ms=12.313802 p95Ms=12.691771
39061FDJH00D3J: MAKEUP_EXECUTION backend=CPU samples=20 meanMs=108.6745932 p50Ms=105.056884 p95Ms=155.004924
39061FDJH00D3J: MAKEUP_EXECUTION backend=GPU samples=20 meanMs=122.16084409999999 p50Ms=127.707642 p95Ms=140.256062
39061FDJH00D3J: READBACK mode=next_frame samples=14 meanMs=35.089053 p50Ms=34.823975 p95Ms=36.094807
39061FDJH00D3J: READBACK mode=early_poll samples=14 meanMs=12.141005857142858 p50Ms=12.083455 p95Ms=14.359294

执行`:core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，以及`:app:assembleRelease -PrequireReleaseSigning=false`通过；Release/R8无正式签名、未设备执行。lint 0错误/214警告；UI/i18n门禁0警告，架构4项既有提醒。本轮无native变更，未复跑16KB页检查。设备命令`adb -s <serial> shell am instrument -w -r -e class <13类> -e soakSeconds 5 com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。

失败过程保留：先前OPPO完整40项39通过/1失败，旧内容测试跨时间戳比较导致FRONT/HD相关性.86056，不能视作坐标通过。改用同时间戳样本、保持原阈值，再重新执行双机完整80项通过；先前日志在makeup-follow-20261030，不混入最终通过基线。该问题属于跨帧测试不能可靠区分内容错位和运动/曝光，并非以降低阈值规避失败。

源码含未提交修改，HEAD、逐文件摘要、APK摘要、实际通过方法、双机完整日志及三组数值见[本轮证据](evidence/makeup-follow-verified-20261030/results.json)，工作区指纹`f6bb33168719e316934c64e772e01266cf4b1fb3eeadbd283b72345d4fdece15`。私有备份仅存build/functional-test/makeup-follow-verified-20261030。独立新增本轮基线，不覆盖其他历史用例。未验收：CPU初始化失败回退故障注入、局部非刚性嘴唇跟踪、真人快速转头/张嘴/遮挡、长时温升与双分析负载、其他机型及实际直播厂商；现有整脸二维相似变换无法完整描述上述局部变形。

---

## 2026-10-30 贴纸首次启用、再次点击关闭

结论：已实现用户要求，相机2D贴纸卡片首次点击启用，再点同一张取消该槽位；3D六款再次点击关闭当前模型，并清除之前暂停的2D，避免旧贴纸恢复。取消结果持久化。OPPO/API30与Pixel8/API36每机7项，共14次设备执行全部通过、0失败、0受阻，Debug已覆盖安装两机。私有文件/偏好分别503/508份摘要恢复一致，未卸载或pm clear。

修改：ResourceCenterController仅相机STICKER卡片显式传toggleSticker，ResourceCenterModel在既有串行IO队列中依据最新state判定，避免快速双击使用旧UI快照造成两次启用。管理页详情中的显式应用保持原语义。新增ToggleStickerRadioButton，仅替换既有6个3D控件类型，保留XML尺寸/样式/ID和RadioGroup程序化互斥/恢复；用户重复激活走既有统一清除入口，支持普通点击和无障碍ACTION_CLICK。贴纸卡片取消不再弹“已应用”提示，其他类别提示保持原行为。SDK、native和资源格式未改。

测试：新增StickerToggleDeviceTest两项，真实卡片启用/取消及角标、连续快速双击、保留磨皮/美白值、关闭后冷重开，以及六款3D真实点击/无障碍点击、切换互斥、渲染options停用、清理2D残留和冷重开。回归Sticker3DUiDeviceTest 1、ResourceExperienceDeviceTest 3、CameraConfigurationDeviceTest 1，覆盖原3D记忆、2D/3D页面恢复、资源详情不自动应用、参数面板和手势重复点不误关。手势仍保留原语义，本轮只更改2D/3D贴纸。

执行`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`通过；测试辅助代码初次编译的Dialog tag查询错误已修正后重建成功。lint 0错误/214警告；UI/i18n门禁0警告，架构4项既有standalone/probe提醒。设备命令为`adb -s <serial> shell am instrument -w -r -e class <上述4类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。没有新增JVM测试，本轮不冒用前轮JVM/Release/16KB结果。

源码含未提交修改，指纹`8449dfd0a0aabeda7133a36741e5b2f46832a11b6bab5fdcdf3f0b57853da19c`，逐文件摘要、APK摘要和实际通过方法见[本轮结果](evidence/sticker-toggle-20261030/results.json)，同目录保留双机完整结果、恢复和构建摘要。私有备份只在build/functional-test/sticker-toggle-20261030。未新增相机画面采集或上传，既有资源UI测试仅绘制面板View。

本轮未复跑完整横屏/大字体/明暗矩阵（控件沿用原尺寸），未验收真人画质、持续性能或Release设备；这些不因点击回归通过而改变状态。

---

## 2026-10-30 高清静止贴纸错位：实际分析与显示内容统一

结论：已修复Demo分析输入与显示纹理尺度不一致的来源。OPPO PCAM00/API30、Pixel8/API36前后摄SD/720p/1080p共12个内容对齐点通过；双机各28项加3次横屏/字体专项，共59次最终设备执行通过、0失败、0受阻。原有私有文件/偏好分别503/508份摘要一致，专项字体与旋转原样恢复，覆盖安装、未卸载或pm clear。最终Debug安装在两机。未保存或上传相机像素。

### 根因与修改

用户确认静止仍错位，排除仅以跟随延迟解释。前轮测试只检查宽高比及尺寸，不能证明独立ImageAnalysis RGBA与OES实际内容对应。本轮先核对sensor crop元数据，两路一致或仅舍入差；继而仅在RAM比较内容，旧输入在OPPO高清需要约1.33倍中心尺度补偿才对应，Pixel也存在较小差异。不能凭这些证据进一步断言特定CameraX/HAL内部实现原因，也不能用OPPO固定系数套所有设备。

`GpuBeautyPreview.kt`改为从合成前同一OES纹理及normalize变换，经已有GlTextureAnalysisBridge最长边320缩图，向人脸/手势/背景运行时同步复制后异步处理。独立相机RGBA不再消费；CameraX低分辨率use case仍保留协商。双PBO/fence后续帧只轮询已完成小图，换源/分辨率切换重置，GL同上下文释放，context丢失不误删新句柄。不读回高清全图、不等待模型，不延长结果有效期；有GPU读回成本，不是零拷贝或性能提升证明。公共SDK默认契约未改，前轮磨皮短边归一化修改保留。

### 实测结果

- 新内容用例比较实际分析回调与独立参考采样，前后摄各三档；相关性必须>.985，中心尺度0.5～1.5搜索的改善必须<.01。最终12点最佳scaleX/scaleY均1.0：OPPO相关性0.99855～0.99957，Pixel0.99969～0.99991。保留真实时间戳，配对间隔约67～100ms；这是内容坐标证据，不是端到端延迟/FPS测量。
- 8类设备回归每机28项：CameraContentAlignment 1、CameraResolution 3、CameraConfiguration 1、PreviewCallbackSafety 1、TextureBridge 4、Sticker3D 10、GestureEffects 5、ForegroundMaskCompatibility 3。覆盖旋转镜像/过期/真实ES3深度与动画、原图暂停、分辨率记忆/快速切换、回调隔离及背景消费者兼容；3D测试含合成脸/公开夹具，不等于真人帽子观感通过。
- UI专项3次：OPPO字体1.0横屏、Pixel字体2.0竖屏/横屏，新会话记忆及诊断末行可达。未新增全系统明暗主题矩阵。
- 本轮JVM三模块174项：173通过、1跳过、0失败；跳过不推进通过基线。Debug、测试APK、无正式签名Release/R8、lint通过，lint 0错误/214既有警告。UI/i18n门禁0警告，架构4项既有standalone/probe提醒，代理结构/config通过。本轮未修改native，未复跑静态16KB检查或Release设备运行。

### 失败过程保留

最初元数据/内容诊断的OK只证明采样完成，不能算旧版本对齐通过。首次新回归在无触摸采样时息屏，GL上下文检查失败；测试finally因此未finish相机，后续出现MediaPipe原生SIGBUS。已改为仅测试窗口保持唤醒，且GL清理异常也先finish相机再恢复文件。该发生顺序不能证明生产SIGBUS的唯一原因；后续完整回归未再出现。证据保留在sticker-alignment-fixed-20261030。

第二次完整OPPO执行27/28通过，后摄FHD相关性0.984724低于0.985，最佳尺度已为1.0；参考160与实际320缩图带来不同高频混叠。改为同320参考采样，保持阈值不变，最终双机59次通过。失败证据保留在sticker-alignment-verified-20261030，不合并成最终通过数据。

### 命令与证据

执行`:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug -PrequireReleaseSigning=false`，以及`:feature:feature_app:testDebugUnitTest :core:lib_camera:testDebugUnitTest :core:lib_beauty:testDebugUnitTest`。设备使用`adb -s <serial> shell am instrument -w -r -e class <上述8类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`；专项使用CameraResolutionDeviceTest的selectionPersistsAcrossNewSessionAndDiagnosticsCanBeRead，附uiRotation/uiExpectedFont参数。

源码含未提交修改，HEAD和逐文件指纹见[本轮结果](evidence/sticker-alignment-final-20261030/results.json)，工作区SHA256为`5492bbdcabc7a534350218f7cc04fd6783a84a92b4ab89b47aec8f3f3b1df836`。双机[OPPO内容数值](evidence/sticker-alignment-final-20261030/5ef48cf2-content.txt)、[Pixel内容数值](evidence/sticker-alignment-final-20261030/39061FDJH00D3J-content.txt)，同目录包含完整设备结果、尺寸、恢复摘要、matrix及构建摘要。原始日志/私有备份只在build/functional-test对应目录，未复制私有文件至Git。

剩余未验收：真人贴纸静止观感、快速运动和长时3D闪烁、持续FPS/读回成本/温升、其他厂商、Release/16KB页设备、真实直播引擎。已建立本轮实际通过方法及设备的独立基线，不覆盖历史未复测用例。

---

# 2026-10-30：跨分辨率磨皮、档位记忆与诊断适配

已完成：GPUPixel磨皮采样间距按输出短边相对480缩放，保留640×480原间距；相机更多菜单提供640×480/1280×720/1920×1080真实请求并重绑，独立分析继续请求320×240小图，共同视场crop后显示真实视频/分析尺寸。显式选择持久化到Demo私有偏好，首次SD；不重置效果参数，不改变SDK检测有效期。诊断卡受相机区域高度约束并可滚动，分辨率文字自然换行。

最终OPPO/API30与Pixel8/API36各14项常规回归共28次通过，另OPPO默认字体横屏、Pixel2.0字体竖屏/横屏3次通过，共31次、最终0失败。包含前后摄三档/返回SD/快速重绑、小分析图和宽高比、原图暂停、重建/新会话记忆、诊断末行可达、手势3D记忆/回调隔离及全部9项GPUPixel用例。同一连续合成纹理640×360/1280×720/1920×1080归一化探针平均RGB差：OPPO0.1944/0.1508，Pixel0.2023/0.1740（0～255）；磨皮像素确有变化且alpha保留。不是用真人画面计算，也不是全效果画质验收。

JVM四模块178项：177通过、1跳过、0失败。Debug/测试APK、显式无正式签名Release/R8构建成功；lint0错误214警告，UI/i18n0警告，架构4项既有独立示例/测量工程提醒。GPUPixel arm64/v7a Debug/Release四份ELF和Debug/Release两份APK的16KB静态对齐通过。最终Debug已安装两机；用户文件/偏好（OPPO503、Pixel508份文件）与矩阵字体/旋转设置恢复摘要一致，无卸载/pm clear/相机帧保存。

过程记录：首轮ADB传输中断，重连并核对原备份后重跑。初始横屏测试实际仍竖屏，断言失败保留在matrix-initial及matrix-before-wake-fix；相机退出回到桌面/锁屏后，原测试只在整项之前设置旋转不足。测试修正为每次重开前唤醒/解锁并冻结旋转，保留真实方向及文本布局断言，随后专项与最终常规组全部通过。没有放宽画面可达断言。旧固定像素间距的高清磨皮摘要只属于历史实现，不沿用为本次输出基线。

未执行/未验收：真人肤质/3D闪烁、不同视场/人脸占比的一致性、锐化等细节滤镜跨尺寸一致性、长时FPS/温升、其他机型协商/失败矩阵、OPPO2.0字体、完整系统明暗主题矩阵、Release设备运行、16KB页设备运行和真实直播引擎；不宣称提升FPS或正式可发布。

使用与尺寸表见[Demo分辨率测试](../DEMO_RESOLUTION_TEST.md)，本次[结果、逐方法与源码指纹](evidence/resolution-adaptation-20261030/results.json)、[Native静态检查](evidence/resolution-adaptation-20261030/native-alignment.json)。本次工作区源码指纹95f67241965a760d3d8bdc8cd54b533f7fe7e6a093acb67ef1e0e6e1d6792d99。下面章节是历史证据，当前相关功能以本节为准。

# 2026-10-30：演示相机分辨率测试入口

更多菜单新增640×480/1280×720/1920×1080请求档位，真实重绑CameraX；主画面显示请求、实际视频crop、分析输入尺寸，配置重建保留选择，冷启动默认SD。预览按请求选择4:3/16:9，分析仍独立请求320×240，通过共同ViewPort保持同视场，避免OPPO原生16:9分析流升到720p。原图暂停时不显示旧分析尺寸；保留原有效果参数与SDK需求/有效期。

最终OPPO和Pixel各4项、共8次设备回归通过，覆盖新增2项前后摄三档/返回SD/快速切换/重建菜单选择/分析暂停及已有手势3D记忆、回调故障隔离。OPPO三档实际640×480/1280×720/1920×1080，高清分析320×180；Pixel前摄三档实际640×480/1214×720/1822×1080，高清分析320×190；Pixel后摄与OPPO同尺寸，展示共同视场crop后的真实尺寸。首轮通过中发现OPPO高清分析流被放大，修正独立小图请求后最终整组通过，并加<=640设备断言；不沿用首轮源码基线。

feature_app15＋camera13项JVM共28项通过，Debug/测试APK与lintDebug通过（0错误、212警告），UI/i18n/架构/结构配置门禁通过。两机用户私有文件/偏好按摘要恢复，最终Debug已安装，无相机图像保存。未验证本轮Release/R8、横屏字体完整矩阵、真人跨分辨率画质/3D与持续性能，不把请求尺寸等同于固定输出模拟。

详见[使用与实际尺寸表](../DEMO_RESOLUTION_TEST.md)、[证据和指纹](evidence/resolution-demo-20261030/results.json)。

# 2026-10-30：SDK纹理内存与组合链路优化

GPUPixel改为按需分配全尺寸中间纹理，磨皮差分复用已消费槽位；仅美白/调色由五张降至两张（RGBA存储减少60%），磨皮由五张降至四张（减少20%）。1080p分别39.55→15.82/31.64MiB；不是进程/驱动总内存。组合背景、3D与美颜配置派生值按不可变实例缓存，减少逐帧分配；不降低画质、模型精度或有效期。新增默认关闭的四槽异步GPU计时与缓存纹理字节诊断；不等待GPU，宿主已有同类计时跳过，disjoint结果丢弃并计数。核心Pipeline的GPU字段仍不代表该局部数据。

最终双机各29项，共58次设备测试通过；24组固定输入输出SHA256与优化前逐组相同。核心/GPUPixel JVM150项：149通过、1项既有跳过，失败/错误0。Debug/测试APK、本地未签名Release/R8和lintDebug通过（0错误、212警告）；四个新增编译GPUPixel ELF及两个APK静态16KB对齐通过，未做16KB页/Release设备运行验收。用户文件与偏好恢复摘要一致，最终Debug已安装OPPO与Pixel。

**收益限定为中间纹理存储和重复配置分配减少。耗时短测有升有降，含明显回退组，不能宣称FPS提升或持续性能通过。** Pixel首轮28/29因测试误要求disjoint时也有有效GPU时间；补失效计数与零计数位支持检查，保留像素/宿主查询断言后双机整组通过。独立测量辅助进程重叠恢复的未完成尝试单独记录，不计通过。真人3D闪烁、多模型联合长测/温升、厂商推流仍未完成。

详见[改动、完整数据和限制](PERFORMANCE_OPTIMIZATION_20261030.md)、[结果与工作区指纹](evidence/gpu-memory-20261030/results.json)。

# 2026-10-30：第三方SDK接入增量实测

使用隔离本地Maven仓库发布当前五个同版制品，逐文件核对AAR来源摘要；平台Activity最小宿主共用源码，构建空宿主/完整SDK/SDK＋当前完整资源ZIP三个Release变体，均R8＋资源压缩。测量专用keep防止无调用空宿主裁掉SDK，数字为保守全量口径，不建议客户照搬全量keep。

减去空宿主后，arm64-v8a完整SDK＋传递依赖/内置模型增加20.72MiB，加资源包合计22.92MiB；armeabi-v7a分别17.62/19.82MiB；两种ARM ABI通用包分别27.93/30.13MiB。完整资源ZIP的额外增量均约2.20MiB。确认目标ABI、无Demo/WKQ/CameraX宿主依赖，排除模拟器SO。隔离发布、Release/R8及依赖解析通过；未安装执行测量宿主，不推进运行功能基线，不代表正式签名、授权、模型/渲染或16KiB设备验收。正式业务/构建配置未改。

详见[接入增量与适用范围](SDK_APK_INCREMENT_20261030.md)、[数据与来源摘要](evidence/sdk-increment-20261030/results.json)。

# 2026-10-30：包体测量（无业务源码变更）

当前工作区实际构建分ABI Debug与本地未签名Release（启用R8/资源压缩），arm64-v8a分别35.55/26.48MiB，armeabi-v7a分别32.19/23.15MiB。随后恢复原有双ARM ABI配置实际重建，Debug43.19MiB、Release34.09MiB；逐一核对ABI与对应SO字节一致。临时分包脚本的四ABI universal产物不作为当前项目通用包数据。无正式Gradle配置/业务源码改动，测量APK未安装，功能基线不推进。恢复重建时初次R8遇Windows文件占用，重试成功；没有把首次失败记为通过。此处仅包体与构建结论，不代表Release功能/授权/16KiB运行已验收。

详见[包体实测与构成](PACKAGE_SIZE_20261030.md)、[字节数/ABI/SHA256证据](evidence/package-size-20261030/sizes.json)。

# 2026-10-30：崩溃风险加固与双机回归

根据源码审计修复四条风险链路：手势组合加载逐包传递剩余像素预算，超限包在Bitmap/ARGB分配前拒绝，避免原先最多八包全部解码后才拒绝；旧GestureEffectArchive.loadZip(InputStream)保持，新增预算重载。背景分割onTiming/onError回调异常隔离并记录，错误回调不递归；关闭分割器使用finally继续清理。GpuBeautyPreview对各UI观察者独立保护，错误回调失败记录日志，代次/运行状态仍限制交付；GL释放逐项继续。资源缩略图增加最多32项/8MiB的LRU保留上限，逐出不recycle仍可能由View持有的Bitmap；已有删除清理保留。

核心/资源/feature Debug JVM共173项：172通过、1项既有跳过，失败/错误0。app Debug与androidTest构建、lintDebug通过（0错误、212警告），代理结构/配置门禁通过，定向diff空白检查通过。无native源码、Gradle、Manifest或依赖版本变更。

OPPO PCAM00/API30与Pixel8/API36各最终23项全部通过，共46次、23个不同用例。包括新增3项：资源像素预算0/不足/准确边界，分割onTiming＋onError连续三帧故障注入且后续快照继续，真实相机预览观察者＋错误回调故障注入、其他观察者继续及Activity真正销毁后无UI交付。其余覆盖原后端兼容与真实3D人脸组合、手势包/ES3动画、公共四组手势组合、3D全部10项、资源页3项安装/选择/参数/持久化/删除回归。用户文件与偏好按文件摘要核对恢复（OPPO503、Pixel508个文件），未卸载/pm clear、未采集保存相机画面。最终APK已安装双机。

保留失败过程：首轮OPPO18/19，新测试在finish请求后、实际销毁前断言回调停止，改为等待isDestroyed后保留原断言；扩展轮OPPO21/23，两项旧资源测试默认进入本地列表，实际页面默认为在线页，补上显式本地页签步骤，未放宽业务断言。最终两机同组整批通过。测试准备时OPPO拒绝pm grant，但既有相机权限已授予；改用既有权限。旧toybox不支持本次管道tar原地覆盖，恢复工具改为逐文件摘要比较与复制，已核对原始备份一致。这些尝试不记为通过基线。

未执行Release/R8、真实底层segmenter.close抛异常的故障注入、极端OOM实验、长时间内存/温升测试或其他机型矩阵。组合解码与缩略图有预算不代表整个进程有统一内存硬上限：预览字节、压缩包、当前效果、纹理等仍会并存。SDK不捕获OOM/native SIGABRT，不延长真实人脸有效期；3D真人闪烁仍待验收。未提交/push。

证据：[结果与源码指纹](evidence/crash-hardening-20261030/results.json)、[OPPO最终回归](evidence/crash-hardening-20261030/5ef48cf2.txt)、[Pixel最终回归](evidence/crash-hardening-20261030/39061FDJH00D3J.txt)、[原始审计与后续状态](CRASH_RISK_AUDIT_20261030.md)。

# 2026-10-30：OPPO背景分割GPU原生崩溃兼容修复

用户报告崩溃。OPPO在13:49:42的堆栈明确为SIGABRT：MediaPipe image_frame.cc:298 Invalid format: UNKNOWN，经PacketGetter.nativeGetImageWidthFromImageList进入ImageSegmenter输出转换；不是Java/Kotlin姿态缓存异常。用安装中的旧APK执行公开portrait GPU分割合成用例，独立复现同类原生崩溃（instrumentation进程退出，未算通过），并恢复用户资源/偏好与系统设置。

MediaPipeForegroundMasks对已确认设备OPPO OP46B1（PCAM00、Android11/API30、当前Tasks0.10.32）在GPU任务创建前处理兼容：GPU_PREFERRED直接使用CPU并输出实际执行后端；显式GPU通过onError报告不支持，避免进入无法catch的原生终止路径。其他设备仍原选择与初始化失败回退逻辑。背景功能、连续置信度/软边、真实采样时间、250ms过期、重置与有界队列保留；不是修补上游SO，也不将所有OPPO/GPU描述为有同一问题。公开GL合成测试改用与Demo一致的GPU_PREFERRED，新增用例在Pixel验证实际GPU、在OPPO验证实际CPU，并独立验证显式GPU拒绝/成功分支。

最终assembleDebug、assembleDebugAndroidTest与lintDebug通过（0错误、212警告），代理结构检查通过。OPPO及Pixel各完整20项设备通过，共40次、20个不同用例：新增2项兼容/真实GPU三维人脸与分割组合，每轮12个公开夹具采样、3次reset、连续置信度与过期；场景7项真实模型/公开GL合成/OES/纹理桥/边缘/状态；三维10项；实际相机场景控件1项。OPPO实际相机日志确认兼容警告、execution=CPU及连续蒙版产出，原崩溃公开用例通过。测试后资源/偏好及字体/旋转前后核对恢复。新版已安装双机，未卸载/清空、未保存相机画面。

本轮未重复JVM（修改位于Android平台分割后端、已双机执行对应分支），未执行Release/R8、长期温升或多机型矩阵。3D帽子真人闪烁仍是独立未验收问题，不能用本次崩溃回归称其已解决。未提交/push。

证据：[结果与源码指纹](evidence/segmenter-crash-20261030/results.json)、[修复前公开夹具复现](evidence/segmenter-crash-20261030/gpu-reproduction-instrumentation.txt)、[原生堆栈](evidence/segmenter-crash-20261030/crash-current-day.txt)、[OPPO最终回归](evidence/segmenter-crash-20261030/5ef48cf2.txt)、[实际分割后端日志](evidence/segmenter-crash-20261030/after-segmentation.txt)。

# 2026-10-30：三维姿态与遮挡上传复用优化

用户确认上一版GPU优先候选仍闪烁，继续要求优化。当前OPPO诊断缓冲未包含对应新版三维GPU复现窗口，不能把旧CPU过期观察当作此次已确认根因。代码确认同一真实检测结果跨视频帧时仍重复计算姿态并上传遮挡顶点，本轮在SDK内部新增单条姿态缓存，GlSticker3DRenderer仅在姿态对象变化时上传遮挡网格；减少重复分配/计算及GL上传，不以此宣称闪烁根因已解决或给出未测的性能提升数字。

缓存以不可变三维观测引用、输出几何、贴纸设置为条件；每帧先检查真实采样年龄，未来/无脸/过期清除，关闭三维清除，保持150ms与无脸隐藏契约。新增JVM覆盖复用边界、失效、不同姿态/尺寸/参数；真实ES3新增连续帧像素一致、150ms边界、过期隐藏、新检测恢复及关闭重开回归。原多视角/头部遮挡/旋转镜像/FBO/GL状态/模型三维输出回归保留。

核心JVM146项（145通过、1项既有跳过），失败/错误0；assembleDebug、assembleDebugAndroidTest及lintDebug通过。OPPO与Pixel8各完整10项设备通过，资源/偏好及临时字体/旋转均恢复，未卸载或清空。新版已安装双机。未执行Release/R8、持续热稳定测试，也未取得本优化后的真人闪烁验收；上一版用户失败记录保留，自动夹具通过不能代替真人通过。未提交/push。

证据：[结果与工作区源码指纹](evidence/sticker3d-cache-20261030/results.json)、[OPPO](evidence/sticker3d-cache-20261030/5ef48cf2.txt)、[Pixel](evidence/sticker3d-cache-20261030/39061FDJH00D3J.txt)。

# 2026-10-30：3D帽子因检测延迟过期而闪烁的修复候选

用户确认整顶帽子间歇消失，随后复现期间实时日志12:47:13～16显示有脸窗口ready24/stale1/noFace0，maxAge约160.54ms，超过150ms有效期，触发隐藏后新检测恢复。此前持续NO_FACE的观察不能代表此次复现。原始实时工具输出的关键值转录进results.json，未保存相机画面；环形日志后续导出见before文件，不将其冒充完整复现录制。

OPPO同一公开portrait夹具CPU/GPU各25帧，排除前5帧，CPU平均127.19ms/P95 154.76ms，GPU平均49.11ms/P95 61.69ms。串行短测受负载/温度影响，不能直接当相机端到端耗时或持续性能。基于该结果，仅Demo GpuBeautyPreview三维分析优先GPU，在所属worker创建/使用/释放；GPU初始化异常回退CPU并日志记录实际执行后端。二维原执行选择、SDK公共默认/模型/构造、150ms有效期与无脸/错误/过期隐藏均未更改。

最终feature JVM15/15、assembleDebug/assembleDebugAndroidTest/lintDebug通过。最终OPPO12/12、Pixel8十二项整批通过，覆盖真实ES3帽子视角/遮挡/GL状态、失效隐藏、公开模型三维输出、配置新实例恢复、2D返回及手势GL动画。另OPPO执行后端夹具1项通过。设备资源/偏好备份恢复、系统字体/旋转核对通过，未清空/卸载。GPU初始化失败回退分支未故障注入。已安装双机。

用户随后复测反馈“已测试，仍会闪烁”：该候选未通过真人视觉验收，问题仍开放。此次反馈时OPPO已回桌面，当前缓冲没有对应GPU三维复现窗口；已启动仅诊断日志的连续读取，等待同步复现以区分过期、无脸或渲染问题。不得把旧160.54ms观察当成此次闪烁的已确认原因，既有自动夹具通过不等于真人通过。未执行Release/R8、完整字体/横屏、热稳定长测、全背面跟踪或真实引擎。未提交/push。

证据：[源码与结果](evidence/sticker3d-flicker-20261030/results.json)、[OPPO回归](evidence/sticker3d-flicker-20261030/5ef48cf2.txt)、[执行后端短测](evidence/sticker3d-flicker-20261030/benchmark-oppo.json)。

# 2026-10-30：3D帽子间歇消失排查（待复现）

用户确认现象为整顶帽子时有时无，而非表面闪动。检查 GlSticker3DRenderer/Sticker3DGeometry、BeautySession、FaceAnalysisHistory、TrackedFaceAnalysis 和 CameraX 时间戳：当前三维仅消费真实模型结果，结果过期/无脸/错误会隐藏。OPPO当前日志持续NO_FACE，所见结果年龄约67～100ms、未见STALE，无法由该观察确认用户佩戴期间的根因。未修改有效期、置信阈值或无脸隐藏契约，未宣称修复或新增测试通过；请求用户对镜头复现后再对照状态/时延。没有采集或保存相机画面，基线不推进。诊断文本见 [observation.txt](evidence/sticker3d-flicker-20261030/observation.txt)。

# 2026-10-30：相机配置恢复与手势误关闭修复

用户要求“配置哪个，下次显示哪个”。发现人脸/手势检测仅用savedInstanceState保存，重新进入会回默认；改为宿主私有偏好持久化，配置重建仍优先Bundle。保留现有3D款式、参数、资源与手势选择存档。相机手势卡片重复点击改为保持启用，增加“无”显式关闭；显式选择动画同时开启手势检测。相机详情隐藏删除操作，资源管理页仍保留确认删除。SDK检测与动画时序/过期契约未改动，不延长无脸挂件显示时间。

最终 assembleDebug/assembleDebugAndroidTest/lintDebug通过，UI静态检查0警告。OPPO PCAM00(API30)中断批次前9项通过、剩余4项独立批次4/4，Pixel8(API36)完整13/13，共26次通过、13个不同用例。新增实际相机配置用例：选择棒球帽及手势、重复点击保持、自动开启检测、相机详情无删除、关闭后以新Activity/ViewModel读取相同帽子和手势包；再关闭人脸/手势检测，新实例保持关闭。既有两类手势包解析、篡改拒绝、真实GL动画alpha/换帧/过期/方向镜像、显式应用后重开恢复、手势库组合/默认/删除以及3D/资源页面回归通过。

初轮两设备各11通过/2失败：旧测试仍查找改版前隐藏“应用”按钮与旧手势卡片tag。更新为现有可见更多菜单和资源详情应用路径，保留显式应用/恢复/删除断言。中间Pixel菜单文字节点不可点击，改为向父级定位可点击菜单项；同轮OPPO instrumentation未返回，主动force-stop结束并恢复备份，标记中断，不算通过。后续Pixel13/13，OPPO12/13（资源页保留在线页签，安装卡片不可达）；旧用例补显式选择“已安装”前置条件。最终Pixel完整13/13；OPPO最后整批等待期间主动中断，实际日志已完成前9项且均通过，剩余4项独立复验4/4。不能表述为OPPO完整批次稳定通过；中间日志保留。每批外层资源/偏好备份恢复，系统字体/旋转前后核对通过。未清空或卸载应用。

本轮恢复使用新Activity/ViewModel且不传savedInstanceState，未模拟保存中强杀/断电。合成TRIGGER的GL通过不代表真人手势准确率；未复验真人帽子闪烁、全背面跟踪、完整横屏/大字体矩阵或长时性能。第一条“删除”的原始现象尚未得到用户进一步确认；仅已修复相机删除入口暴露及重复点击导致停用，不将其等同于已定位所有消失问题。已安装双机，未提交/push。

证据：[结果与源码摘要](evidence/configuration-restore-20261030/results.json)、[OPPO日志](evidence/configuration-restore-20261030/5ef48cf2.txt)、[Pixel日志](evidence/configuration-restore-20261030/39061FDJH00D3J.txt)。

# 2026-10-30：包头三维棒球帽与估算头部遮挡

新增自有棒球帽网格：开放帽壳/内壁、弯曲帽檐、前徽章、侧标、后调节带。按已有 MediaPipe 额头及脸宽估算贴合，新增独立头部椭球深度通道；帽子偏移/缩放不移动虚拟头部。保留旧 StickerMesh3D 三参构造及 Sticker3D 构造/copy，新增显式 headOcclusion 重载；旧素材不启用头体积。独立 VAO/VBO/EBO 静态上传头部网格，渲染后恢复宿主 GL 状态；不新增模型、SO、联网或 CMS 三维格式。Demo 新增 3D 棒球帽，首次进入三维默认该款，原选择继续记忆。

核心 Debug JVM：145 项，失败 0、错误 0、跳过 1；其中 Sticker3D 8/8。assembleDebug、assembleDebugAndroidTest、lintDebug 通过，UI/i18n 检查 0 警告。最终 OPPO PCAM00(API30) 13/13、Pixel 8(API36) 10/10，共23次通过、13个不同设备用例。覆盖六视角真 ES3 渲染、头部遮挡开关与前后深度、四方向/镜像、alpha、GL状态恢复、过期隐藏、素材切换/记忆/关闭及已有资源页面。双机资源/偏好备份恢复，系统字体/旋转核对恢复。

保留初轮失败：两台各1项界面检查早于弹框/布局完成，其他底层用例通过；为设备测试增加最多5秒主线程可见/布局轮询，未删除或降低原断言，最终完整批次通过。初轮日志随证据保存，不累计中间通过数。

目视检查 OPPO 正面/侧面/合成背面/内侧渲染；预览由真实 GL 合成纹理截图裁剪拼接，不含相机人像。头部为估算椭球，不是实测头型/头发/手部遮挡；背面为合成姿态，不代表 MediaPipe 完全背对镜头仍能跟踪。真人佩戴适配、快速大角度运动、持续性能、完整字体/横屏矩阵及 Release/R8 本轮未验收。已覆盖安装双机，未提交/push。

证据：[六视角预览](evidence/baseball-cap-20261030/preview.png)、[结果与源码摘要](evidence/baseball-cap-20261030/results.json)、[SDK说明](../SDK_3D_STICKERS.md)。

# 2026-10-30：Apple 设计原则下的效果面板交互与裁剪修复

固定显示 2D/3D 切换，三维素材列表单独滚动；从最末耳机选项仍可直接点击 2D，关闭三维并恢复二维资源页。选中状态覆盖全部三维选项。卡片底色收敛为深色中性底，封面/描边/点击遮罩统一 12dp 圆角；图片自身 Path 裁剪同时覆盖软件截图与硬件绘制，不擦除父背景。保留 8dp 列间距/12dp 行间距与 16dp 页面边线。

卡片不再增加生效/暂停文字行，相机保存忙碌状态不插入内容流；预设应用与三维开启只发一次短提示，错误仍提示，管理页保留总体进度。微调区可纵向滚动，标签固定两行，滑杆左右 16dp/高度 48dp、底部 12dp；持续复用同一滑杆。

最终 assembleDebug、assembleDebugAndroidTest、lintDebug 通过，UI/i18n 各 0 警告。最终 APK：OPPO 常规 14/14；Pixel 实际系统字体 1.3 竖屏微调隔离 1/1、3D返回2D隔离 1/1、横屏三项 3/3，共 19 次通过，覆盖 14 个不同用例。新增滑杆完整可见和调参前后矩形相等断言，2D按钮可见/完整宽度、三维关闭及二维网格恢复断言。原逐帧面板位置/尺寸、参数复用/重置、资源详情不自动应用与末卡可达断言保留。各批资源与偏好备份恢复，字体/自动旋转/方向恢复核对通过。

保留失败：初轮 Pixel 字体1.3竖屏三项批次 2/3，贴纸初次自动打开超时（既有批次启动问题）；最终同断言隔离通过，没有证明完整竖屏批次稳定。初次代码编译的方向常量、style父级修复后重建；中间圆角控件软件绘制产生黑角，替换为直接裁剪后复验。所有通过数以最终证据为准，不将中间重复执行累加。

目视检查最终 OPPO 微调/二维/三维与 Pixel 横屏页面；截图仅绘制 UI View，不保存相机帧。未验证 OPPO 大字体、完整日夜/超大字体/小屏矩阵、真人效果、持续性能和 Release/R8；未修改 SDK 渲染器、CMS 或三维资源协议。

证据：[页面预览](evidence/effect-panel-redesign-20261030/preview.png)、[源码及结果](evidence/effect-panel-redesign-20261030/results.json)、[最终构建](evidence/effect-panel-redesign-20261030/build.txt)。设计说明见 [EFFECT_PANEL_DESIGN.md](../EFFECT_PANEL_DESIGN.md)。已安装双机，未提交/push。

# 2026-10-30：新增三款内置 3D 试戴素材与标识

新增香槟金皇冠、珍珠猫耳、薄荷色耳机，均为自有不透明顶点色三角网格；原眼镜、礼帽保留。相机贴纸入口横向滑动，每个三维选项带立方体和 3D 名称，普通 PNG 资源不伪标三维。选择记忆扩展到五款。未扩展 CMS/ZIP/GLB 格式。

最终 assembleDebug、assembleDebugAndroidTest、lintDebug 通过；UI/i18n 静态检查各 0 警告。Sticker3D JVM 7/7 通过（含五款网格的真实深度/分析需求）。OPPO 三项设备测试 3/3 通过：全部素材重复切换与侧转、五款三视角覆盖/曲面色阶/alpha/GL 错误、面板新选项启用及关闭/恢复。新版已覆盖安装 OPPO，未清除应用数据。预览为合成输入纹理，不含相机人像。

未验证真人佩戴适配、头发/手部遮挡、持续性能、多设备与横屏/大字体完整矩阵；本轮不修改渲染器。首轮构建后补充猫耳深度和测试列表，首次视觉检查发现耳机头梁被脸部近似遮挡，调整头梁高度与深度后重新构建并复测，以上述最终证据为准。

证据：[三视角预览](evidence/sticker3d-new-20261030/preview.png)、[设备日志](evidence/sticker3d-new-20261030/oppo.txt)、[构建](evidence/sticker3d-new-20261030/build.txt)、[源码与结果](evidence/sticker3d-new-20261030/results.json)。

# 2026-10-30：卡片预览图片铺满修复

ResourceCardAdapter 与 item_effect_preset.xml 将图片缩放改为 CENTER_CROP，等比铺满并居中裁剪；符号和关闭卡片显式保留 FIT_CENTER 与原内边距，避免列表复用时继承错误缩放。详情大图保持完整显示。

本轮 assembleDebug、lintDebug 通过，UI 静态检查 0 警告。新版成功覆盖安装到 OPPO 5ef48cf2 并启动首页；未执行本轮设备自动化或截图视觉验收，未重跑横屏、大字体、小屏和生命周期矩阵。构建日志：build/functional-test/thumbnail-fill-build.txt。

# 2026-10-30 最新：紧凑布局与内边距收敛

效果卡片默认四列、54dp封面/26dp符号/12sp单行标题，资源中心默认三列、80dp封面/32dp符号/13sp最多两行标题；大字体和宽度自适应换列。取消空白状态行和短标题的强制双行，勾选角标表达选择，暂停使用短标且保留完整可访问性说明；下载状态保留。网格外侧不叠加卡片margin，内部8dp间隔、行尾12dp，与16dp页面边线对齐；更新列数时失效旧装饰边距缓存。

微调参数选项固定48dp，不再按剩余空间拉伸；数值、滑杆与重置保持复用。竖屏默认面板约42%并按320dp最低内容/字体需求保底，预设与微调仍同高、可展开。模式按钮保留48dp点击范围但缩小底色；3D选择压至56dp，统一来源/分类/内容边距。SDK和3D渲染不在本轮修改。

最终assembleDebug、assembleDebugAndroidTest、lintDebug通过，UI/i18n检查0警告。OPPO常规14/14、默认字体横屏3/3；Pixel实际系统字体1.3横屏3/3、竖屏批次2/3，同一最终APK的失败项原断言隔离1/1通过。共23次通过、1次最终批次失败，覆盖14个不同用例，不能表述为所有批次通过。新增断言防止参数选项随面板拉伸；原详情应用、末卡完整可达、参数更新/重置、预设默认/展开/重开、3D暂停及资源记忆断言保留。每批资源备份、字体/自动旋转/方向恢复均核对通过。

保留未通过尝试：Pixel早期横/竖屏批次各有一项初始贴纸弹框尚未可用；增加可见弹框有界等待、整批锁定方向后，最终横屏3/3，但竖屏仍有初始贴纸弹框等待超时，未降低交互断言。相同最终APK隔离该项1/1通过，根因尚未完整定位，不宣称竖屏批次稳定性通过；日志保留。OPPO真机普通/微调和Pixel横屏资源截图已目视检查，均为UI View绘制，不保存相机帧。

未验证：OPPO大字体、Pixel全14项批次、完整超大字体/日夜/小屏矩阵，未新增JVM或执行Release/R8、真人效果和持续性能。既有3D和授权结果是历史基线，不能算本轮通过。

证据：[前后对比](evidence/ui-compact-20261030/before-after.png)、[结果与源码摘要](evidence/ui-compact-20261030/results.json)、[最终构建](evidence/ui-compact-20261030/verified-build.txt)。新版已安装双机；未提交/push或发布网站。

以下为历史轮次。

# 2026-10-30 最新：3D饰品模型与光照改进

针对验证素材缺乏体积感的问题，重做内置眼镜双层镂空框、金色包边、铰链和曲线镜腿；礼帽改为连续卷边帽檐、收腰帽冠、缎带和金属扣，并调整佩戴高度。GlSticker3DRenderer仅在网格上传时生成面积加权平滑法线，GPU增加方向主辅光、高光和弱轮廓光；公共网格API、模型/SO、弱透视和深度/alpha契约不变。这是3D饰品质感改进，不是三维美型。

最终核心Debug JVM 143通过、1项Release专用测试按预期跳过，无失败；assembleDebug、assembleDebugAndroidTest、lintDebug通过。OPPO PCAM00 API30、Pixel8 API36最终各8项通过，共16次执行、8个不同设备用例。覆盖深度遮挡/alpha、左右转头、GL状态恢复、四方向镜像/FBO尺寸、原生美颜与运镜、原模型三维输出和UI选择记忆；新增512px三视图夹具验证覆盖、明暗变化、姿态变化与视频alpha。首轮双机8项通过后，目视发现帽子偏低，调整佩戴位置并完整重跑上述最终检查，没有沿用首轮作为最终结果。

已目视检查实际OPPO ES3导出的正面/左右侧转图。对照图是纯合成纹理，深度测试使用不可见的模拟脸部扇形，因此后方镜腿可能分段隐藏；不是缺失模型，也不代表真人头部遮挡已验收。真实头发、耳朵、手和后脑勺不在现有遮挡模型范围。截图合成仅裁切/缩放同一实际渲染输出，没有生成或保存摄像头人像。

限制：仍是顶点色风格化着色，不含PBR、环境反射、真实投影或透明镜片；没有GLB/骨骼/CMS三维资源支持，未解决弱透视/精细遮挡、未验收真人佩戴观感、快速动作、长时性能、Release正向授权、iOS或直播引擎。设备执行通过不能表述为商业素材质量通过。

证据：[结果与源码摘要](evidence/sticker3d-polish-20261030/results.json)、[三视图](evidence/sticker3d-polish-20261030/showcase-final.png)、[最终构建](evidence/sticker3d-polish-20261030/final-build.txt)。已安装双机；未提交/push、发布Maven或部署CMS。

以下为历史轮次。

# 2026-10-30 最新：条目图标与文字优化

五款内置美颜预设使用不同的统一线性图标，补齐beauty_bare/beauty_fresh真实ID，替换带小字旧封面；其他实际素材预览保留。标题竖屏固定两行区域，横屏单行省略；隐藏普通已安装状态，保留选中/暂停/下载信息。同名条目按当前列表顺序编号区分，不修改资源协议、原名或删除用户资源。关闭图标提高对比，并根据资源选择、参数中性值和场景状态反馈选中；单独的全分类关闭状态矩阵未执行。

最终assembleDebug、assembleDebugAndroidTest、lintDebug通过；UI与i18n检查0警告。OPPO API30默认字体竖屏3/3，Pixel8 API36实际系统字体1.3横屏3/3，通过的是同一组3个不同ResourceExperienceDeviceTest用例，两设备共6次执行。覆盖详情显式使用、末卡完整可达、单滑杆/展开/重开和3D暂停恢复；设备资源备份与字体/旋转均恢复。OPPO新版预设UI-only截图已目视核对。未复跑上一轮14项全套，不以其历史结果替代本轮；未执行Release/R8、完整字体矩阵、真人或长时性能验收。

证据：[结果与源码摘要](evidence/resource-cards-20261030/results.json)、[新版条目截图](evidence/resource-cards-20261030/presets-review.png)。已安装两台设备；未提交或push。

以下为历史轮次。

# 2026-10-30 最新：效果面板与资源中心整体优化

效果弹框统一预设/微调、单滑杆和单项重置，支持展开并保持模式切换高度；3D暂停二维效果时明确展示状态。资源中心改为独立可滚动网格，安装/在线入口分离，卡片先查看详情再明确使用；删除、主题覆盖提供确认，导入摘要与低频管理操作收进更多菜单。新增横屏紧凑布局，修复大字体横屏最后一张卡无法完整显示的问题。SDK渲染和资源协议未改动。

最终构建 assembleDebug、assembleDebugAndroidTest、lintDebug 通过。UI/i18n门禁0警告，架构检查通过并保留独立sample的两项既有提示。没有把历史JVM、Release/R8或性能结果算成本轮通过。

OPPO PCAM00 API30：常规14/14、横屏3/3通过，共17次通过、14个不同用例。Pixel 8 API36：常规批次12/14通过，两项分别为布局采样超时与异步列表删除后的视图时序断言失败；同一APK、原断言分别隔离复跑2/2通过。Pixel实际系统字体1.3竖屏3/3、横屏3/3通过，共20次通过、14个不同用例。保留失败批次，不能声称Pixel整批稳定或零失败。测试资源备份和系统字体/旋转设置均已恢复。

测试覆盖详情不隐式启用、显式使用、末卡完整可达、单滑杆状态更新、重置、预设默认、展开/重开、3D暂停及普通效果恢复、JSON参数和内置资源。UI截图仅绘制界面View，不含摄像头人像；已检查OPPO微调与Pixel大字体横屏资源截图。

限制：OPPO系统拒绝ADB字体设置，未验收其1.3倍字体；Pixel上述两项批次时序波动仍需持续关注。未验收完整日夜/字体/设备矩阵、真人观感、长时性能、Release正向授权、直播引擎或iOS。此前验证尝试中的DiffUtil lint问题、测试配置/旋转恢复问题已修复；Gradle低并发重试通过，不将失败尝试记为通过。

证据：[说明](evidence/ui-refresh-20261030/README.md)、[结果与工作区指纹](evidence/ui-refresh-20261030/results.json)、[最终构建](evidence/ui-refresh-20261030/build-responsive.txt)、[Pixel保留失败批次](evidence/ui-refresh-20261030/39061FDJH00D3J-normal.txt)、[原断言隔离复跑](evidence/ui-refresh-20261030/pixel-final-isolated.txt)。本轮未提交、push或部署网站。

以下为历史轮次，不能替代本轮结论。

# 2026-10-29 最新：实验性刚性3D贴纸

已实现单人脸不透明三角网格、弱透视头部跟随、粗略脸部深度遮挡和内置3D眼镜/礼帽，Demo「相机→贴纸」可选并记忆。复用原MediaPipe模型/SO，无新增native依赖；3D期间明确暂停二维美型/贴纸/块匹配，保留原参数。默认关闭。旧构造/copy/factory JVM描述符通过重载保留，Release沿用FACE＋STICKER授权。

本次最终通过：JVM **194项**、另1项Release专用用例在Debug按预期跳过；其中核心Debug143通过、核心Release授权/3D几何20、资源12、GPUPixel4、feature15。Pixel 8 API36 Debug **14项不同设备用例**通过（新增三维6＋UI1，原有GPU6＋运动匹配1）；UI同一用例额外在竖屏1.0字体、横屏1.3字体配置通过，恢复用户偏好及系统设置。设备覆盖真实深度遮挡/alpha、yaw、四方向×镜像、FBO尺寸变化、GL状态恢复、现有模型三维输出、美颜＋运镜，以及界面可见入口/关闭/重开记忆。合成PNG已目视检查，只保存合成纹理，不保存相机图像。

最终Demo assembleDebug/assembleDebugAndroidTest/lintDebug通过并覆盖安装Pixel8；本地Maven5制品、4项发布契约、2300项JVM签名保留检查、独立消费者三classpath/Debug/Release-R8/lint通过。Release/R8是未正式签名构建，未做Release正向激活/GL验收。UI与i18n门禁0警告；代理配置通过；架构检查通过，独立sample未纳入根settings的两项既有提示保留。

修复/未计通过的尝试：首次lint发现values-en缺5项翻译，补齐后通过；首次UI用Activity根查找已移入BottomSheet的控件而失败，改为在弹窗查找，保留可见/状态断言。一次误将依赖Debug开发模式的旧功能全量纳入未配置授权的Release测试，因按设计旁路引发断言失败后终止；最终Release仅执行授权契约与纯3D几何20项，未以降低授权或修改旧断言规避。原始尝试日志留在build/functional-test/sticker3d/{build-tests,device,final-build}.txt。

未实现/未验收：GLB/PBR/纹理/骨骼动画、CMS三维制作与资源协议、多人脸、iOS、真人侧脸/俯仰/手和头发遮挡质量、多设备长时性能、正式网络发行和直播引擎。脸部遮挡仅鼻部到轮廓的扇形近似，不是精细深度或matting。横屏1.3字体用例不代表完整字体/日夜/设备矩阵。旧JVM描述符保留不等于Kotlin元数据/native ABI/全部行为兼容通过。

证据：[本轮说明](evidence/sticker3d-20261029/README.md)、[逐项结果与工作区指纹](evidence/sticker3d-20261029/results.json)、[设备14项](evidence/sticker3d-20261029/device-regression.txt)、[最终Demo构建](evidence/sticker3d-20261029/final-app-build.txt)、[独立消费者](evidence/sticker3d-20261029/consumer.txt)。接入见[3D贴纸文档](../SDK_3D_STICKERS.md)。本轮未提交/push或部署CMS。

以下为历史轮次，不能替代上述本轮结论。

# 2026-10-29：0.2.0 应用授权

已实现签名授权文件、首次联网激活、最长 7 天缓存租约、默认 24 小时刷新、重启复用退避计划、签名撤销、功能权限和未授权原帧旁路。提供 Node 服务、签发/续期/撤销 CLI、统一 Maven 初始化入口与开发文档。正式服务尚未部署。

本次通过：核心 Debug JVM 136 项（另 1 项 Release 专属用例跳过）、Release 授权 JVM 13 项、资源 12 项、GPUPixel 4 项、Node 9 项；Pixel 8 API36 的 Debug GPU/手势 7 项。主 Demo 构建/lint、独立消费者三类 classpath/Debug/Release-R8/lint、5 个本地 Maven 制品与源码摘要、2193 项 JVM 签名检查、13 项发布/试用检查器测试通过。空发行配置的商业打包反例按预期拒绝，无商业 ZIP 产出。

详细命令、逐项证据、两处已修复的验证失败及限制见 [本轮授权报告](evidence/license-20261029/README.md)。Release/R8 为未正式签名的构建验证；设备测试使用 Debug 开发模式，不能代表商业 Release 的正向激活。未执行正式 HTTPS、生产密钥、客户 APK 激活及跨重启真机离线验收；直播引擎、iOS、真人持续性能不在本轮范围。当前本地 Release 未配置正式公钥，默认关闭效果。

以下为历史轮次记录，不能替代本轮结果。

# 2026-10-29 限定试用产品收敛

落实主推范围与当前状态入口、首次接入任务卡、真人/设备/外部试用流程、发布回退规则；保留实验能力，未更改算法参数冒充真人调校。新增ResourceIntegration.kt独立资源示例、CMS下载摘要与Android最近成功本地导入完整包摘要展示/持久化。

验证：Demo assembleDebug/assembleDebugAndroidTest/lintDebug通过（5m46s）；独立Maven单入口检查、Debug和Release/R8通过（1m14s），最终使用已有配置兼容的2048m Gradle进程。首次样例内存不足、一次PowerShell属性参数解析错误、一次低内存进程未正常结束均保留失败日志，不计通过。

试用门禁8项、发布契约4项通过；JVM2033条签名初始基线检查通过。qualified门禁按预期拒绝全部5项pending验收。UI/i18n均0警告；架构通过，独立sample未纳入根settings的2项提示符合设计。代理结构与配置通过。

CMS84项全量测试、静态构建通过；隔离Playwright选择轻量美颜、下载ZIP、重新计算摘要与手机试用弹框一致，390px弹框无横向超出，零pageerror。未修改资源包协议或现有渲染默认值。

Pixel8明确offline，未安装运行新APK，未操作其他设备。DeliveryReceiptDeviceTest已编译但未执行；Android字体/横屏、实际两端导入核对、真人默认预设调校、多设备持续性能、外部开发者试用及正式签名发行未验收。未更新设备通过基线，未接直播厂商、未部署网站或远程Maven。

证据：[Demo构建](evidence/trial-20261029/app-build.txt)、[独立消费者](evidence/trial-20261029/sample-build.txt)、[门禁](evidence/trial-20261029/contract-tests.txt)、[API](evidence/trial-20261029/api-check.txt)、[Web](evidence/trial-20261029/cms-tests.txt)、[浏览器](evidence/trial-20261029/browser.json)。当前交付结论见[TRIAL_BUILD_REPORT](TRIAL_BUILD_REPORT.md)。

---
# 2026-10-29 单一Maven依赖入口

新增com.beautysdk:beautysdk:0.1.0（core/lib_beauty_sdk），api暴露四个同版模块。独立sample仅一条implementation，不复制模型/native库、不合并AAR。新增发行版本常量BeautySdkDistribution.VERSION。根任务统一发布5个制品；候选打包器同步入口、来源摘要与API/runtime变体检查，完整本地候选包build/beautysdk-single-entry-20261029.zip生成成功。

验证通过：publishBeautySdkToLocalBuildRepository（1m7s）；独立工程verifyBeautySdkEntry、assembleDebug、assembleRelease（1m43s，含R8）。debugCompileClasspath/debugRuntimeClasspath/releaseRuntimeClasspath均恰含入口与四个0.1.0模块。Python发布契约4项通过，覆盖真实制品、错误scope、API依赖缺失及混合版本。首次打包检查误把sources documentation变体视作runtime库，修正为明确library类别并要求api/runtime双变体后通过，未放松运行依赖校验。

设备尝试仅指定Pixel8，adb报告offline，安装未执行；其他在线设备/模拟器未使用，不沿用上一轮真机成功作为新入口证据。未上传远程Maven、未发布网站、未提交push；Release是未签名验证制品。依赖入口不代表功能/性能新增验收。

[发布日志](evidence/maven-entry-20261029/publish.txt)、[单依赖图及Debug/Release构建](evidence/maven-entry-20261029/consumer.txt)、[发布契约测试](evidence/maven-entry-20261029/contract-tests.txt)。配置与接入方式见doc/ANDROID_SDK_USAGE.md及samples/standalone/README.md。未提升历史设备通过基线。

---
# 2026-10-29 独立交付与CMS工作区可靠性

四模块本地Maven发布通过（完整Kotlin编译）；候选打包器通过POM/module/AAR/sources结构、依赖及源码摘要核对，产物build/beautysdk-candidate-20261029.zip。初次增量编译未识别已有顶层函数，完整编译后通过，未删改SDK业务代码规避。

独立工程 `gradlew.bat -p samples/standalone :app:assembleDebug :app:assembleRelease -Pkotlin.incremental=false --max-workers=1 --console=plain` 通过（5m6s），包含Release/R8。Debug APK覆盖安装Pixel8，启动com.beautysdk.sample/.MainActivity，UI实际显示“本地帧处理完成”。只验证生成色卡RGBA路径；未验证独立GPU示例运行，Release为未签名制品，不能称为正式发布或完整native运行验收。

CMS：全量83项测试通过；随后增加具体配置字段差异，相关7项测试再次通过。最终静态构建通过。补齐原遗漏release-backup.mjs及新增workspace-backup.mjs的发布复制，构建逐一检查相对模块引用存在。新增工作区备份/摘要校验/结构预算/单事务补缺失恢复、逐字段defaults/library差异。

隔离Playwright浏览器（新context，未使用用户草稿）：真实IndexedDB写入二进制测试素材；备份/读回后merge恢复1项，保留1项，二进制字节一致。最终dist/releases.html实际点击“备份整个工作区”下载beauty_workspace.bsworkspace，再用文件控件导入，状态“恢复完成：补充0项，保留本机已有1项”，pageerror为空。首次获取远程下载path不支持，改为下载流后完成，不计失败尝试为通过。所有隔离context已关闭。

[发布日志](evidence/commercial-20261029/publish-full.txt)、[独立Debug/Release构建](evidence/commercial-20261029/standalone.txt)、[Pixel8页面文本](evidence/commercial-20261029/sample-ui.xml)、[Web83项](evidence/commercial-20261029/cms-tests.txt)、[最后7项](evidence/commercial-20261029/backup-tests-final.txt)、[静态构建](evidence/commercial-20261029/cms-build-final.txt)。未发布网站/远程Maven，未提交push。能力清单移除过时的“不支持手势播放器”声明，保留真人资格pending。

剩余：运行状态与能力卡片完整联动、统一错误模型/API兼容门禁、GPU独立宿主运行、正式网络签名跨端发行验证、CMS多页并发与配额耗尽及所有编辑器恢复、模型素材商业凭据、真人画质和多设备长测。工作区备份只含已保存数据，不认证来源、不覆盖已存在草稿。旧基线不整体提升。

---
# 2026-10-29 状态回放与相机手势分类修复

Pixel 8 / Android16 API36最终22项设备回归全部通过（31.605秒），包括上一轮失败的4个用例。新增相机第9类手势特效入口，内置卡片可选择并更新动画配置；资源控制器改为宿主赋值后显式start，避免初始状态回放访问未初始化字段。补齐发行下载按钮values-en翻译。

先前连续测试存在相机退出后设备重新锁屏的问题；本轮每项LabSceneBackup UI测试先唤醒/请求dismiss-keyguard，保留所有原断言。独立资源页3项复跑通过，最终8个测试类22项完整通过。首轮修复构建的22项仍有5项失败，日志保留，不计入通过。构建曾遇Kotlin增量缓存未识别已有扩展函数，关闭增量后通过源码编译；后续一次构建中断，最终以单worker重建安装，未把旧APK算作新入口验证。

最终通过范围：SignedResourceDeliveryDeviceTest 1、EffectPanelStabilityDeviceTest 1、DemoBuiltinLibraryDeviceTest 1（增加相机手势入口/可见卡片/选择配置断言）、ResourceCenterUiDeviceTest 3、ResourceLibraryDeviceTest 5、ResourceContractDeviceTest 3、GestureResourceLibraryDeviceTest 4、GestureEffectsDeviceTest 4。覆盖GPU透明合成/动画换帧/结束过期/旋转镜像、手势资源导入/显式应用/恢复；动画GPU使用合成TRIGGER事件，不代表真人识别准确率验收。

构建命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -Pkotlin.incremental=false --max-workers=1 --console=plain`。两个APK构建与覆盖安装通过，UI门禁0警告。最终lintDebug通过，整体BUILD SUCCESSFUL（7m57s）。[构建日志](evidence/fix-20261029/build.txt)。adb只操作Pixel8，未清数据，测试恢复宿主状态；恢复测试前亮屏超时，未保存相机帧。未接直播引擎。

证据：[最终22项](evidence/fix-20261029/device-final.txt)、[中间失败记录](evidence/fix-20261029/device.txt)、[工作区文件摘要与APK摘要](evidence/fix-20261029/summary.json)、[UI门禁](evidence/fix-20261029/ui-final.txt)。保留旧用例基线，本轮证据单独归档。未覆盖正式HTTPS发行源、CMS浏览器、真人动态观感、长时性能和iOS。

---
# 2026-10-29 最新源码 Pixel 8 真机回归

用户本轮明确要求真机测试，解除此前不运行限制。采用 android-functional-test-workflow。目标 Pixel 8 / Android 16 / API 36；另一在线设备未操作。覆盖安装debug与测试APK成功，未清空应用数据；相关用例使用LabSceneBackup恢复宿主配置。不保存相机图像。

结果：资源模块12项JVM通过（ResourceCompositionTest 8、ResourceCapabilitiesTest 4）；assembleDebug、assembleDebugAndroidTest通过。lintDebug失败：official_resources.xml 的 resources_official_download 缺少 values-en 翻译（1 error / 169 warnings）。整体Gradle命令因此失败，不能写作构建全通过。

设备首轮14项，9通过/5失败，期间设备熄屏。临时延长screen_off_timeout至600000并唤醒复跑，最终14项10通过/4失败；随后恢复原亮屏超时。保留两轮原始日志。最终失败：

- DemoBuiltinLibraryDeviceTest.cameraUsesLatestBuiltinJsonAndAppliesControlsWithoutManualImport：模型参数0.31已就绪，预览鼻子参数仍0.0。
- ResourceCenterUiDeviceTest.rapidParametersKeepResourceCardsAndCallerInputStable：资源Grid首个卡片为空，getChildAt空引用。
- ResourceCenterUiDeviceTest.independentPageInstallsSelectsAndPersistsWithoutCamera：分类预期9，实际8。
- ResourceLibraryDeviceTest.cameraUsesJsonNamesRangesAndParametersWithoutReplacingSceneControls：预期“自定义美颜”，实际“美颜”。

通过范围包括签名真实包安装/篡改及错误公钥拒绝、预设默认展示/切换/重开/滑杆调节期间面板位置采样、资源删除重开、JSON参数应用与重置、包校验与旧格式兼容、默认语言/顺序、在线手势校验。位置采样通过不代表真人画质或全部屏幕视觉通过。

执行：`gradlew.bat :core:lib_beauty_resources:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=2 --console=plain`；adb指定Pixel 8覆盖安装，AndroidJUnitRunner选择SignedResourceDeliveryDeviceTest、EffectPanelStabilityDeviceTest、DemoBuiltinLibraryDeviceTest、ResourceCenterUiDeviceTest、ResourceLibraryDeviceTest、ResourceContractDeviceTest。

证据：[最终设备日志](evidence/device-20261029/device-awake.txt)、[首次设备日志](evidence/device-20261029/device.txt)、[构建与lint日志](evidence/device-20261029/build.txt)。本轮只测试/记录，未修业务源码。因尚未生成可靠完整工作区指纹，不推进TEST_BASELINE，旧通过记录不覆盖当前失败。未测试正式HTTPS发行源（未配置）、CMS浏览器、横屏/大字体矩阵、持续性能、真人动态画质、直播引擎及iOS。

---
# 2026-10-29 Demo默认接入最新完整资源库（本轮）

> 后续源码增量：增加实际组件能力配置、结构化不支持错误、商业能力分级、候选发布制品结构检查和独立示例显式错误回调。新增ResourceCapabilitiesTest尚未运行；未构建、未操作真机，历史通过基线不覆盖这些改动。

> 最新增量状态（2026-10-29，用户要求“先不运行”）：统一Maven发布、独立消费者、候选打包脚本、资源验签、CMS来源记录、长测覆盖率门槛和预设模式栏适配仅修改源码，**本轮未构建、未执行测试、未运行真机**。以下历史通过记录不覆盖这些变更；TEST_BASELINE不推进。新增验签设备用例与CMS来源记录用例均待执行。范围及剩余项见 [COMMERCIAL_READINESS.md](../COMMERCIAL_READINESS.md)。

根因：之前只新增公共assets加载API，Demo启动仍使用旧resource_center/catalog.json，缺少library.json时回退旧控件。修复：主APK加入最新resource_delivery.zip与对应内置library.json，Model启动经installDeliveryFromAssets完成一次安装；未显式导入时默认采用内置JSON，导入清单仍优先。启动安装按完整包摘要标记，保留用户参数/选择和旧内置删除记录；相机面板在异步清单到达时刷新参数模式，不继续显示旧控件。

本次通过Pixel8 Android16设备13项（14.814秒，0失败）：DemoBuiltinLibraryDeviceTest1、ResourceLibraryDeviceTest5、ResourceCenterUiDeviceTest3、GestureResourceLibraryDeviceTest4。新增真实相机用例不手动导入，断言50常规/4手势/15参数、美颜磨皮/美妆口红/美型鼻子JSON滑杆可见，旧控件隐藏，鼻子参数0.31进入预览settings。回归导入覆盖、参数默认/恢复、资源页9类、删除后重开不重装、手势选择。测试保留/恢复宿主设置，不清空应用数据。

app debug/测试APK构建与lint通过，UI/架构门禁0警告。APK实际assets/resource_delivery.zip与源文件逐字节一致，SHA256见证据。构建命令：`.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。adb显式指定Pixel8安装后用AndroidJUnitRunner选择上述4类。已覆盖安装修复版并启动相机；额外uiautomator读取动态相机页面因无法idle失败，未把旧XML当当前页面，也未拍摄保存摄像头画面。

[证据及源码指纹](evidence/demo-builtin-library-20261029/summary.json)、[13项设备日志](evidence/demo-builtin-library-20261029/device.txt)、[最终构建](evidence/demo-builtin-library-20261029/build.txt)。未测真人观感、持续性能、厂商推流、横屏/字体完整矩阵，功能断言不代表这些已通过。

---

# 2026-10-28 Android assets内置加载与开发说明（本轮）

新增公共installDeliveryFromAssets与installDeliveryDirectoryFromAssets，默认读取APK assets/resource_delivery.zip或beauty_resources目录；后台调用、可取消、完整校验和回滚、按摘要复用，不联网、不改变用户状态。目录限2000文件、8层及32MiB，再复用ZIP校验。新增doc/ANDROID_SDK_USAGE.md，从首页/核心及资源模块文档可达。

通过：资源模块8项JVM、Pixel8 Android16 API36设备14项（13.113秒）零失败；app debug/测试APK构建、lint与架构门禁通过（0警告），使用说明本地文档链接通过。设备包含BuiltinResourceAssetsDeviceTest2、GestureResourceLibraryDeviceTest4、ResourceLibraryDeviceTest5、ResourceDeliveryDeviceTest3。新用例实际读APK assets内ZIP和解压目录，对比普通/手势摘要、重复安装、多语言和准备效果，覆盖缺失/非法路径/非完整目录/取消后无安装。隔离cache且清理，旧宿主回归备份恢复配置。

命令：`.\gradlew.bat :core:lib_beauty_resources:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；adb显式Pixel8目标覆盖安装，以AndroidJUnitRunner -e class选择上述4类。未改视频算法/采集/厂商接口。

证据：[摘要与源码指纹](evidence/builtin-assets-20261028/summary.json)、[14项设备日志](evidence/builtin-assets-20261028/device.txt)、[构建](evidence/builtin-assets-20261028/build.txt)。本次源码仍未提交，基线按ASSET用例独立记录。未做真实引擎、真人观感、持续性能或iOS完整库验证。

---

# 2026-10-28 统一外层文件夹与英文稳定路径（本轮）

新完整ZIP仅含beauty_resources/外层；内部使用beauty/reshape/makeup/filter/sticker/background/motion/theme/gesture等英文分类和稳定资源ID。中文与多语言仅保留JSON展示字段；library引用相对清单位置。Web及Android兼容新外层、旧中文分类根目录和旧平铺包；单项包不变。Demo导入仍只安装，显式选择/采用默认后应用。

通过：Web55项、资源JVM8项，Pixel8 Android16 API36设备12项（11.336秒）零失败。设备类GestureResourceLibraryDeviceTest4、ResourceLibraryDeviceTest5、ResourceDeliveryDeviceTest3，含新包导入/组合/选择恢复、旧包兼容、混合根拒绝、JSON美颜控件/相机配置和篡改无部分安装。debug及androidTest APK构建、lint通过，架构门禁0警告。未清空应用，相关配置与本轮安装测试后恢复。

命令：npm test / npm run build / node scripts/export-library.mjs；`.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :core:lib_beauty_resources:testDebugUnitTest :app:lintDebug`；adb使用明确Pixel8目标覆盖安装后，以AndroidJUnitRunner -e class选择上述3类。

实际本地交付包：258个文件、所有路径ASCII、9类/50常规/4手势，3,838,687字节。输出C:/work/AI/BeautyStickerWorkshop/build/resource_delivery.zip及library.json。[证据与源码指纹](evidence/package-layout-20261028/summary.json)、[设备日志](evidence/package-layout-20261028/device.txt)、[Web日志](evidence/package-layout-20261028/web-tests.txt)、[构建日志](evidence/package-layout-20261028/build.txt)。未发布网站；iOS完整库、真人观感和持续性能不在本轮范围。

---

# 2026-10-28 手势动画完整资源库 V2（本轮）

完整ZIP新增GESTURE第9类，50份常规资源+4组透明图集动画；根library.json记录触发规则、预览、多语言与默认选择。Web已保存绑定随方案导出，导入可恢复；导出等待草稿恢复/保存。Android校验、安装、显式选择/删除、重开恢复及默认配置应用完成；旧独立手势包仍兼容。详细边界见resources/unified/library-v1/README.md。

本次通过：Web53项、资源模块8项JVM；Pixel 8 Android16相关18项设备回归（17.793秒，零失败）；app debug/测试APK构建、lint、UI/i18n/架构门禁均通过，门禁零警告。设备类：GestureResourceLibraryDeviceTest 3、GestureEffectsDeviceTest 4、ResourceLibraryDeviceTest 5、ResourceDeliveryDeviceTest 3、ResourceCenterUiDeviceTest 3。验证完整新库/组合、篡改拒绝且未安装、卡片选择与重开、显式默认、删除、旧包和GL播放器。安装覆盖保留应用数据，配置与本轮新建资源测试后恢复。

命令：`.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :core:lib_beauty_resources:testDebugUnitTest :app:lintDebug`；使用adb显式Pixel8目标安装两个APK，AndroidJUnitRunner以-e class选择上述5类。Web npm test/npm run build/node scripts/export-library.mjs。首次新设备测试编译因跨模块访问internal辅助函数失败，改为测试侧ZIP枚举后最终构建通过；初轮日志保留，不作为功能失败或通过。

浏览器实测本地完整包回读9类/50常规/4手势（3,845,015字节）；自定义绑定生成真实PNG封面，5组回读其中1默认组。交付页加载成功，仅已有favicon.ico 404。本地输出C:/work/AI/BeautyStickerWorkshop/build/resource_delivery.zip及library.json，未发布网站。新包内置手势均默认关闭。网络catalog.json仅覆盖常规资源，手势当前从完整ZIP安装。

证据：[源码/制品摘要](evidence/gesture-library-20261028/summary.json)、[18项设备日志](evidence/gesture-library-20261028/device.txt)、[Web53项](evidence/gesture-library-20261028/web-tests.txt)、[最终构建](evidence/gesture-library-20261028/build-final.txt)。基线单独记录本轮GLIB用例指纹，不替换其他历史用例。源码仍未提交。

未验证：iOS完整库读取、网站发布、真人识别准确率/动态观感、持续性能、直播厂商远端推流、横屏与大字体视觉验收。

---

## 2026-10-28 手势触发动画与定点贴纸（本轮增量）

已实现独立手势效果包、一次动画/定点PNG、7种模型手势绑定；默认SDK仍不自动开启识别或安装素材。Android Demo资源中心导入仅暂存，明确应用后持久化，公共播放器在美颜与场景后合成视频纹理；播放中忽略、800ms冷却、结束消失、换源/原图/停用清理。22种固定支持示意保持只读，实验规则不接首版效果。Demo检测开关遵循现有新页面默认开启要求，可在更多菜单关闭。

本次通过：核心124项、feature_app14项JVM零失败；assembleDebug/assembleDebugAndroidTest/lintDebug；UI/i18n各0 warning；Pixel 8 Android16相关19项（26.549秒）。设备包含GestureEffectsDeviceTest4项、SceneDeviceTest7项、ResourceDeliveryDeviceTest3项、GestureDeviceTest5项。新的真实ES3断言覆盖透明度、图集换帧/结束和4旋转×2镜像；实际网页ZIP覆盖动画与定点贴纸读取、非法配置拒绝和导入→应用→重开/清除。测试备份恢复宿主配置，不清空应用数据。

命令：`.\gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`；adb覆盖安装后，使用AndroidJUnitRunner显式选择上述4个设备类。最终宿主按钮busy门控后补跑app构建/lint，最新设备包复跑通过。

初轮19项中的3失败分别是屏幕关闭导致两项UI不可见、旧用例仍断言Demo默认关闭；唤醒并按现有默认开启约定调整断言后19项通过。最终复跑曾两次被并行安装/另一轮instrumentation强制停止（系统日志明确start instr/installPackageLI），不计为通过；避开冲突后完整19项通过，原中断日志保留。

Web使用隔离的本次提交快照验证45项Node与静态构建，避免混入并行资源目录改动。浏览器原50素材/14参数/背景模糊/跟随示意通过。线上 https://beautystickerworkshop.pages.dev/ 已实测7绑定、4动画、导出回读、导入图集保留、390px无横向溢出和无页面异常。功能提交d984a7f、检查等待修订df88b79已推送main，远端一致。SDK未提交，保留工作区其他任务改动。

证据：[本轮目录](evidence/gesture-effects-2026-10-28/results.json)，含日志和源码/APK摘要。因SDK存在并行未提交源码变更，本轮不推进全局历史TEST_BASELINE，仅保留本次明确范围的证据，下轮按实际差异重新选择回归。

未执行：真人手势准确率与动态观感、持续温升/帧率、厂商远端推流、iOS消费；Android完整横屏/大字体视觉未验收。设备夹具与网页插画不能代替上述验收。

---

# 2026-10-28：分类资源库、效果属性及多语言

实现：Web完整资源下载按分类/名称__ID组织；根library.json定义分类、卡片展示、15项float滑杆属性及中英文回退。Android新公共BeautyResourceLibrary读取、校验及参数合并；Demo导入保存、返回刷新、JSON分类/名称/排序/滑杆/默认恢复。旧平铺交付包兼容；安装不自动应用默认效果。完整协议见resources/unified/library-v1/README.md。

验证：Web `npm test` 48/48，`npm run build`；资源模块8/8 JVM；`:app:assembleDebug :app:assembleDebugAndroidTest`通过；UI、i18n、架构门禁零警告。Pixel 8（Android16 API36）相关回归12/12（16.136秒），新增实际相机用例后清单组5/5（4.513秒），合计13个不同设备用例。源码含其他未提交改动，实际文件指纹记录在证据，不以HEAD代替。

命令：`adb -s <Pixel8> shell am instrument -w -r -e class com.beautysdk.demo.ResourceLibraryDeviceTest,com.beautysdk.demo.ResourceDeliveryDeviceTest,com.beautysdk.demo.ResourceCenterUiDeviceTest,com.beautysdk.demo.GpuPixelControlsDeviceTest,com.beautysdk.demo.SceneControlsDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`；新增相机用例后仅重跑ResourceLibraryDeviceTest。

失败与修复：Web初轮两项旧平铺文件数量断言失败，改为新目录内容/摘要断言并新增3项协议测试。设备首轮7项2失败（测试的晚注册与系统ZipException类型）；中间10项1失败暴露LiveData同步观察时控件字段未初始化，移至init前修复。新相机测试初轮5项1失败（面板移入BottomSheet后从Activity查找），改为打开前持有真实面板引用；最终12项及5项零失败，未删除断言/关闭功能规避。

证据：[摘要与源码指纹](evidence/resource-library-20261028/summary.json)、[设备回归](evidence/resource-library-20261028/device-regression.txt)、[清单与相机](evidence/resource-library-20261028/device-library.txt)、[Web](evidence/resource-library-20261028/web-tests.txt)。中间失败原始日志同目录保留。新参数UI截图来自无摄像头的合成宿主，仅作控件功能证据。

限制：本轮为Web+Android；iOS无新清单消费实现；网页未发布。新控件字体缩放/横屏未验收，未做真人效果、持续性能或厂商推流测试。lint最终结果下文补记。

---

# 2026-10-28 轮廓融合与快速移动对照：跟随问题仍未验收通过

保留的默认优化：连续软置信度过渡（0.08～0.92）、当前图颜色引导、不跨帧混合alpha；同尺寸R8蒙版glTexSubImage2D更新，减少纹理重分配。公开固定人像走真实MediaPipe+BeautySceneRenderer导出GL结果，人工查看静态轮廓；不能推断真人发丝抠图质量。

实验整体平移补偿新增C++前景纹理双向块匹配、全局一致性校验，位移留给GPU采样，无CPU整图重采样。原采样时间/250ms有效期保留，校正目标时刻另记；分析小图比视频纹理新时回退同次模型原始像素。实验有额外成本，重复对照收益不稳定，SDK和Demo最终均默认关闭；快速转头/非刚性局部运动未解决，不推进动态画质通过基线。

本次设备功能26项通过（13.673秒），包括NativeMask(3)、MaskMotion(3)、ForegroundScheduling(3)、Scene(7)、SceneControls(1)、ScenePersistence(1)、MediaPipeInputContract(4)、TextureBridge(4)。核心119/宿主13项JVM零失败。另显式关闭/开启补偿各1项横移测量执行通过。测量完成后才计算IoU与CPU对照图片，避免逐帧诊断合成干扰输入节奏。

| 最终同版本公开横移对照 | 关闭 | 开启实验补偿 |
| --- | ---: | ---: |
| 模型采样年龄中位 | 66.66ms | 99.97ms |
| 校正目标画面年龄中位 | 66.66ms | 66.24ms |
| 模型采样年龄P95 | 66.90ms | 100.02ms |
| 当前帧对参考二值IoU | 0.710 | 0.722 |
| 推理平均 | 28.70ms | 33.90ms |
| 后处理平均（含补偿） | 4.29ms | 11.14ms |
| 补偿阶段平均 | 不运行 | 6.82ms |
| 完成结果平均间隔 | 33.61ms | 45.65ms |

模型与校正目标时刻不同，不能把后者当作新的分割采样时间。IoU参考是同模型当前位置结果，非人工真值；开启补偿84帧中83帧有蒙版，54/60次完成做了可靠匹配。更早同协议一对测得IoU约0.700→0.759、目标年龄66.67→33.35ms，重复结果说明波动较大，不能挑选好的一轮宣称稳定变快。GPU/CPU频率、温度和系统负载未控制，也未测持续竞争。失去纹理、不一致局部运动或不可靠匹配明确拒绝补偿。

Debug/Test构建、lint及显式无正式签名Release/R8通过（合并126秒），算法AAR重新构建通过（3秒）；自有native四ABI ELF PT_LOAD≥16KB，APK zipalign 16KB检查通过；非16KB设备验收。最后仅将Demo实验开关改为显式false，补充构建与宿主回归见build-default-off.log/host-default-off.log。原设备配置6文件逐字节恢复，未保存实时摄像头帧。 最终Demo默认关闭补偿后的9项宿主/场景复测通过（6.622秒）。首次复测有2项页面超时，当时设备处于Dozing、焦点为NotificationShade；唤醒并确认应用焦点后同9项通过。失败日志保留为host-screen-off-failed.log，不隐去首次失败。

命令：gradlew.bat核心/宿主testDebugUnitTest、app assembleDebug/assembleDebugAndroidTest/lintDebug/assembleRelease -PrequireReleaseSigning=false、MediaPipe assembleRelease；Pixel8指定八类instrumentation；ForegroundMotionDeviceTest通过-e maskMotionAlignment false/true分别测量。测试调用者的位移元数据采样契约见SDK_SCENE_EFFECTS.md。

未验证/未通过：真人快速转头、头发摆动、遮挡与低光自然度，持续温升、厂商远端、多设备、iOS/Web及正式签名Release运行。已知Camera停机帧计数竞争未修改。轮廓融合调整不能补出模型未识别的细发丝；不能将本轮功能测试通过写成动态效果已解决。

证据：[功能测试](evidence/mask-alignment-20261028/device-final.log)、[关闭对照](evidence/mask-alignment-20261028/control-foreground-motion.json)、[开启对照](evidence/mask-alignment-20261028/aligned-foreground-motion.json)、[实际GL公开人像](evidence/mask-alignment-20261028/portrait-scene-gl-public.png)、[源码指纹](evidence/mask-alignment-20261028/source-fingerprint.json)。

---

# 2026-10-28 原生蒙版后处理优化

已替换MediaPipeForegroundMasks的Kotlin逐像素循环：NativeMaskConverter直接读取FLOAT32缓冲，在C++一次完成量化与逆旋转/镜像，复用DirectByteBuffer；ForegroundMask新增缓冲构造入口，只在发布时批量复制一次成独立只读快照，避免后续帧覆盖正在使用的结果。新增结果提取、转换、封装三段耗时，保留原采样时间与250ms过期。

本次通过：Pixel8/API36相关21项设备功能测试（12.147秒）＋1项横移测量执行（3.671秒）；核心118/宿主13项JVM零失败，Debug/Test构建、lint和算法AAR Release通过（合并构建86秒），架构0警告；app显式无正式签名Release/R8构建通过（81秒，非发布签名）。AAR自有libbeauty_rgb_input.so四ABI ELF PT_LOAD≥16KB，Release APK zipalign -c -P 16 4通过；不代表全部第三方ELF或16KB设备验收。

| 同一公开横移夹具短测 | 优化前 | 原生后处理后 |
| --- | ---: | ---: |
| 后处理平均 | 44.72ms | 4.46ms |
| 完成结果平均间隔 | 83.84ms | 38.70ms |
| 蒙版年龄中位数 | 137.44ms | 66.67ms |
| 蒙版年龄P95 | 200.00ms | 162.17ms |
| 当前帧/旧蒙版二值IoU | 0.561 | 0.669 |

本轮细分：提取0.095ms、转换入口4.004ms（包括缓冲准备/JNI）、快照0.357ms；GPU推理均值31.19ms、排队10.69ms，84帧中82帧有有效蒙版、2帧启动无结果，71次完成。非温控交替实验，前后推理负载也有波动，不将全链路变化完全归因于C++。IoU参考为同模型当前位置结果，不是人工真值；CPU诊断图上仍有局部人物切边，动态画质未验收通过，尤其不能推断真人转头/头发摆动已解决。

覆盖：NativeMaskDeviceTest(3)，原ForegroundScheduling/Scene/Controls/Persistence/InputContract/TextureBridge共18项；新增JVM缓冲游标/防御复制检查。测试原生浮点量化含NaN/无穷值，所有方向/镜像、奇数尺寸及非对齐输入逐字节与旧Kotlin参考一致；边界内存拒绝写入，原生输出复用不会覆盖已发布蒙版。

执行：`gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :core:lib_beauty_mediapipe:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；`gradlew.bat :app:assembleRelease -PrequireReleaseSigning=false`；Pixel8指定21项类的instrumentation和单独ForegroundMotionDeviceTest。Debug/Test APK已安装；测试后6个用户配置文件逐字节恢复一致，未保存实时摄像头帧。

未验收：真实直播引擎、远端合成、长时温升及GPU竞争、多设备、16KB设备、正式签名Release运行、真人动态画质。既有停机帧计数竞争未修改或推进基线；Web/iOS未更改。

证据：[功能测试](evidence/native-mask-20261028/device.log)、[横移测量](evidence/native-mask-20261028/foreground-motion.json)、[公开夹具图](evidence/native-mask-20261028/foreground-motion-public.png)、[Release/R8](evidence/native-mask-20261028/release.log)、[自有ELF](evidence/native-mask-20261028/native-elf.json)、[源码指纹](evidence/native-mask-20261028/source-fingerprint.json)。历史结果保留如下。

---

# 2026-10-28 快速移动效果实测：未达到动态观感要求

使用Pixel 8/API36运行新增ForegroundMotionDeviceTest：公开人像192×240、GPU分割，84帧30Hz往返横移（72px/约0.4秒），经过公共MediaPipeForegroundMasks；未读取或保存实时摄像头，没有修改用户配置。1项测量测试通过，但动态画质验收失败：可见人物被切掉、原背景露出，不能把测试执行通过描述为效果通过。

最终短测81/84帧有可用蒙版，开始3帧无结果；蒙版年龄中位137.44ms/P95 200.00ms。推理均值39.23ms、后处理44.72ms、排队16.31ms、输入复制0.18ms、准备0.10ms，完成结果平均间隔83.84ms，共32次完成。此前同一夹具重复运行中位年龄约166.67ms，说明短测存在波动，不是持续稳态测量。

当前帧对旧蒙版的二值IoU平均0.561；按蒙版原采样位置对齐同模型参考后IoU为1。参考是同一模型逐个位置完成后的结果，非人工标注真值，不证明人像抠图准确率。结论：时间错位是此横移夹具的显著问题；推理后的蒙版读取/逐像素转换/封装阶段与推理本身同量级，需重点优化，不能只改羽化。未在本轮测试请求中修改生产实现。

图示上排为当前帧配旧蒙版，下排为当前位置的同模型参考；为公开测试图的CPU诊断合成，未经过生产OpenGL边缘重建，不冒充实际相机截图。头部真实转动、遮挡、头发摆动、GPUPixel竞争、高清输出及持续温升未测试。本轮未推进动态画质通过基线；之前18项设备功能测试保持历史结果。

命令：`gradlew.bat :app:assembleDebugAndroidTest`；`adb -s <Pixel8> shell am instrument -w -r -e class com.beautysdk.demo.ForegroundMotionDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。

证据：[测量结果](evidence/foreground-motion-20261028/foreground-motion.json)、[测试执行](evidence/foreground-motion-20261028/device.log)、[公开夹具对照图](evidence/foreground-motion-20261028/foreground-motion-public.png)、[源码指纹](evidence/foreground-motion-20261028/source-fingerprint.json)。

---

# 2026-10-28 动态虚拟背景：分割延迟优化

本轮通过：Pixel 8/API36最终18项设备测试（13.285秒），核心117＋宿主13项JVM零失败；Debug/Test构建与lint通过（47秒），架构检查0警告。核心JVM任务UP-TO-DATE，结果为已有产物核验。独立CPU/GPU夹具比较1项通过（8.731秒，早于最终GPU_PREFERRED与诊断变更）；不冒充最终源码全部19项重跑。

原因与修改：静态边缘融合不能消除旧蒙版落后于当前帧。公共MediaPipeForegroundMasks将忙时拒绝改为固定两槽、仅保留最新待处理帧，Direct RGBA复用原生融合转换/行对齐，新增显式delegate选择；Demo GPU优先，初始化失败回CPU。原采样时间、250ms期限、代次失效均保留；无Bitmap、无运动外推和视频延迟。

最终设备范围：ForegroundSchedulingDeviceTest(3)、SceneDeviceTest(5)、SceneControlsDeviceTest(1)、ScenePersistenceDeviceTest(1)、MediaPipeInputContractDeviceTest(4)、TextureBridgeDeviceTest(4)。覆盖最新pending替换、忙时reset换时间线、重复时间戳、奇数宽行对齐/四方向/镜像、真实模型、背景/运镜/参数持久化与2D/OES桥。

公开固定人像192×240，各delegate预热5次、测20次：CPU推理中位89.540ms/P95 100.820ms；GPU中位42.259ms/P95 51.350ms。顺序、温度和负载未控制，仅为该机短测选择依据。最终相机短日志确认GPU：一个窗口推理平均16.317ms/最大20.179ms，结果间隔最大115.698ms；一个即时蒙版年龄41.596ms且ready=true。不是全帧蒙版年龄分布，不是持续30FPS证明，不与固定夹具耗时混算。

命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；`adb -s <Pixel8> shell am instrument -w -r -e class <上述六类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。最终安装Debug及Test APK；测试后恢复用户配置，6个配置文件逐字节一致，未保存实时摄像头帧。

未验收：真人快速移动/细发丝/遮挡画质，长时间GPU竞争和温升、厂商远端推流、多设备矩阵、GPU运行期故障恢复。已有CameraDeviceTest停机帧计数竞争未修改或推进基线。降低分割延迟不能保证模型漏分或拖影完全消失。

证据：[最终设备测试](evidence/segmentation-latency-20261028/device-final.log)、[构建](evidence/segmentation-latency-20261028/build-gpu-preferred.log)、[固定夹具比较](evidence/segmentation-latency-20261028/foreground-delegates.json)、[实际GPU日志](evidence/segmentation-latency-20261028/live-gpu.log)、[配置恢复](evidence/segmentation-latency-20261028/restoration.json)、[源码指纹](evidence/segmentation-latency-20261028/source-fingerprint.json)。历史测试保留如下。

---

# 2026-10-28：选中效果与手动场景重启恢复（最新）

补齐宿主配置记忆：原有美颜参数、启用状态和资源选择继续用公共状态库；新增LabSceneStore持久化手动背景/运镜覆盖。保存模式、颜色、资源图片、虚化半径、焦点、倍率、周期和关键帧；显式关闭也保存。背景图片只在资源变化时编码为私有PNG并校验摘要恢复，滑杆不重复编码，不保存相机帧。存档按类别/主题资源选择绑定，新的同类资源或主题能取代旧覆盖，不影响其他类别。

ResourceCenterModel共用进程级串行IO队列，关闭页面后完成已接受的保存，再释放快照；重新创建的Model在队列中读取磁盘，避免退出时丢掉排队修改。UI依旧拒绝关闭或旧代次的发布。场景JSON用AtomicFile写入；它与公共user-state.json不是跨文件事务，磁盘故障/保存前强杀不能保证最新一次操作，错误在页面报告；本轮没有模拟断电或磁盘满。

**Pixel 8 / Android16 API36，14项相关设备回归通过，27.424秒，0失败。** 新增ScenePersistenceDeviceTest在提交后立即清除ViewModel并重建，覆盖图片像素/焦点、虚化、倍率/周期、关键帧、美颜开关及参数、关闭重启、新资源覆盖，并打开真实相机Activity断言最终渲染选项。其余13项验证分类关闭、资源/主题/默认导入、参数快速修改及贴纸保存。LabSceneBackup保护并恢复场景JSON/图片和用户状态，外层再次核验5份原配置；最终Debug APK已安装，未保存摄像头帧。

构建与lint通过，架构门禁0 warning。命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`；`adb -s 39061FDJH00D3J shell am instrument -w -e class <summary.json中9类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。首次编译发现宿主访问StickerImage内部pixel方法，已改用既有公开copyArgb；最终构建及设备验证通过。

未修改SDK渲染算法、资源包协议、iOS或Web，因此未重跑无关像素/全量JVM/Web；本轮不是持续性能、真人画质、真实厂商推流或全机型验收。已知相机停止帧计数竞争仍未修复。

[14项回归](evidence/settings-memory-20261028/device.log) · [构建](evidence/settings-memory-20261028/build-final.log) · [恢复](evidence/settings-memory-20261028/restoration.json) · [源码与限制](evidence/settings-memory-20261028/summary.json)

---

# 2026-10-28：虚拟背景轮廓漏色与虚化光晕（最新）

用户反馈头部轮廓大量模糊。代码定位：SceneGlPass原先直接双线性放大蒙版，再用0.25～0.75宽置信度过渡；虚化25点无条件累计原图，包含人物颜色。两者会造成不确定轮廓混入原背景，以及前景颜色向背景扩散。没有采集用户真人画面，不能据此确认所有模糊均由这两处引起。

Android公共BeautySceneRenderer现对不确定区使用原图颜色引导的四邻域蒙版重建，按蒙版像素中心采样并收窄软过渡为0.35～0.70；完整前景直接保留。BLUR只累计可信背景并归一化，无可信样本时回退当前源颜色，避免黑边。保留R8蒙版/FBO复用，无新Bitmap、CPU读回、时间平滑或有效期延长。适用于公共2D/OES输入，非相机专用；颜色相近、漏分、异步旧蒙版和细发丝仍存在模型局限。

**核心117项JVM通过；Pixel 8 / Android16 API36共6项设备测试通过（4.819秒），0失败。** 新增合成红/蓝硬轮廓+16×16蒙版+64×64输出，用真实GPU像素验证前景边缘、背景漏色、虚化光晕、alpha和8种方向/镜像元数据；其余覆盖真实公开人像模型、尺寸/过期/reset、纯纹理桥、OES/背景/运镜、GL状态及场景控件。不是实际发丝、运动延迟和持续性能验收。

命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 --console=plain`；覆盖安装两APK；`adb -s 39061FDJH00D3J shell am instrument -w -e class com.beautysdk.demo.SceneDeviceTest,com.beautysdk.demo.SceneControlsDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。最终APK已安装，5份配置恢复核验通过，未保存相机帧。

边缘新增颜色采样，虚化新增蒙版采样，完整前景提前返回；未测量GPU净耗时，不宣称更快。未验证持续真人视频、头发/背景同色、快速移动遮挡、其他机型、真实厂商推流；iOS渲染器未同步。本轮未重跑无关UI/Web，也没有修复既有相机停止计数竞争。

[设备6项](evidence/background-edge-20261028/device.log) · [构建与JVM](evidence/background-edge-20261028/build.log) · [配置恢复](evidence/background-edge-20261028/restoration.json) · [基线及限制](evidence/background-edge-20261028/summary.json)

---

# 2026-10-28：底部效果面板视觉重做（最新）

针对用户反馈的“大块按钮、选中底板重复、弹框不美观”，分类与预设/微调改为文字下划线；参数使用统一线性图标（口红、腮红、睫毛分开），轻量选中背景；缩略图统一72dp方形、名称置下，移除相机卡片的冗余整块底色和选中前缀；滑杆统一强调色。竖屏参数页高度从42%降至36%，资源页54%降至48%，保留240dp最小高度及滚动。没有修改SDK算法、资源协议或Web。

最终Debug与Test APK覆盖安装Pixel 8 / Android16 API36。构建及lint通过，UI门禁0 warning。首轮12项通过（22.060秒），图标区分补充后最终10项通过（19.410秒），1.3字体竖屏4项通过（10.098秒），均0失败。查看纯控件截图，不保存相机帧；5份用户配置逐字节恢复，字体恢复1.0。未复测本轮横屏、完整语言/日夜、多机及持续性能，旧停止帧计数竞争仍未修复。

命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`；`adb -s 39061FDJH00D3J shell am instrument -w -e class <summary.json中7类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`；大字体选择LabHierarchy、EffectPanel、SheetScroll共3类。

[最终10项](evidence/panel-style-20261028/device-final.log) · [大字体4项](evidence/panel-style-20261028/device-font.log) · [微调面板](evidence/panel-style-20261028/lab-adjust-panel.png) · [构建](evidence/panel-style-20261028/build-final.log) · [恢复](evidence/panel-style-20261028/restoration.json)

---

# 2026-10-28：实验室层级与紧凑操作整理（最新）

已覆盖安装最终Debug APK到Pixel 8 / Android16 API36，恢复测试前5份偏好、默认配置和草稿，字体、方向、USB常亮均核验恢复。未保存相机或真人帧。

- 主页面只保留一个效果入口；资源中心、调试、全局美化与可选原图小窗放入“更多”。人脸/手势状态压成两条摘要，点击打开独立回调详情，保留最近12条进入/离开和手势事件；手势开关仍可在详情操作。
- 美颜、美型、美妆、调色默认微调，素材类默认预设；美颜一次只展示所选参数滑杆。分类、模式和完成按钮合并紧凑固定栏，内容独立滚动；横屏采用右侧面板。
- 相机素材卡缩小并去掉冗余安装状态。新增“关闭本类效果”，清除当前分类并保留其他分类；独立资源管理页继续保留管理信息。

**最终相关设备回归36项通过（54.397秒）；1.3倍字体竖屏9项通过（18.285秒）、横屏9项通过（18.364秒），共54次执行、0失败。** 新增2项层级/单滑杆/分类关闭用例；同时回归回调展示、滚动、预设切换、资源组合、背景/运镜、主题与持久化消费者。回调记录用确定输入验证，不能当作真人识别准确率结论。面板截图仅绘制控件View，预设测试含临时资源卡，测试后已恢复用户资源选择。

构建命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`，成功；UI/i18n门禁均0 warning。设备命令：`adb -s 39061FDJH00D3J shell am instrument -w -e class <summary.json中的15类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`；大字体两组选择LabHierarchy、CallbackDisplay、EffectPanel、SheetScroll、DemoLayout共5类。

本轮限于Demo布局和资源操作，没有改动检测/渲染算法或Web，未重跑全量JVM/Web；没有进行真人连续画质、持续性能、真实直播引擎、其他机型、完整语言/日夜或Release验收。相机停止后帧计数的既有间歇竞争仍未修复，不推进该用例基线。

[36项回归](evidence/lab-clean-20261028/device-final.log) · [大字体](evidence/lab-clean-20261028/device-font.log) · [横屏](evidence/lab-clean-20261028/device-landscape.log) · [构建](evidence/lab-clean-20261028/build.log) · [预设面板](evidence/lab-clean-20261028/presets-final.png) · [微调面板](evidence/lab-clean-20261028/adjust-final.png) · [配置恢复](evidence/lab-clean-20261028/restoration.json) · [源码指纹与限制](evidence/lab-clean-20261028/summary.json)

---

# 2026-10-28：主预览显示手势与人脸进入/离开

已更新CameraTestActivity、activity_camera_test.xml及三语言lab_strings，并安装最终Debug APK到Pixel 8 / Android16 API36。主预览下方显示当前检测状态和最近一次人脸进入/离开；手势开关及状态从调试面板移到主页面，显示最近一次手势名称、阶段及事件ID。空事件批次保留最近回调，旧ID不覆盖新ID；相机启停/异常清空会话展示，手势开关切换清空旧事件。

人脸展示由已有约250ms限频统计中的READY/NO_FACE状态转移生成，重复快照不重复计数，初始无人脸不产生离开，STALE/ERROR不当作离开。没有新增公共SDK逐帧人脸事件API，不保证展示小于统计间隔的进出；也不额外开启持续推理。无定位需求时提示未启用，选择需要定位的贴纸/美型/美妆后沿用原有分析支路；手势默认关闭，显式开关启用。

**13项相关设备测试通过，32.224秒；1.3倍字体竖屏5项通过、横屏5项通过。** 新增CallbackDisplayDeviceTest的2项用确定输入调用现有页面回调，验证进入/重复/过期/错误/离开/再进入/会话重置，以及手势保持、批次最新事件、旧ID拒绝及清空。此两项是展示层测试，不冒充真人识别。另回归LabInteraction、EffectPanel、SheetScroll、DemoLayout与5项GestureDeviceTest；既有手势用例包含真实官方图片输入模型，不能外推为本次真人动作识别验收。

命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`；两APK覆盖安装；`adb -s 39061FDJH00D3J shell am instrument -w -e class <上述6类> com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`，字体/方向专项选择CallbackDisplayDeviceTest与DemoLayoutDeviceTest。Debug/Test APK和lint通过；UI/i18n门禁0 warning。测试前5份偏好/默认/草稿逐字节恢复，字体、方向与常亮设置已恢复；未保存相机帧。

未验证：真人连续进出/手势画质、其他机型、完整英文/日夜矩阵、真实直播引擎、长期温升及Release。未修改核心检测/渲染算法，未重跑全量JVM/Web。已知相机停止帧计数竞争仍未修复，不推进该用例基线。

[13项回归](evidence/callback-ui-20261028/device.log) · [大字体](evidence/callback-ui-20261028/device-font.log) · [横屏](evidence/callback-ui-20261028/device-landscape.log) · [构建](evidence/callback-ui-20261028/build.log) · [统计和限制](evidence/callback-ui-20261028/summary.json) · [配置恢复](evidence/callback-ui-20261028/restoration.json)

---

# 2026-10-28：美颜实验室交互与面板整理

已安装最终Debug APK到Pixel 8 / Android16 API36。**32项相关设备回归通过；1.3倍字体竖屏3项、1.3倍字体横屏3项通过**。已查看纯控件面板截图，没有保存相机帧；测试前5份用户偏好、默认配置和草稿逐字节恢复，字体、方向和USB常亮均恢复。

本次整理：

- 底部效果面板固定标题、八分类及操作栏，内容独立滚动，不与弹框拖动争抢手势。
- 美颜、美型、美妆、调色、背景、运镜拆成“预设 / 微调”；默认显示资源预设，微调仅显示当前分类参数；贴纸和套装直接显示资源。
- 可见资源卡片再次点击即可取消；选中状态明确提示。选择美颜类预设时启用美化，解决已关闭开关时点预设没有效果的误解。取消整套主题不再先拆成子资源遗留效果。
- 参数选中项高亮，预设/微调切换复用参数View，关闭重开保留当前分类与模式；重置增加确认，普通分类只重置该分类，套装明确提示影响全部分类。
- 移除主预览区域的手势测试操作，移入调试；主画面性能信息收敛为帧率/合成耗时，详细统计保留在调试面板。中文/英文文案同步。
- 横屏缩小资源卡片、隐藏重复操作提示，保留内部滚动；修正API24不支持的样式padding属性。

改动集中在app的CameraTestActivity、ResourceCenterController/Model、activity_camera_test.xml、模式/分类样式及三套lab_strings；未改推理、渲染和厂商适配链路。

验证命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`，构建成功；lint 0 Error / 0 Fatal / 146 Warning（并非零警告）。UI与i18n门禁均0 warning。两个APK覆盖安装，设备runner为`com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`，`-e class`选择[汇总中的13个类、32个方法](evidence/lab-ux-20261028/summary.json)。覆盖预设启用/取消、主题取消、微调参数高亮、固定分类、关闭重开、重置确认/取消、真实滑动、分类重置保留其他效果、资源导入下载默认、背景蒙版和运镜生效、首页路由及贴纸持久化。字号/横屏专项选择LabInteractionDeviceTest、EffectPanelDeviceTest、SheetScrollDeviceTest。

首次面板测试有1项重置弹窗按钮定位失败，改为访问真实AlertDialog按钮，保留确认与取消的业务断言；首次横屏测试有1项输入落在窗口外，定位到入场动画期间坐标不稳定，增加等待并与可见窗口取交集后复测通过。首次构建曾有2项API24样式NewApi错误，改用start/end/top/bottom后最终构建通过。原日志均保留，不把首次失败隐藏成全程无问题。

未执行：其他设备/API24运行、完整英文/日夜视觉矩阵、旋转重建/进程死亡专项、真人动态画质、持续温升、真实直播引擎远端和Release；本轮UI修改不重复未变更SDK算法的全量JVM/像素测试。上一轮停止后帧计数偶发回写竞争仍未修复，本次未修改或复测该失败用例，不推进其通过基线。未改动、发布Web或提交代码。

[最终32项](evidence/lab-ux-20261028/device-final-current.log) · [大字体](evidence/lab-ux-20261028/device-font-final.log) · [横屏](evidence/lab-ux-20261028/device-landscape-final.log) · [构建](evidence/lab-ux-20261028/build-final-verified.log) · [预设面板](evidence/lab-ux-20261028/lab-presets-panel.png) · [微调面板](evidence/lab-ux-20261028/lab-adjust-panel.png) · [恢复核验](evidence/lab-ux-20261028/restoration.json) · [源码指纹](evidence/lab-ux-20261028/source-fingerprint.json)

---

# 2026-10-28：六类效果真机功能验证

**结论：六类效果的功能链路有真实输出，但整体稳定性仍有1处间歇失败，不能宣称全部验收通过。** Pixel 8 / Android16 API36，当前Debug主APK未改动，仅更新两个旧相机用例的资源准备方式及共享测试辅助函数。

最终完整设备回归 **81项通过、0失败，100.805秒**；随后两个相机用例重复5轮共10次，**9次通过、1次失败**：停止后帧计数偶发由0回到1。155项JVM测试全部通过（核心117、相机13、资源8、GPUPixel4、feature13），0失败/错误/跳过。本轮没有更改业务代码修复该竞争。

| 类别 | 本次验证内容与证据 | 判断范围 |
| --- | --- | --- |
| 美颜/调色 | GPU真实纹理像素变化：磨皮、美白、红润、亮度、对比度、饱和度、锐化；alpha、采样变换及GL状态恢复 | 功能通过；未做多肤色/光照画质验收 |
| 美型 | 瘦脸、大眼像素变化；瘦鼻、下巴正负、嘴形正负的方向、旋转一致性、缺失点旁路；公开人像输出 | 功能通过；嘴形满强度样图可见拉伸和模糊，不能作为自然度通过 |
| 美妆 | 口红、腮红、程序化睫毛及3套PNG睫毛；口腔留空、强度、无脸/坏点/单侧缺点/闭眼合成、资源组合与替换 | 功能通过；满强度口红偏艳；320×400样图的脸部像素不足以验收睫毛细节 |
| 贴纸 | 6套PNG眼镜/胡子/帽子等加载及实际人像合成、三个独立槽位共存、过期隐藏；真实关键点与native已知小位移/空白帧拒绝 | 功能通过；未模拟完整快速转头、真人遮挡和进出画面 |
| 运镜 | 推近像素输出、平移/OES合成、关键帧资源和UI生效；JVM覆盖全部模式/方向/镜像及FACE_FOLLOW裁剪边界 | 功能通过；未做真人连续跟拍和运镜审美验收 |
| 背景 | 纯色/图片/模糊像素、焦点裁切、真实公开人像分割的前景/背景置信度、reset/过期/尺寸约束、OES和实时CameraX蒙版就绪 | 功能通过；未做发丝、手部遮挡和动态拖影验收 |

另覆盖：资源27项安装/组合、完整交付包及默认显式应用、真实HTTPS线上旧17项目录下载、分辨率重建、纹理入口、底部分类/滑动、启停/前后摄、手势和主题资源等现有回归。具体81个方法见[用例与结果汇总](evidence/all-effects-20261028/summary.json)。单项通过不代表所有组合、所有设备均通过。

## 本轮发现

1. **未解决：相机停止与在途GL帧统计竞争。** 首次完整回归出现`expected 0 but was 1`，修正资源准备后5轮重复中的第2轮再次复现。`GpuBeautyPreview.stop()`主线程清零，GL线程已进入的绘制末尾仍可能`renderedFrames++`；需后续对停止代次和统计提交作同步处理。本证据证明计数回写，不足以认定停止后仍持续采集或推流。失败用例不推进本轮有效通过基线。
2. **已修正测试前置：旧隐藏贴纸开关不再能代表统一资源已启用。** 首次完整回归79/81通过，另有GPU分析启动超时；单独复跑两项也失败。当前用例等待资源模型初始化，并通过公共资源入口选择`round_glasses`，恢复用户配置，保留原输出/停止/GPU分析断言。此后GPU启动通过完整回归、独立复跑和5轮重复。没有删除断言或修改SDK默认效果规避问题。
3. **画质观察，不等于算法断言失败：** 已实际查看公开人像输出并生成标注放大对照。口红最大强度明显偏艳，嘴形最大强度有模糊/拉伸；睫毛测试图面部较小，无法凭此判断睫毛精细度。未将“像素改变”当作商业级妆感保证。

起始设备再次息屏，首次运行在相机阶段中止，恢复配置后临时开启USB常亮，再执行完整回归；所有原始日志保留。结束时恢复原常亮值0及测试前5份偏好/默认/草稿文件，摘要逐字节一致。只保存公开测试人像及纯UI图片，不保存相机帧。

命令：`gradlew.bat :app:assembleDebugAndroidTest --max-workers=1 --console=plain`；`adb -s 39061FDJH00D3J install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`；`adb -s 39061FDJH00D3J shell am instrument -w com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。重复使用`-e class`选中CameraDeviceTest#cameraPageProducesProcessedImageAndStopClearsIt、MediaPipeDelegateDeviceTest#cameraGpuOptionProducesAnalysisWhileRendering。JVM运行五模块testDebugUnitTest，使用既有128MiB测试配置强制重跑。

未执行：真人动作画质/快速跟随、30分钟持续温升、真实厂商回调与远端视频、iOS、其他实体设备、完整语言/视觉矩阵和Release。Web本轮未改动或重新发布。CPU/GPU静态样图计时仅代表直接输入推理，不代表实际直播帧率。

[最终81项](evidence/all-effects-20261028/device-current-entry.log) · [首次完整2项失败](evidence/all-effects-20261028/device-final.log) · [重复测试失败](evidence/all-effects-20261028/stability-2.log) · [测试构建](evidence/all-effects-20261028/build-tests-final.log) · [JVM](evidence/all-effects-20261028/jvm.log) · [人像标注对照](evidence/all-effects-20261028/portrait-face-comparison.png) · [配置恢复](evidence/all-effects-20261028/restoration.json) · [源码指纹](evidence/all-effects-20261028/source-fingerprint.json)

---

# 2026-10-28：资源面板调参效率优化

本轮修改ResourceCenterModel/Controller及对应设备用例。原实现每次调参都扫描并解包全部已安装ZIP、重新读取封面，封面数组变化又触发卡片全部重建。现调参、手动场景和总开关复用资源快照；主题首次拆分、导入、下载、删除和跨页面刷新仍重新扫描，封面按内容摘要复用。初始化/返回页的状态解析共用一次目录快照。删除后释放不再使用的封面引用，页面销毁时由同一IO队列释放配置快照，防止与prepare交错。

Pixel 8 / Android16 API36覆盖安装最终Debug/测试APK，**21项设备回归通过，22.653秒**。新增用例验证连续40次调参、不同字段与末值落盘、调用方可变Map隔离、卡片/封面对象不变，以及另一管理器删除/重新安装后的列表/封面刷新。既有资源、主题拆分/重开、完整包导入/显式默认、真实相机场景、底部面板和首页路由同时通过。测试前4份用户配置逐字节核对恢复。

最终Debug/测试APK与lint构建成功（1分12秒）；lint 0 Error / 0 Fatal / 139 Warning，UI门禁0 warning。首轮设备已Dozing/息屏，21项中6项界面断言失败；唤醒并确认无锁屏遮挡后同一源码完整重跑21项全通过，未删除断言或改变锁屏安全设置。

命令：`gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain`；两APK以`adb -s 39061FDJH00D3J install -r`覆盖安装（测试包加`-t`）。`am instrument -w -e class`选择ResourceCenterUiDeviceTest、ResourceCenterDeviceTest、ResourceDeliveryDeviceTest、SceneControlsDeviceTest、GpuPixelControlsDeviceTest、EffectPanelDeviceTest、HomeNavigationDeviceTest、ThemeDemoDeviceTest，runner为`com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。

本次未修改Web或推理/渲染算法，未重复JVM/Web全量测试。对象复用断言证明取消了重复列表构建，不能换算为直播FPS或毫秒收益；真人动态美观、长时间温升、厂商推流及完整语言/横竖屏视觉矩阵未复测。没有发布或推送。

[最终设备结果](evidence/resource-ui-20261028/device-final.log) · [首轮息屏失败](evidence/resource-ui-20261028/device.log) · [最终构建](evidence/resource-ui-20261028/build-final.log) · [UI门禁](evidence/resource-ui-20261028/ui-gate.log) · [配置恢复](evidence/resource-ui-20261028/restoration.json) · [工作区源码指纹](evidence/resource-ui-20261028/source-fingerprint.json)

---

# 2026-10-27：Web / APK 整体分析、真机回归和修复

本轮已将当前Debug APK安装并启动于Pixel8 / Android16 API36。最终完整**80项设备测试通过**（98.84秒），另3项大字体/横屏专项通过；五模块**155项JVM实际重跑通过**，Web**28项通过**。Debug/Test APK与lint通过，0 Error / 0 Fatal / 139 Warning；UI/i18n/代理配置门禁通过。

修复Web真实下载被导航逻辑拦截、无修改切页丢失交付设置、主题来源提示；同步APK当前27资源，新增Web完整交付ZIP预检/批量安装/显式默认应用，修复内置删除后自动装回。首次5失败、中间2失败和最终全绿均保留，未删断言或关闭效果规避。

真实线上HTTPS旧17项目录下载校验通过，本地新版尚未发布。原设备配置/草稿已恢复并核验，未保存相机帧。未做真人动态画质、长时温升、厂商推流、iOS、其他实体设备或Release验收。

[整体分析与覆盖](WEB_APK_REVIEW.md) · [最终80项](evidence/web-apk-full-20261027/device-final.log) · [首次失败](evidence/web-apk-full-20261027/device-initial.log) · [JVM](evidence/web-apk-full-20261027/jvm-final.log) · [Web](evidence/web-apk-full-20261027/web-tests.log) · [下载回读](evidence/web-apk-full-20261027/web-downloads.json) · [源码指纹](evidence/web-apk-full-20261027/source-fingerprint.json)

---

# 2026-10-27：相机面板、独立资源中心与场景生效修复

## 本次实现

- 新增独立ResourceCenterActivity，首页及相机资源入口均进入独立页面，不创建相机或模型。导入、HTTPS目录配置/下载、删除和默认管理集中于此；导入/下载仅安装，点击已安装卡片才启用，目录地址显式配置并记住。
- 相机默认主画面效果、小窗原图。八分类共用底部面板，参数先于已安装卡片，管理按钮隐藏；参数横向选择、卡片选中态、不足三列补空列，移除不匹配分类的提示。
- 背景/运镜统一提交ResourceCenterModel最终themeSettings。串行保留快速跨字段修改，重置仅当前分类，跨页刷新只清除变化分类的手动场景覆盖。关键帧直接复用最终场景并保留timeline，倍率由素材时间线控制。手动场景是ViewModel会话状态，资源选择与美颜参数沿用持久化。
- 显式同视场比例蒙版映射：MediaPipeForegroundMasks.allowScaledFrames默认false，CameraX同ViewPort小图显式开启。保持真实采样时间与250ms有效期，拒绝未来、错误方向/镜像或不同比例；缓存映射避免重复上传。背景显示等待/就绪，人脸分析异常不再误锁死独立渲染。

## 本次验证

- JVM **142项全部通过**：核心117、资源8、GPUPixel4、feature13，0失败/错误/跳过。包含新增蒙版比例映射及年龄/旋转镜像/错误几何拒绝，以及既有手势防重复回归。
- Pixel 8 / Android16 API36：覆盖安装最终Debug和测试APK，**23项设备测试全通过，9.574秒**。覆盖独立资源页无相机、导入不启用、显式选择落盘、分类与管理隐藏、最终背景/运镜参数、快速跨字段更新、分类重置、跨页关键帧刷新、真实CameraX蒙版映射就绪，以及GPUPixel夹具像素变化。
- 资源中心横屏+1.3倍字体、相机面板竖屏+1.3倍字体各1项复跑通过，方向与字体设置已恢复；已查看独立页及纯控件面板图。截图仅资源页或控件View.draw，不保存相机图像。测试用户资源配置已恢复并核对一致。
- Debug/测试APK和lintDebug通过：0 Error、0 Fatal、139 Warning；UI/i18n门禁均0 warning。本轮未跑Release/R8，旧包体及Release结果不代表新源码。

构建运行app的assembleDebug、assembleDebugAndroidTest、lintDebug，以及核心/资源/GPUPixel/feature的testDebugUnitTest，使用单worker和原128MiB测试配置。最终app修改后重建app与测试APK、lint，并完整重跑23项设备测试。

## 问题与限制

联动测试先后发现新增默认循环时间线未闭合、旧DemoEffects构造路径遗漏关键帧timeline；均已修复，最终完整23项通过，未删除断言或关闭效果规避。

本机直连原写死HTTPS目录返回403，只能证明该请求被拒绝，不能据此断言所有设备或浏览器不可访问。现改为独立资源页显式配置可访问目录；未部署线上资源或完成生产下载联调。既有离线/摘要/坏包测试继续通过。

真人动态观感、发丝/遮挡、持续性能与温升、完整小屏/语言/日夜/相机横竖屏矩阵、直播厂商及远端输出、其他设备和16KiB运行未验收。同视场映射不能用于不同crop或留边。

## 证据

[最终构建](evidence/demo-ui-scene-20261027/build.log)、[JVM构建](evidence/demo-ui-scene-20261027/jvm-build.log)、[142项汇总](evidence/demo-ui-scene-20261027/jvm-summary.json)、[23项设备](evidence/demo-ui-scene-20261027/device.txt)、[资源横屏大字体](evidence/demo-ui-scene-20261027/resource-landscape-font130.log)、[面板大字体](evidence/demo-ui-scene-20261027/camera-panel-portrait-font130.log)、[资源页](evidence/demo-ui-scene-20261027/resource-center-page.png)、[纯控件面板](evidence/demo-ui-scene-20261027/camera-controls-panel.png)、[源码指纹](evidence/demo-ui-scene-20261027/source-fingerprint.json)。TEST_BASELINE.json追加demoUiScene20261027，保留历史条目，未提交工作区以SHA256标识。

---

# 2026-10-27：手势重复调用专项修复

保持静态手势不会因冷却到期重复TRIGGER；短暂丢手保留最多两条跟踪的静态锁存，双手暂时漏检保留对应静态锁存，交互仍立即CANCEL且不跨无手采样复用运动轨迹。松开计时改从首次负样本开始，避免单个较晚负样本误解锁，也能在下次采样已恢复手势时正确判定稳定松开。跨跟踪冷却拒绝不会提前消耗新候选的触发资格。

- 核心JVM **116项通过，0失败/错误/跳过**；新增6项覆盖保持超过冷却后的短暂丢手、单帧晚期分类抖动、松开后重入、冷却拒绝后恢复、双手合十漏检，以及并发轮询一次性交付。原有捏合START/UPDATE/END/CANCEL与重复采样回归继续通过。
- 执行：`gradlew.bat :core:lib_beauty:testDebugUnitTest --max-workers=1 -I build/functional-test/gestures-20261027/test-memory.gradle`，BUILD SUCCESSFUL。本轮仅修改纯Kotlin状态机、单测与接入文档，未重跑设备、APK/Release及持续真人性能；下方旧包体/设备结果属于此前源码版本。
- 文档明确：宿主按TRIGGER/START启动，UPDATE仅更新；快照回调频繁不代表触发事件重复。不同kind可能表达同一手型，需宿主订阅/优先级过滤。宿主动画播放中去重/排队由宿主控制。
- 证据：[构建](evidence/gesture-repeat-20261027/build.log)、[116项汇总](evidence/gesture-repeat-20261027/jvm-summary.json)、[修改源码指纹](evidence/gesture-repeat-20261027/source-fingerprint.json)。TEST_BASELINE.json追加gestureRepeat20261027，保留历史条目。合成规则测试不代表真人遮挡/交叉跟踪准确率通过。

---

# 2026-10-27：可选手势识别与宿主触发事件

已按用户要求保留MediaPipe + GPUPixel，新增手势分析，但只提供识别结果和事件；动画由宿主接入，不自动播放、不切主题、不改视频效果。新增公共GestureAnalysis/GestureRuleEngine/GestureAnalysisRuntime，MediaPipeGestureAnalyzer、固定本地手势模型，以及相机可见开关与中英文诊断。手势支路默认关闭，与人脸效果独立，原图模式跳过；单worker/单在途、忙时丢帧、复用DirectByteBuffer，无逐帧Bitmap或新增ONNX/so。

## 本次通过

- 核心JVM110、GPUPixel4、feature13，共127项，0失败/0跳过。新增26项覆盖七种模型分类的稳定/冷却/重入、OK/伸指数量0..5/单手捏心/双手比心/合十/挥手/拍手/画圈的合成坐标正反例、捏合拖动/双手缩放事件阶段、旋转镜像、列表换序、代次、忙时丢帧/独占复制、结果时效、错误与同worker释放。合成坐标不是实验手势真人准确率证据。
- Pixel 8 / Android16 API36：覆盖安装Debug及测试APK，17项设备测试全通过，0失败，26.565秒。5项GestureDeviceTest覆盖官方公开图片中的点赞/比耶/食指向上/拇指向下及21个实际关键点、8种旋转镜像的真实坐标回映射、空白无手、稳定一次触发、禁用清空、相机开关；另跑BeautyDeviceTest/GpuPixelDeviceTest共12项原有回归。没有清应用数据、卸载、保存相机图像或上传帧。握拳/开掌/I_LOVE_YOU类别已接入且状态机单测通过，本轮未提供三者真实图片准确率证据。
- Debug/测试APK、未签名Release/R8、核心与MediaPipe Release AAR通过。命令：`gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :core:lib_beauty:assembleRelease :core:lib_beauty_mediapipe:assembleRelease --max-workers=1 -PrequireReleaseSigning=false -I build/functional-test/gestures-20261027/test-memory.gradle`。最后只修正测试采样重试后，重建测试APK并完整重跑17项。
- app lintDebug：0 Error、0 Fatal、133 Warning。结构/工作流配置/架构/UI/国际化门禁、模型摘要和公开测试图摘要校验通过。UI自动化覆盖开关与相机数据交付，未做完整视觉矩阵。
- 手势模型8,373,440字节，SHA256 `97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482`；继续使用tasks-vision 0.10.32。产品APK不含公开测试手势图片、RFB或ONNX；现有so清单不增加。
- Debug APK 43,743,183字节（41.72 MiB），未签名Release 33,032,926字节（31.50 MiB）。全部模型12,381,573字节（11.81 MiB）；Debug arm64模型+so 23,776,549字节（22.68 MiB），此项包含宿主辅助so，不含图片/LUT/动画素材、DEX或APK封装。
- Debug/Release APK `zipalign -c -P 16 -v 4`均通过；arm64全部ELF PT_LOAD至少16KiB。既有armeabi-v7a MMKV为4KiB，其余至少16KiB。本轮未改native源码，不等于16KiB设备运行验收。

## 过程问题与限制

首次测试编译发现测试访问私有缓冲字段，已改为持有测试源缓冲；随后本机Java测试进程因内存不足未启动，使用临时128MiB/SerialGC测试配置后完成。最初设备命令没有完整结束输出，单项与完整重跑均保留完整证据。整组首次回归1项失败来自测试重交已丢弃的同时间戳，修正测试为新采样后完整17项通过；SDK继续拒绝重复采样，不改变背压或断言目标。

官方静态模型之外的手势全部是实验性二维/时序规则。真人捏心/比心/合十和连续动作误识别率、侧掌/交叉遮挡、复杂光照、持续同时运行人脸+手势的性能和温升，均未验收。不支持数字6..10或地区数字手语；数字0..5仅表示伸指数。宿主动画、iOS/Web手势、厂商引擎/远端、完整横竖屏/字体/小屏UI矩阵和16KiB设备运行未执行。上述未执行项未推进通过基线。

SDK以真实采样时间限制结果/事件年龄；START/UPDATE/END/CANCEL不是可靠消息通道，固定32项队列可能丢弃过载旧事件。宿主必须在NO_HANDS/STALE/ERROR/DISABLED、换源/停用或超时自行取消交互，不等待终止事件。坐标/线程/时序契约与使用示例见[SDK_GESTURES](../SDK_GESTURES.md)。

## 可复核证据

[构建](evidence/gestures-20261027/build.log)、[设备17项](evidence/gestures-20261027/device.txt)、[JVM汇总](evidence/gestures-20261027/jvm-summary.json)、[APK清单](evidence/gestures-20261027/package.json)、[ELF对齐](evidence/gestures-20261027/elf-alignment.json)、[完整受影响源码指纹](evidence/gestures-20261027/source-fingerprint.json)。工作区源码仍未提交，TEST_BASELINE.json新增gestures20261027独立条目，用源码SHA256标识本轮，保留其他历史条目。

---

以下为历史验证记录，不作为本轮实测结果。

# 2026-10-27：统一资源制作、管理、默认配置与实验室闭环

新增独立Android公共库core/lib_beauty_resources；制作端使用独立BeautyStickerWorkshop/resource-studio.html。实现8分类、名称/说明/默认及多语言、预览封面、参数作用域、单项/套装、catalog/defaults/state JSON、内置/本地/网络同一安装路径、取消/进度、摘要固定版本、离线目录/封面、使用引用与状态保存。实验室使用公共组合配置，继续交给现有相机/直播公共纹理管线，不新增厂商适配。

## 本次通过

- JVM共122项：资源库8、核心110、GPUPixel4，0失败/错误/跳过。最终核心任务使用--rerun重新执行；不把之前失败隐藏为首次全绿。
- Pixel 8 / Android16 API36：19项通过，0失败，7.661秒；覆盖17包、部位叠加、套装拆分、默认/用户合并、缺失包仅关闭对应槽位、缓存/下载注入、安装拒绝、8分类真实UI与GPU/图片睫毛回归。覆盖安装保留数据；资源测试用隔离缓存，UI最后还原原用户状态，不清应用数据。
- Web23项Node测试通过，npm run build成功。真实浏览器制作美颜与图片睫毛、应用编辑器结果、导出套装及交付ZIP、保存/恢复草稿、导出当前默认JSON；3资源/8文件全部包SHA256与bytes一致，theme选择正确且无默认0强度误覆盖。390×844布局无横向溢出。
- Debug与测试APK、lib_beauty_resources Release AAR构建通过。最后完整lintDebug：0 errors、133 warnings；最后封面底板可辨认性改动后再次构建与设备回归通过。结构与工作流配置校验通过。资源IO/封面解码不进入逐帧处理；关闭最后一个句柄后释放已解码资源缓存。

## 修复与复测记录

最初UI用例从Activity查找已移动到BottomSheetDialog的控件导致空指针，修正查找窗口后通过。资源复查补齐不同贴纸槽位、默认与已选资源scope的重启优先级、默认套装互斥、缺失资源容错、Web默认JSON固定当前选择及严格对象类型。

扩大回归时，工作区正在更新的手势用例曾分别出现NO_HANDS/READY时序和双手动作断言失败；保留最初输出。相关工作区更新后，最终核心回归重新执行通过，当前未遗留该失败。本轮不把手势实现归入资源功能交付。

## 未执行与限制

- iOS提供统一解析/管理/Metal桥及共享17包XCTest源码；Windows无Swift/Xcode，未编译/真机验证。Metal美型/口红/腮红及无图片程序化睫毛会在切换前拒绝，不能宣称与Android全部效果等价。
- 网页产物已在独立项目dist生成，未部署Cloudflare；默认网络目录地址待部署后可用。网络回归使用注入HTTPS传输，未验证实际线上下载。
- 未模拟磁盘耗尽、强杀进程、全字体/横竖屏/主题矩阵，未测持续温升、快速移动最终画质、直播厂商回调及远端推流。网络首版无HTTP断点续传/请求共享。
- 17包为协议/功能示例，部分封面使用素材本身；正式资源的成品预览与美术质量需要另行验收。保留设备已安装旧版本，因此面板资源数量可多于17，不删除旧选择引用的版本。

[接入与制作手册](../../resources/unified/v1/README.md) · [实施方案](../design/BEAUTY_RESOURCE_WORKFLOW_IMPLEMENTATION.md) · [结果/指纹](evidence/resource-workflow-20261027/results.json) · [最终设备](evidence/resource-workflow-20261027/device-final.txt) · [针对构建](evidence/resource-workflow-20261027/build-targeted.log) · [最终核心/lint](evidence/resource-workflow-20261027/final-regression.log) · [Web测试](evidence/resource-workflow-20261027/web-tests.log) · [Web截图](evidence/resource-workflow-20261027/web-resource-center.png) · [实验室面板](evidence/resource-workflow-20261027/pixel8-resource-panel.png)

---

以下保留历史验证，不自动扩展为本轮实测。

# 2026-10-27：移除 RFB / ONNX 方案

本次按用户要求删除 core/lib_beauty_rfb 全模块（含模型、五点适配器、源码、测试和构建产物），清理 settings、版本目录、宿主/测试依赖、Demo 枚举与三个控制器、状态提示、中英文资源和模型校验项。保留 MediaPipe + GPUPixel 主线；同步当前架构、项目事实、模块说明和测试计划。历史测试记录及 TEST_BASELINE.json 中的旧源码指纹仅作为历史证据，RFB 用例已退役，不代表当前能力；本轮不推进旧基线。

## 本次通过

- 结构/工作流配置/架构校验、scripts/verify_models.py 通过。
- `gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false --max-workers=2` 通过；核心85、GPUPixel4、feature12，共101项JVM，0失败/0跳过。Release为本地未签名/R8产物。
- app lintDebug：0 Error、0 Fatal、131 Warning（现有警告不代表全部消除）。
- Pixel 8 / Android16 API36：覆盖安装Debug及测试APK，不清数据、不卸载；`am instrument -w -r -e class com.beautysdk.demo.BeautyDeviceTest,com.beautysdk.demo.GpuPixelDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`：12项通过，0失败，11.458秒。覆盖MediaPipe真实人像/释放、RGBA转换、静态页面、贴纸和GPUPixel GL回归。
- `:app:dependencyInsight --dependency onnxruntime --configuration debugRuntimeClasspath` 无匹配依赖；当前源码/构建入口不再引用已删除的RFB类型或ONNX依赖。
- Debug/Release APK均无RFB或ONNX文件。Debug从81,874,998降至35,771,107字节（78.08→34.11 MiB）；Release见package.json。保留模型共4,008,133字节；arm64模型+so共15,403,109字节（14.69 MiB）。
- Debug APK `zipalign -c -P 16 -v 4` 通过。ELF静态检查：arm64全部so PT_LOAD至少16KiB；armeabi-v7a的宿主MMKV为4KiB，其余至少16KiB。没有修改native源码；不等于16KiB设备运行验收。

## 限制与证据

首次构建因Windows旧资源目录文件占用失败，直接重试成功；见build-first-attempt.log、build.log。本轮未测真人持续性能、完整相机UI矩阵、直播厂商/远端和16KiB设备运行。对应用例保持历史通过未复测或未执行，未扩大本轮结论。

证据：[构建](evidence/rfb-removal-20261027/build.log)、[设备](evidence/rfb-removal-20261027/device.txt)、[依赖](evidence/rfb-removal-20261027/dependencies.log)、[JVM汇总](evidence/rfb-removal-20261027/jvm-summary.json)、[APK/ELF](evidence/rfb-removal-20261027/package.json)、[打包对齐](evidence/rfb-removal-20261027/zipalign-debug.log)。

---

以下为历史验证记录，不作为本轮实测结果。

# 2026-10-27：V4 图片睫毛资源链路

新增透明PNG资源、9点根部编辑、三款自有示例、主题/单包导出、Android内置/本地/网络资源消费以及iOS Metal源码。Android复用贴妆FBO，换图上传纹理，逐帧预分配网格；首次小脸测试发现细线欠采样，增加mipmap后保持原断言重测通过。

- 核心JVM 85项、GPUPixel JVM 4项通过；包含6组全量跨平台顶点向量。
- Pixel 8 / Android16 API36：13项设备测试通过（5.329s），包含三款PNG实绘、alpha/无脸旁路、错误资源拒绝与原有GPU回归。原设置恢复后文件列表及SHA256一致，未清数据或卸载。
- GPUPixel Release AAR、宿主Debug与测试APK构建通过；完整构建阶段lintDebug通过，最后仅缩小采样修复后重建并跑13项设备回归。
- Web 18项Node测试通过；实际浏览器验证款式切换、配置有效、ZIP下载及预览截图。另验证390×844小屏无横向溢出、内嵌应用到主题与完整主题导出（回读beauty.zip为V4）；真实人脸效果尚未验收。
- iOS新增解析、Metal绘制和XCTest源码，Windows无Swift/Xcode，未编译运行；网页尚未发布。

[资源制作说明](../../resources/effects/v4/README.md) · [结果](evidence/eye-resource-20261027/results.json) · [设备输出](evidence/eye-resource-20261027/device-gpupixel.txt) · [设置恢复](evidence/eye-resource-20261027/settings-restoration.json) · [Web截图](evidence/eye-resource-20261027/web-eye-editor.png)

人工眨眼/侧脸/遮挡、持续温升与直播引擎最终推流不在本次通过范围。三款素材为链路示例，不能据此宣称达到商业美妆质量。历史记录保留在下方。

---

# 2026-10-27：睫毛自然度与按需开销优化

- 以眼轴法向距离判断开合，修正水平眼睑漂移被误判成睁眼；平滑开合响应。按局部眼睑法线展开睫毛，长度分布改为眼头短/中段长，保留像素宽度下限。
- 单开睫毛跳过嘴唇与脸颊网格；关闭睫毛时JNI/VBO只传288浮点原贴妆网格，避免1440全量上传。没有新增模型、GPU等待、全尺寸FBO或逐帧Bitmap。
- 本次通过：新增几何JVM 3项（非方形旋转/镜像、闭眼水平漂移、单眼/无人脸复用清理）；GPUPixel Release AAR、app Debug及测试APK构建。设备7项已启动，1项通过，第二项执行期间Pixel 8离线，其余6项受阻，不能沿用上一轮7项通过作为当前源码验收。
- Pixel 8已安装本轮APK，但完整真机自然度与GPU回归未完成。未操作其他设备。没有清数据或卸载；设置恢复确认因离线受阻。原始备份留在本机`build/functional-test/eyelashes-refine-20261027/device-settings-backup.tar`，重连后应先force-stop，再用`adb exec-in run-as com.beautysdk.demo tar -xf -`恢复并核对文件摘要；二进制恢复不要通过PowerShell文本管道。
- 未验证：真人快速眨眼/侧脸/遮挡、持续性能、16KB设备；Web/iOS/外部睫毛资源协议未扩展。以上是可测试的几何改进，不声称达到商业美妆自然度。

构建命令：`gradlew.bat :core:lib_beauty_gpupixel:testDebugUnitTest :core:lib_beauty_gpupixel:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m`。证据：[结果](evidence/eyelashes-refine-20261027/results.json)、[设备中断日志](evidence/eyelashes-refine-20261027/device-gpupixel.txt)、[源码指纹](evidence/eyelashes-refine-20261027/source-fingerprint.json)。基线只记录实际通过的3项几何及1项设备用例。

---

# 2026-10-27：内置睫毛与贴妆质量增量

新增公共`BeautyAdjustments.eyelashes`（0..1），人脸分析按需启用；GPUPixel C++/JNI复用贴妆FBO/VBO，每眼8根三段程序化上睫毛绑定眼睑，眉部确定朝外方向，开合眼近似联动，两眼独立旁路。Demo底部美妆新增「睫毛」，强度0..100、重置0；默认/英文/中文数组均13项。此前口红明度保持、腮红柔光与分侧收缩、美型平滑边界修改一并保留。

- 本次通过：核心JVM 85 项；Pixel 8 GPU/入口7项（4.739s）；Debug宿主/测试APK、GPUPixel Release AAR构建成功。三语言XML和参数数组长度校验通过。
- 实际公开人像检测与睫毛像素验证、缺脸/缺点旁路、单眼缺点、闭眼坐标、原有2D/OES/旋转/alpha/状态/组合测试通过。合成人像测试不能替代真人眨眼自然度验收。
- 首轮7项中1项失败：小脸的亚像素睫毛未覆盖采样中心。增加像素级最小细带宽度后，完整7项重新通过；没有降低断言或跳过用例。
- 设备保留既有设置，备份/恢复文件SHA256匹配；无清数据或卸载，未保存相机画面。公开人像测试产生的临时对照已清理。
- 改动不依赖CameraX或直播厂商，公共纹理链路可调用；实际厂商与远端未测试。没有新增模型/全尺寸FBO/逐帧Bitmap，但新增局部draw和固定顶点上传，不宣称零成本。
- 未执行：真人动态/侧脸自然度、遮挡、持续性能/温升、16KB设备运行；Web/iOS睫毛和外部素材资源协议未实现。本轮只提供内置程序化款，不能当作完整商业美妆方案。

验证命令：`gradlew.bat :core:lib_beauty_gpupixel:assembleRelease :core:lib_beauty:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m`；设备runner选择`GpuPixelDeviceTest,GpuPixelControlsDeviceTest`。证据：[results](evidence/eyelashes-20261027/results.json)、[设备日志](evidence/eyelashes-20261027/device-gpupixel.txt)、[设置恢复](evidence/eyelashes-20261027/settings-restoration.json)。源码指纹`ca9359588e7830c2f6b63598e4c3ce3c0e2379313dd4398e32ea81cc58236942`，只推进实测用例的增量基线；下方是历史范围，不代表本轮全量回归。

---

# 2026-10-27：统一资源工作台、资源中心和七类效果面板

本轮按“全部优化”落地公共 SDK 与两个消费/制作界面；未接入实际直播厂商，也未发布 Cloudflare。

- Web 首页变成统一主题工作台：左侧资源清单、中央组合画布、右侧内嵌参数。独立贴纸编辑保留到 stickers.html，离线版继续只含六模板。背景、运镜、美颜在同一工作台调整并整体导出；嵌入时保留错误反馈与关键帧选择，避免把必要控件一起隐藏。
- Android 正常入口改为资源中心；内置单类/完整主题、本地 ZIP、HTTPS 目录下载。高级贴纸布局和导出显式展开。单一 BottomSheet 切换美颜/美型/美妆/调色/贴纸/背景/运镜，保留 ScrollView 单独处理滑动。
- BeautyResourceStore 支持严格目录、限量读取、SHA-256 校验、完整解析后原子缓存、离线目录/包复用与可注入宿主 HTTP 传输；下载失败不切换当前资源。目录生成产物含 10 项，逐项核验字节数和摘要。核心库不添加联网权限，Demo 显式资源下载声明 INTERNET。
- 新 V3 BEAUTY 完整保存口红、腮红、瘦鼻、下巴和嘴型；V1/V2 保持兼容。新版本只允许 BEAUTY，V2 不接受新字段。Android 与 Web 有跨端往返夹具，iOS 解析同步；Metal 对尚不支持的美型/美妆明确拒绝。
- BeautyEnginePipeline.performance 提供共用 CPU 提交/窗口 FPS 统计，排除 redraw；GPU 时间为未知。预览透明浮层每 250ms 更新，明确不是推流 FPS 或 GPU 完成时间。无新增逐帧 Bitmap、模型或 GPU 等待。
- 明确资源预设与手动模式；选择新贴纸可退出旧主题，后台恢复贴纸草稿不会误清已恢复主题；异步目录/贴纸事件受代次检查保护。

## 本轮验证

| 检查 | 实测结果 |
| --- | --- |
| JVM | core 85 + feature 12，共 97 项通过，0 失败 |
| Web | 15 项通过；build、离线版生成、JS 语法、diff 检查通过 |
| 首轮 Pixel 8 全量 | 57 项：55 通过、2 失败，67.634s；隐藏 Spinner 回调不触发导致旧值，已修复 |
| 修复后 UI | 7/7 通过，14.819s；真实分类按钮及可见场景控件操作 |
| 最终资源/面板专项 | 11/11 通过，13.659s；含内置完整主题、V3、断网缓存/坏摘要/验证失败、主题恢复、GPUPixel、分类及滑动 |
| 1.3 倍字体 + 夜间模式 | 5/5 UI 通过，11.791s；原系统设置与 3 个偏好/草稿文件恢复，列表和 SHA-256 一致 |
| 构建 | Debug APK、测试 APK、核心 Release AAR、lint 通过；lint 0 errors / 130 warnings |
| Web 可视操作 | 桌面与 390×844 检查；组合示例、V3 美颜采用成功。最终构建在内置浏览器确认关键帧 2/3 可选且参数可见。未保存相机画面 |

[汇总](evidence/resource-studio-20261027/results.json) · [最终设备专项](evidence/resource-studio-20261027/device-final-resource.txt) · [大字体](evidence/resource-studio-20261027/device-large-font.txt) · [源码指纹](evidence/resource-studio-20261027/source-fingerprint.json) · [工作台截图](evidence/resource-studio-20261027/web-studio.png)。基线仅推进最终实测的 11 个设备用例与 97 个 JVM 用例；首轮其他用例不冒充最终源码全量重测。

## 尚未验证/发布

Pages 的 catalog.json 与新界面尚未上传，默认线上目录在发布前不可用；HTTP 下载使用可注入传输做确定性离线/坏包测试，未声称线上部署联调成功。iOS 无 Xcode 编译与 Apple 设备验收。真人快速运动、遮挡与持续性能/温升、实际 Zego/Agora、远端编码流、横屏 UI 矩阵及宿主 Release/R8 本轮未执行。缓存目录由宿主按业务存储预算管理。

---

# 2026-10-27：隐藏基础OpenGL，Demo默认GPUPixel

按用户最新要求隐藏美颜后端选择器及标签，相机Demo默认GPUPixel，完整美型/美妆参数直接可见；顶部功能入口保留。修改CameraTestActivity、activity_camera_test.xml及现有页面回归，AGENTS和模块README同步最新默认约定。SDK基础OpenGL实现仍作内部对照。

Pixel8参数页回归 **1/1通过，1.863秒**：默认后端为GPUPixel、选择器隐藏、扩展区可见、12个参数、入口/正负调节/重置与GPUPixel相机帧交付。Debug/测试APK构建通过，UI检查0 Warning、代理结构检查和diff通过。SDK/native未修改，既有53项设备/96项JVM与lint是历史通过，本轮未全量重跑；真人观感、持续性能及厂商远端未验收。仅Pixel8覆盖安装，原3个偏好/工坊文件列表及SHA256恢复一致，不清数据、不卸载、不保存相机图像。

[设备证据](evidence/hide-basic-20261027/device-gpupixel.txt) · [构建](evidence/hide-basic-20261027/build.log) · [恢复核验](evidence/hide-basic-20261027/settings-restoration.json) · [源码指纹](evidence/hide-basic-20261027/source-fingerprint.json)。仅推进本轮真实通过的1项基线。

---

# 2026-10-27：美型/美妆入口可发现性修复

原因是默认基础OpenGL会隐藏整个扩展参数区，用户必须先切GPUPixel再向下滚动。现在美颜面板顶部有常驻“美型与美妆 · 口红 / 腮红 / 瘦鼻”入口（英文Reshape & makeup）；点击自动切GPUPixel、选择口红并定位参数区，默认处理主线保持不变。修改CameraTestActivity、activity_camera_test.xml和三套strings资源，复用已有样式/滚动容器。

Pixel8新增入口闭环 **1/1通过**（1.799秒）：默认参数区隐藏→真实入口点击→后端GPUPixel/口红选中/12项参数可用→调节美型美妆→收到GPUPixel帧→重置。Debug及测试APK构建通过，UI/i18n各0 Warning，diff检查通过；SDK/native未修改，53项全量、96项JVM及lint是上一轮历史通过，本轮未重跑。未重新验收人工小屏/大字体视觉矩阵、真人动态/持续性能或厂商远端。原设置与草稿3文件列表/SHA256已恢复，不清数据、不卸载、不操作其他设备、不保存相机图像。完成后打开Pixel8相机的美颜面板供用户直接找到入口。

[设备日志](evidence/makeup-entry-20261027/device-gpupixel.txt) · [构建日志](evidence/makeup-entry-20261027/build.log) · [数据恢复](evidence/makeup-entry-20261027/settings-restoration.json) · [源码指纹](evidence/makeup-entry-20261027/source-fingerprint.json)。本轮仅推进实际通过的1项基线。

---

# 2026-10-27：补充更多美型＋口红腮红（已实现）

用户确认两类都要，继续完成 `slimNose`（瘦鼻0..1）、`chinLength`（下巴长短-1..1）、`mouthSize`（嘴部大小-1..1）。均默认0，后两项负值缩小/缩短、正值放大/拉长。它们是 **BeautySDK自定义二维扩展**，不是GPUPixel上游原生效果；与前轮完成的口红/腮红、原有磨皮/美白/调色/瘦脸/大眼可组合。

修改公共BeautyAdjustments及能力门控、GPU渲染参数/JNI/Shader、独立ExtendedReshapeGeometry、相机参数调节与中英文说明。新增区域按MediaPipe语义点、人脸朝下方向、实际帧宽高计算，鼻部、下巴、嘴部分别校验；鼻部退化只旁路鼻部。三项合并现有美型pass与FBO槽2，不增加draw或全尺寸纹理；复用56浮点几何和5浮点强度数组，不加模型、不做逐帧Bitmap/读回。仅开原瘦脸/大眼时跳过扩展区域计算。

| 本轮验证 | 结果 |
| --- | --- |
| GPUPixel＋参数页专项 | **7/7通过**，4.488秒；含三项扩展、正负方向及公开人像真实478点 |
| Pixel8完整instrumentation | **53/53通过**，0失败/跳过，71.247秒 |
| JVM | 核心84、feature12，共**96项通过**；XML证据随报告保留 |
| 构建 | GPUPixel双ABI Release AAR、app Debug、测试APK通过 |
| app lintDebug | 0 Error/0 Fatal、125 Warning |
| 结构/配置/UI/i18n及diff检查 | 通过；UI/i18n各0 Warning |
| SO/资源检查 | arm64-v8a、armeabi-v7a的PT_LOAD均16KiB对齐，内置贴妆资源与LICENSE/NOTICE已打包 |
| 用户数据 | 原偏好/工坊草稿3文件列表及SHA256恢复一致；未清数据/卸载，仅Pixel8覆盖安装，App留首页 |

新增设备用例在96×128生成纹理上，不仅断言输出变化，还断言瘦鼻及正负下巴/嘴型的采样位移方向；实际将纹理与关键点旋转90°后，逆旋转结果每通道误差≤2。覆盖alpha、边角保持、缺脸/稀疏/退化区域独立旁路及与磨皮/贴妆组合。公开portrait.jpg六列对照（原图/口红/腮红/瘦鼻/下巴/嘴部）已检查；仅存本机公开fixture，未保存相机帧。页面索引9/10/11、正负滑杆、中性重置和MP选择已测试；没有增加滚动容器，没有重新完成人工全字体/语言视觉矩阵。

**未验收**：真人快速动作、侧脸/遮挡自然度、长时性能/温升、实际直播引擎与远端、宿主Release/R8、16KiB设备运行。当前资源V2仍严格支持原七项，新增五项（口红/腮红/瘦鼻/下巴/嘴部）通过Android SDK API与Demo使用，Web工坊/主题/iOS资源制作消费尚未扩展；没有自定义色号或3D遮挡。

当前259文件源码指纹：`2ee3964dbd08c3efdfa06db96a97cf96cc462e925cd7b90d2844c7f123009a52`。53项设备通过基线及96项JVM记录已更新；其他历史条目保留原指纹，不冒充本次复测。

[逐项结果](evidence/gpupixel-shapes-20261027/results.json) · [完整设备日志](evidence/gpupixel-shapes-20261027/device-all.txt) · [专项日志](evidence/gpupixel-shapes-20261027/device-gpupixel.txt) · [最终构建/lint](evidence/gpupixel-shapes-20261027/extended-shapes-final-build.log) · [源码指纹](evidence/gpupixel-shapes-20261027/source-fingerprint.json) · [ELF/包检查](evidence/gpupixel-shapes-20261027/native-elf.json) · [数据恢复](evidence/gpupixel-shapes-20261027/settings-restoration.json)。初次native构建的缩进告警已修复，最终双ABI构建通过。

```powershell
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :core:lib_beauty_gpupixel:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
adb -s 39061FDJH00D3J shell am instrument -w -r -e class com.beautysdk.demo.GpuPixelDeviceTest,com.beautysdk.demo.GpuPixelControlsDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s 39061FDJH00D3J shell am instrument -w -r com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

---

# 2026-10-27：GPUPixel 局部口红、腮红实现与回归

已为公共 `BeautyAdjustments` 增加默认关闭的 `lipstick` / `blusher`（0..1），接入 GPUPixel C++/JNI 纹理渲染与相机 Demo 参数索引7/8、中英文提示和重置。贴妆单独触发人脸分析，`hasReshape` 与 `hasMakeup` 分离；无有效稠密关键点时旁路对应贴妆区域。默认 MediaPipe＋基础OpenGL测试主线不变。

口红复用固定上游贴图、规范脸UV与唇环拓扑，经MediaPipe478语义映射排除口腔内部；腮红复用上游贴图，按双眼方向/下巴/实际宽高绑定左右脸颊。顺序为美颜/调色→贴妆→贴纸→美型，保留输入alpha和2D/OES采样契约。复用原有五FBO及固定288浮点顶点缓冲，贴妆至多增加一次背景复制、两次局部绘制，不增加全分辨率FBO，不增加检测器、逐帧Bitmap或GPU读回。上游来源、SHA256和Apache-2.0声明已随库维护。

| 验证 | 本次结果 |
| --- | --- |
| Pixel8 GPUPixel专项 | 5/5通过，含公开人像真实478点、局部像素、嘴内留空、强度、缺点旁路、alpha、变换、GL状态、纹理复用、2D/OES |
| Pixel8完整instrumentation | **52/52通过**，0失败/跳过，70.110秒；包含新增2项GPU用例与口红/腮红页面控制回归 |
| JVM | 核心83、feature12，共**95项通过**；新增贴妆分析需求及原图旁路断言 |
| 构建 | GPUPixel Release AAR（arm64-v8a、armeabi-v7a）、app Debug、测试APK通过 |
| app lintDebug | 0 Error/0 Fatal、125 Warning；不是零警告 |
| 静态检查 | 代理结构/配置、UI、i18n及git diff --check通过 |
| 原生/包内容 | 双ABI本次SO全部PT_LOAD为16KiB对齐；AAR包含两张贴妆图与LICENSE/NOTICE；不等于16KiB设备运行通过 |
| 数据保护 | 仅Pixel8覆盖安装，无清数据/卸载；偏好与工坊草稿原3文件列表/SHA256已恢复一致，App留首页 |

公开portrait.jpg的实际渲染对照已检查：口红对准嘴唇且牙齿保持原色，腮红为局部柔和变化；对照图只保存在本机忽略目录，不含实时相机图像。UI回归复用现有弹框/参数选择器，未增加嵌套滚动容器；本轮没有重新做全语言/全部字体倍率的人工观感矩阵。

**范围边界**：当前只有默认红唇与粉色腮红，属于实验性2D贴妆，无自定义色号、3D透视或遮挡分割。V2美颜资源包仍严格使用原七项参数；Web工坊、主题资源和iOS本轮没有新增贴妆制作/消费，新增两项通过Android SDK API与Demo使用。真人快速动作/侧脸/遮挡/张嘴画质、持续直播性能/温升、实际Zego/声网与远端、宿主Release/R8、16KiB设备运行均未验收。

源码指纹覆盖258个Android源码/配置及GPUPixel来源导入文件：`662fcf34a114ef5426c77383df7ff61d74e7b9c690265c444fa10b44550bc9ac`。逐项通过基线已更新；其他历史结果未冒充本次重跑。

[逐项结果](evidence/gpupixel-makeup-20261027/results.json) · [完整设备日志](evidence/gpupixel-makeup-20261027/device-all.txt) · [专项日志](evidence/gpupixel-makeup-20261027/device-gpupixel.txt) · [最终构建与lint](evidence/gpupixel-makeup-20261027/makeup-final-build.log) · [最终测试APK构建](evidence/gpupixel-makeup-20261027/makeup-test-build.log) · [源码指纹](evidence/gpupixel-makeup-20261027/source-fingerprint.json) · [ELF与包检查](evidence/gpupixel-makeup-20261027/native-elf.json) · [设置恢复核验](evidence/gpupixel-makeup-20261027/settings-restoration.json)。Release AAR与JVM任务完成记录在makeup-validation.log，随后该轮测试源编译因错误调用copy失败，已修正并在最终构建和52项设备回归中通过。

```powershell
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :core:lib_beauty_gpupixel:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 39061FDJH00D3J shell am instrument -w -r com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

---

# 2026-10-27：修复 MediaPipe RGB 行对齐（Pixel 8 回归通过）

PX8-RGB-ALIGN-01 已修复：项目实际使用的 MediaPipe 0.10.32 PacketCreator 对 RGB 按 `(width*3+3)/4*4` 计算行步长，并要求 buffer.capacity 等于步长×高度。旧后端按 width×height×3 紧凑分配，导致部分宽度在 RGB 与 RGBA 两个入口均失败。

修复仅位于 core/lib_beauty_mediapipe：新增内部 MediaPipeRgbInput，MediaPipeFaceAnalyzer 两条入口共用对齐缓冲；RGBA Direct 路径由 C++ 一次完成缩图、旋转、镜像、去alpha并直接写入对齐行，没有紧凑中间图或新增逐帧Bitmap；静态RGB使用逐行批量复制。堆RGBA仍走公共参考转换后批量打包，不为JNI复制整张源帧。行尾padding清零；同容量复用；释放随检测器关闭。公共 ReusableRgbInput/RgbPixelWriter 仍输出紧凑RGB，没有改引擎输入输出尺寸、关键点坐标、公共API、模型、依赖或跟随算法。

本次通过：Pixel 8 Android16/API36 全部50项设备测试，0失败/0跳过，67.449秒。上轮3项失败全部消除；新增逐像素测试覆盖Direct/堆RGBA、源行padding与非零position、全部四方向/镜像、窄边及尺寸切换、padding清零、缓冲复用与RGB/原生结果一致；CPU/GPU各自检测239×299公开人像，RGB/RGBA均输出478点，鼻锚点位置一致于测试容差。原生紧凑写入器、静态素材、MediaPipe/RFB、纹理桥、GPUPixel、背景/运镜、主题Demo、相机/工坊/记忆/页面回归均通过。

核心83项、feature11项JVM显式重跑，共94项通过（RFB与相机JVM本轮未重跑，上一轮17项历史结果保留）；MediaPipe Release AAR四ABI、app Debug、测试APK构建通过。app lintDebug为0 Error/0 Fatal/125 Warning。结构/配置校验和git diff --check通过。修改的libbeauty_rgb_input.so四ABI PT_LOAD均至少16KiB对齐；不等于16KiB设备运行通过。现有JNI保留规则继续覆盖原生入口，不需增加keep规则。

只对Pixel8覆盖安装，未清数据、卸载或操作其他设备；偏好与工坊草稿已恢复，原3个文件列表与SHA256完全一致，App留在首页。没有保存实时相机图像。本轮不宣称真人跟随观感、分割边缘、持续直播性能/温升或实际Zego/声网远端已验收；未运行16KiB设备或宿主Release/R8测试。性能微基准日志保留，但不能用本轮短测宣称提速或推流帧率。

测试计划和50项逐用例基线已更新，242个Android源码/配置文件SHA256总指纹用于未提交工作区复核。历史失败报告保留在下方，当前结论以上述50项结果为准。

[结果与逐项状态](evidence/rgb-align-fix-20261027/results.json) · [设备日志](evidence/rgb-align-fix-20261027/device-all.txt) · [构建日志](evidence/rgb-align-fix-20261027/build.log) · [源码指纹](evidence/rgb-align-fix-20261027/source-fingerprint.json) · [ELF检查](evidence/rgb-align-fix-20261027/native-elf.json)。

命令：

```powershell
.\gradlew.bat :core:lib_beauty:testDebugUnitTest --rerun :feature:feature_app:testDebugUnitTest --rerun :core:lib_beauty_mediapipe:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 39061FDJH00D3J shell am instrument -w -r com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

---

# 2026-10-27：Pixel 8 全功能回归（未全通过）

用户要求 px8 全功能测试，执行全部现有设备用例并补充主题 Demo 闭环、首页入口、MediaPipe 非对齐尺寸回归。设备为 Pixel 8、Android16/API36，安装当前工作区 Debug 0.1.0（versionCode1000）。未修改业务实现，仅补充测试与修正两个测试前提。其他手机未操作。

## 本次结果

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 最终完整 instrumentation | 48项：45通过、3失败、0跳过，64.817秒；3项对应同一行对齐缺陷 | [完整设备日志](evidence/px8-full-20261027/device-complete.txt) |
| JVM 显式重新执行 | 核心83、RFB4、相机13、feature11，共111通过，0失败/错误/跳过 | [重跑日志](evidence/px8-full-20261027/jvm-rerun.log) |
| app Debug及测试APK构建 | 通过；新增测试后测试APK再次构建通过 | [构建日志](evidence/px8-full-20261027/build.log) |
| 最终 app lintDebug | 0 Error、0 Fatal、125 Warning；不是零警告 | [lint XML](evidence/px8-full-20261027/lint-results-debug.xml) |
| 代理结构/配置检查 | 通过，不代表功能验收 | 本轮命令结果 |
| 原设置/草稿恢复 | 强停后恢复，原3个文件列表与SHA256完全一致 | [恢复核验](evidence/px8-full-20261027/settings-restoration.json) |

最终45项通过覆盖：MediaPipe真实公开人像478点与CPU/GPU、RFB真实框、原生RGBA转换/运动匹配、基础GL与GPUPixel调色/美型、2D/OES和GL状态、统一引擎纹理Connector夹具、分辨率变化、真实人像分割与背景像素/焦点/运镜、前后摄/镜像/启停重启、相机合成帧、参数/重置/弹框滚动、贴纸选择记忆、工坊编辑导出导入及草稿、V1/V2/主题加载与能力/坏包校验、首页三个入口。

新增 ThemeDemoDeviceTest 在真实 Demo 中加载完整主题→收到GPUPixel合成相机帧→美颜总开关关闭但保留贴纸→真正退出重进恢复→坏包保留旧主题→点击界面清除入口→重进不再恢复；原效果资源目录通过备份重命名在 finally 还原。HomeNavigationDeviceTest验证相机、工坊、诊断三个入口的实际页面状态，截图只保存首页，没有保存相机图像。[首页截图](evidence/px8-full-20261027/home.png)。

## 确认缺陷 PX8-RGB-ALIGN-01（待修复，P1）

MediaPipeFaceAnalyzer.analyze 与 analyzeRgba 最终都把紧凑 RGB 缓冲直接交给 ByteBufferImageBuilder。宽度非4对齐时，MediaPipe底层所需RGB行容量与当前紧凑分配不符。两条公开分析入口均能稳定复现，不能视作旧图片页独有问题。

- 静态公开人像缩放后的缓冲：期望630784字节，实际629760，BeautyDeviceTest.demoLoadsAllSixPngAssetsAndCompositesActualPortrait失败；单独复测同样失败。
- 生成的318×240 RGB：每行954字节，当前总228960；底层期望229440（956×240），MediaPipeInputContractDeviceTest.compactRgbSupportsAlignedAndNonAlignedWidths失败。
- 同尺寸直接RGBA输入，经原生转换进入复用RGB后同样失败，MediaPipeInputContractDeviceTest.directRgbaSupportsAlignedAndNonAlignedWidths失败。
- 两个新增尺寸用例先跑320×240对照成功，再跑318×240失败。影响某些分辨率/旋转/缩图规格下的人脸分析，可能导致无关键点、贴纸隐藏和分析错误；常用对齐尺寸的相机冒烟通过不能覆盖该边界。

建议后续修复只调整MediaPipe内部RGB布局或受支持输入包装，保留原始帧尺寸、旋转、镜像及坐标契约，继续复用缓冲，不以Bitmap绕过；修复后重跑该3项与原生/坐标/纹理桥回归。本轮为测试任务，未直接修改业务逻辑。

## 过程与证据边界

首轮44项中42通过2失败：实际RGB缺陷，以及DemoLayout测试错误假定持久化贴纸初始关闭。后者改为先明确设置测试前提，最终通过。新增首页测试首轮误断言已被页面消费并移除的Intent extra，修正为检查实际入口选中状态，最终通过；业务实现未改。中间46项结果保留在证据中，最终以48项完整结果为准。最初Gradle的-D参数被PowerShell拆分，正确引用后构建通过；不计作产品故障。

检测微基准使用公开固定240×300人像DirectByteBuffer，ABBA各轮预热5帧、采样40帧。最终CPU总分析均值171.64/119.90ms、p95 197.52/137.02ms；GPU均值101.04/93.11ms、p95 109.50/106.53ms。完整初始化/推理/预处理数据见设备日志。该测试不是摄像头端到端或持续直播性能，不能折算为推流FPS或承诺热稳态。电池温度开始28.8°C、多轮测试结束30.2°C，结束Thermal Status=0，只是环境读数，非温升基准。

未执行：真人快速移动/遮挡/发丝边缘的视觉验收，长时间三组管线FPS/尾延迟/内存/温升性能基准，深色/字体放大/横屏UI矩阵，实际Zego/声网与远端编码画面，Release/16KB运行及iOS。本机已把App留在首页，测试安装未清数据/卸载；相机权限原已授予未改，未保存实时相机帧。

本轮更新测试计划、报告及逐用例基线。仅45项实际通过设备用例推进基线，3项失败不推进；工作区包含未提交源码，记录241文件SHA256及总指纹，不能仅以HEAD标识。[总结果](evidence/px8-full-20261027/results.json) · [源码指纹](evidence/px8-full-20261027/source-fingerprint.json)。用户资源备份仅保留本机临时目录，不进入Git证据。

执行命令（所有adb均指定Pixel 8）：

```powershell
.\gradlew.bat testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
.\gradlew.bat :core:lib_beauty:testDebugUnitTest --rerun :core:lib_beauty_rfb:testDebugUnitTest --rerun :core:lib_camera:testDebugUnitTest --rerun :feature:feature_app:testDebugUnitTest --rerun --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 39061FDJH00D3J install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 39061FDJH00D3J shell am instrument -w -r com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:lintDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m'
```

---

# 2026-10-27：完整主题资源制作与 SDK 整体加载

THEME-V1-01～06：独立 Web 工坊新增主题编辑器，组合贴纸 V1、背景/运镜/美颜 V2，支持分类启停、内嵌编辑、组合示意预览、IndexedDB 草稿恢复、整体导入/导出。bundleVersion=1 保留原子包原始内容。Android 新增 BeautyThemePack，完整校验与能力检查通过后一次替换 BeautySession.settings；缺失类别归零，坏包或能力不足保留旧配置。Demo 复用资源导入入口并持久化主题，主题优先于手动配置。Swift 加载器与 Metal 组合消费者、测试源码已补齐，未编译。

本次实测：Web 13 项测试通过，静态构建、离线单文件生成及模块语法检查通过；核心 JVM 83 项、feature JVM 11 项，0 失败/错误；核心 Release AAR、app Debug、测试 APK 构建与 app lintDebug 通过。Pixel 8（API36）6 项资源设备测试通过，0.214 秒，覆盖完整主题加载/往返、能力不足保持旧配置、切换仅运镜主题清除旧类别、总像素预算及非法包拒绝，并回归 V2 两项。覆盖安装保留数据，没有启动相机、保存人脸帧或操作其他设备。

Chrome 本地构建产物验证：四类示例组合、内嵌美颜改名与贴纸旋转后采用、刷新后恢复完整草稿、导出成功状态、桌面与390×844布局；控制台无 error。本次浏览器下载未捕获文件路径，Android 测试夹具由同一 Web 打包模块在 Node 中生成，不声称手机加载了本次浏览器下载文件。浏览器文件选择受扩展未开启“Allow access to file URLs”阻挡，未执行人工导入回读；底层主题往返由 Node 和 Android 验证。

边界：iOS 无 Xcode/Swift/Metal 环境，未编译/运行；Metal 消费者不支持非零瘦脸/大眼，明确拒绝，宿主需另接美型实现。Web 仅示意预览，不模拟真实分割/美颜。未执行真人画质、持续性能、直播厂商或远端验收；未发布到 Cloudflare。Demo 导入后重启/清除的完整人工 UI 矩阵待验收。结构与配置校验通过；未推进测试基线提交指纹。

[日志与结果](evidence/theme-v1-2026-10-27/results.json) · [主题协议与接入](../../resources/themes/v1/README.md)。Web 截图在独立项目 build/screenshots/theme-desktop.jpg 和 theme-mobile.jpg。

---

# 2026-10-27：工坊拆分与Cloudflare Pages准备

网页源码已从tools/sticker-workshop迁移到同级独立项目C:/work/AI/BeautyStickerWorkshop。构建/测试/模板/协议快照都位于独立项目，无Android或SDK相对路径依赖。新增Node零依赖开发服务、静态dist构建、Wrangler Pages配置、MIME/CSP头、404和部署文档。

独立项目npm test 4项通过；build通过；本地dist的首页、JS模块、模板ZIP、协议JSON均HTTP200且MIME正确。未重新构建Android（SDK代码未变），未操作设备。没有登录Cloudflare、创建远程仓库或实际部署；CF线上头策略和域名仍待真实部署验收。历史工坊证据保留原始路径指纹，迁移后源码在独立项目。

---

# 2026-10-27：跨平台静态贴纸资源制作闭环

RESOURCE-WORKSHOP-01：新增网页工坊（源码+内嵌6模板的离线单HTML）、跨端V1规范/Schema/几何夹具、iOS Swift ZIP加载/几何/Metal合成组件及XCTest。沿用Android严格V1字段，无新Android运行时依赖，不改人脸检测、跟随或帧管线。设计同事可模板起步、换透明PNG、拖动/缩放/旋转、编辑最多3层并导出ZIP；PNG不烘焙人脸。预览为示意人脸，不是实时检测。

本次通过：Node4项（6样例往返、非法输入、16组几何、离线脚本/模板）；Pixel8资源测试4项，0失败，0.337秒，包含浏览器实际下载的中文礼帽包、6网页导出包及旧加载器两项回归。仅安装测试APK，没有启动相机、清空数据或改用户工坊草稿，OPPO未操作。app Debug/测试APK构建37秒通过；追加真实浏览器夹具后测试APK重建47秒通过。core JVM任务UP-TO-DATE，属于历史通过未复测。

浏览器实测HTTP版本：默认模板、礼帽切换、名称/标识/角度编辑、真实ZIP下载、再次导入、追加透明PNG、上唇绑定；窄屏和1366×900桌面布局，无横向溢出（scrollWidth1351/innerWidth1366）。修复hidden控件被通用CSS覆盖的问题；后补键盘标签操作与旧ImageBitmap释放。截图见证据。Node首轮测试脚本多一个括号，修正后最终4项通过。

未执行/受阻：iOS无Swift/Xcode/Metal环境，Swift Package与4项XCTest为代码交付，未编译或运行，不代表iOS真机可用。离线单文件已语法/内嵌包验证，自动浏览器明确禁止file协议导航，未绕过策略；双击打开需人工验收。未运行全量lint、持续性能、真人佩戴、跨端颜色边缘对照、真实直播引擎或远端测试。动画/3D及完整iOS美颜/采集/检测SDK不在本次静态资源闭环范围。

[证据和SHA256](evidence/resource-workshop-20261027/results.json) · [工坊截图](evidence/resource-workshop-20261027/workshop.png) · [协议](../../resources/stickers/v1/README.md)。

---

# 2026-10-27：美颜与贴纸工坊弹框滚动冲突

CameraTestActivity.openSettings原先只展开BottomSheet，未禁用整块拖拽，父层行为可能与ScrollView内容手势竞争。现将behavior.isDraggable=false，并关闭面板向父层嵌套滚动，保留内容滚动、关闭按钮/返回/外部点击。未改美颜、分割或贴纸SDK管线。

SHEET-SCROLL-01：Pixel 8 Android16/API36最终3项设备用例通过（7.648s）。真实Window注入上下滑动，美颜长内容可滚动且弹框顶部/展开状态不变；空工坊内容容纳时scrollY=0且弹框不被拖动；另回归GPUPixel和场景参数/重置联动。首轮新增测试误将空工坊也视为必然溢出，修正测试后重跑通过，业务修复未改。没有修改素材或保存相机帧，OPPO未操作。

app Debug/测试APK构建通过（59s），测试修正重建36s；UI静态检查0 warnings。已将修复版安装Pixel 8。未执行全量lint/SDK算法和性能回归；小屏、字体放大、横屏人工矩阵及主观手感待验收。

证据：[源码指纹和结果](evidence/sheet-scroll-20261027.json)。

---

# 2026-10-26：虚拟背景与数字运镜

SCENE-EFFECTS-01：新增公共BeautyScene/BeautySceneRenderer与ForegroundMaskProvider，支持背景虚化、纯色、图片center-crop替换，以及周期推近、水平摇移、人脸居中。顺序为背景合成→原有基础GL/GPUPixel美颜和贴纸→最终运镜裁切，输出分辨率与旋转镜像元数据不变。默认关闭，没有改动贴纸跟随策略。

MediaPipeForegroundMasks使用本地固定SelfieSegmenter单通道人像置信度，249537字节；独立单在途worker、忙时丢弃、关闭/换源作废发布，250ms采样年龄及几何隔离。RGBA缩小复制到复用RGB，无逐帧Bitmap。仅纹理可通过GlForegroundMaskBridge复用双PBO小图读回，保留原始FrameInfo，非零拷贝；有RGBA支路时不重复读回。蒙版变更才上传R8，背景图片对象变更才上传，FBO复用。缺少有效mask时恢复原背景，不能作为隐私遮挡保证。

## 本次实测

- 核心80项、feature10项JVM通过，0失败/错误/跳过；覆盖mask过期/未来/几何、参数边界、跨方向运镜裁切与reset、背景与人脸检测独立需求，并回归已有核心逻辑。
- Pixel 8 Android16/API36最终12项设备测试全部通过（4.031s）：SceneDeviceTest4、TextureBridgeDeviceTest4、GpuPixelDeviceTest3、SceneControlsDeviceTest1。验证实际模型、公开固定人像前景及背景置信度、RGBA与纯纹理分割桥、2D/OES合成、全背景/全前景/缺失与未来mask、纯色/图片像素、模糊邻域、裁切、alpha、GL状态、原GPUPixel及页面参数/倍率/重置。生成纹理和公开portrait.jpg，不保存用户相机图像。最新版APK已安装Pixel 8，OPPO未操作。
- 首批12项中2项失败：测试错误假设mask固定256，实际返回输入尺寸，修正断言为原始128×96；UI设备息屏无相机帧，唤醒后确认无锁屏展示再重跑。5项场景用例重跑通过（3.492s）。补Activity销毁弹框兜底，避免已onStop后再打开弹框的Window泄漏；最终重建并12项全过。
- 核心/MediaPipe Release AAR、app Debug和测试APK构建通过。最终app构建/lint58秒，app lint0 errors、114 warnings。结构、配置校验通过；架构、UI、国际化静态检查0 warnings。没有更新native实现或引入新Gradle依赖。

## 实际限制

这是数字取景裁切，非物理运镜或3D场景生成。分割对头发、手指、快速移动及低光的边缘质量未真人验收，首次/过期mask旁路可能有切换；没有宣称不会闪烁。BLUR为25采样近似虚化，新增背景/运镜FBO和分割worker有GPU/内存/CPU成本；未测持续性能、温升或端到端速度。仅纹理同时开人脸与分割目前可能各有一次小图读回。小屏/字号/横竖屏人工视觉矩阵、压力生命周期、16KB运行、真实厂商与远端均未验收。静态检查及夹具不代表生产可用。

接入：[场景SDK说明](../SDK_SCENE_EFFECTS.md)。证据：[源码指纹与实测结果](evidence/scene-effects-20261026.json)。日志见证据。Demo：相机→美颜→背景/运镜/倍率；场景开关独立于磨皮美白开关，原图关闭所有分析，重置关闭场景。

---

# 2026-10-26：GPUPixel 调色与实验性美型

新增公共 BeautyAdjustments：红润、亮度、对比度、饱和度、锐化、瘦脸、大眼。调色合并单 pass，美型复用 MediaPipe 478 点与上游形变公式；顺序为基础美颜、调色、贴纸、美型，底图与贴纸一起形变。中性值跳过新增处理，无有效关键点跳过美型。基础 CPU/GL 明确拒绝非中性扩展参数，Demo 仅在 GPUPixel 下应用；默认 MediaPipe＋基础 OpenGL 不变。没有增加逐帧 Bitmap、读回或检测模型，也未改动已回退的跟随策略。

GPUPIXEL-EXTENDED-01：核心77项、feature9项 JVM 通过；Pixel 8 Android16/API36 共8个不同设备用例最终通过。设备首批8项中7通过，页面用例因测试从 Activity 查找已移入弹框的控件而失败；修正测试引用后单项重跑通过（1.863s）。覆盖7项效果真实改变生成纹理、alpha、缺脸/稀疏点旁路、组合效果、2D/OES 元数据与GL状态、公共Connector及页面参数/重置联动。不保存用户人脸图像。新版已安装 Pixel 8，未操作 OPPO。

核心和GPUPixel Release AAR、app Debug及测试APK构建通过；app lint 0 errors、110 warnings（不是零警告）。代理结构、配置、架构、UI与国际化静态检查通过，后三项0 warnings。详见源码指纹和日志记录。

红润是简单肤色权重调色，不是局部腮红/皮肤分割。瘦脸大眼是实验性2D映射，贴纸也可能拉伸；未验收真人侧脸、横竖屏视觉矩阵、生命周期压力、16KB运行、持续性能及热稳态。没有接入实际厂商SDK或验证远端画面，也不能据此宣称贴纸跟随已改善。

证据：[源码指纹与结果](evidence/gpupixel-extended-20261026.json)。Demo入口：相机 → 美颜 → GPUPixel → 扩展参数下拉框与强度滑杆；调节美型自动选择 MediaPipe。

---

# 2026-10-26：用户报告跟随退化，撤回快速运动效果改动

用户确认FAST-MOTION-01真人跟随比原来差。此前固定人像平移测试仅证明算法可匹配更大位移，不能证明真人视觉改善。现已撤回新增多尺度匹配及连续平滑增益，恢复原单尺度native块匹配、约35ms小幅平滑和大位移直接跟随。保留公共Pipeline可选FaceMotionEstimator、资源保存加载及已有异步边界。

本次75项核心JVM、Pixel8 Android16/API36 5项设备测试通过（1.054s），0失败；设备测试为固定人像原位移/空白拒绝及4项2D/OES统一管线回归。核心/MediaPipe Release AAR和app Debug/test APK构建通过（1m41s），配置校验通过。已安装回退版并重新打开相机页。未重跑app全量Lint/UI/16KB运行验收。

回退前完整聚合日志找到59个有脸窗口：CPU检测部分窗口平均推理87.3/73.6ms、平均排队36.5/32.1ms；18:05:04窗口ready=7、stale=6、noFace=11、tracked=0。说明检测年龄/排队以及跟踪失效确实存在，不能用延长旧结果有效期掩盖。并未做相同模式、相同动作的受控前后对比，不把退化完全归因于新增搜索。只保存时序聚合，不保存人脸帧。

真人回退后视觉效果：未验收，需后续实际运动观察。持续性能、CPU/GPU同条件比较、真实直播厂商与远端画面仍未验证；不能把本次回归测试通过称为跟随问题已解决。历史FAST-MOTION-01的两项效果改动已撤回，报告下方结果仅作为历史记录。

证据：[撤回结果与源码指纹](evidence/motion-rollback-20261026.json)。日志：build/motion-rollback-build.log、build/motion-rollback-device.log、build/motion-regression-window.log。

---

# 2026-10-26：公共快速跟随优化

FAST-MOTION-01：公共StickerPoseSmoother以归一化位移/缩放/角度连续改变平滑增益，避免阈值切换引发跳变，保留小幅35ms时间响应及大位移直接跟随。NativeFaceMotion直接匹配失败的点才使用半尺寸粗搜、原尺寸局部细化；固定有界栈缓冲，保留前后向/低纹理/相似变换一致性和空白拒绝。没有延长150ms结果期限或300ms检测参考期限，也未增加渲染线程等待。

BeautyEnginePipeline新增可选FaceMotionEstimator，核心不依赖相机或MediaPipe实现。自有分析由Pipeline启用跟踪；借用sharedAnalysis仍由宿主管理，拒绝重复配置。外部RGBA与仅纹理小图桥接均可复用该运行时；相机继续复用现有共享运行时。没有实现厂商类型、跨上下文同步或远端写回。

## 本次验证

- 核心76项JVM全部通过，0失败/错误/跳过。新增平滑跨阈值连续性和无相机类型的标准帧Pipeline跟踪；原有跨分辨率、角度环绕、过期/换源、在途检测/跟踪交接等测试继续通过。最终源码回归构建29秒。
- Pixel 8 Android16/API36：FaceMotionDeviceTest 2项及TextureBridgeDeviceTest 4项全部通过，1.84秒。公开固定人像通过真实MediaPipe生成关键点，验证灰度图3/2像素及超出旧搜索窗口的18/8像素平移、空白图拒绝；另验证2D/OES输入、统一Connector写回、过期与几何隔离和GL状态恢复。没有采集或保存用户人脸图像。
- 固定人像estimate()每组15次：小位移均值3.775ms/最大5.905ms，大位移均值5.367ms/最大9.808ms。这是本轮夹具的匹配调用耗时，包含Kotlin结果映射，不是完整帧延迟/纯native耗时，也不是持续性能或优化前后速度对比。扩大运动适应范围有额外计算成本。
- core Release AAR、app Debug/test APK构建通过（3m6s），MediaPipe Release AAR通过（23秒）；native Debug/Release四ABI编译。新版已安装Pixel 8。代理结构、配置和架构通过，架构0 warnings。未重跑全量app lint/UI、正式宿主Release/R8或16KB运行验收。

当前没有真人快速转头、运动模糊、遮挡进出、热稳态或实际直播引擎远端的验收结论。仍是2D相似变换跟踪，不提供侧脸透视/深度遮挡，不承诺完全消除滞后或闪烁。OPPO未操作。

证据：[源码指纹及结果](evidence/fast-motion-20261026.json)。日志：build/fast-motion-build.log、build/fast-motion-unit-final.log、build/fast-motion-device.log、build/fast-motion-native-release.log。

---

# 2026-10-26：工坊保存资源后重新加载

新增工坊“导出”分区的“保存并加载”按钮及中英文状态提示。有资源且空闲时可用；在IO线程冻结当前素材、通过AtomicFile写入完整ZIP，再调用SDK loadZip校验并解码，成功才替换预览并启用。错误保留当前效果并展示错误；清除可阻止在途加载重新启用。自动草稿复用同一原子写入方法，busy期间退出不再把旧快照写回覆盖新资源。设置不会进入逐帧视频链路。当前仍只保留一份私有草稿，多套永久资源通过导出ZIP保留。

STICKER-SAVE-LOAD-01 本次通过：Pixel 8 Android16/API36 3项设备测试，0失败，3.525秒。新增用例验证PNG保留、宽度/偏移落盘、重载产生新资源对象、新ViewModel再次恢复、保存期间flush不覆盖以及清除不被撤销；另回归内置贴纸三次进入恢复和工坊开关恢复。仅操作专用测试草稿，恢复用户原偏好、不保存相机图像、不清应用数据。

app Debug和测试APK构建通过（3m），已安装Pixel 8。UI及国际化静态检查均0 warnings，配置校验通过。未重跑全量JVM/lint、字号/小屏/横竖屏视觉矩阵、持续性能，也未注入断电/磁盘满故障；静态检查不代表视觉验收。OPPO未操作。

证据：[源码指纹与结果](evidence/sticker-save-load-20261026.json)。日志：build/sticker-save-load-build.log、build/sticker-save-load-device.log、build/sticker-save-load-ui.log。

---

# 2026-10-26：贴纸选择及启用状态持久化

相机测试页现在保存内置眼镜/胡子/帽子开关及款式，进入时在监听器注册前恢复；设置无变化不重复写入。工坊继续用私有AtomicFile保存素材，额外按草稿名记录开关，进入页面即异步恢复，无需重新打开工坊。清除会保存关闭状态并阻止在途恢复重新启用；旧草稿没有开关记录时保持关闭。仅宿主保存设置，未修改SDK处理链路、不保存相机图像。

STICKER-PERSISTENCE-01 本次通过：Pixel 8 Android16/API36 2项设备测试，0失败，3.293秒。分别验证新页面恢复全部内置款式和开关、清除后再次进入保持关闭，以及独立工坊草稿跨ViewModel恢复开/关和恢复期间清除。测试恢复原用户偏好并删除专用测试草稿，没有清空应用数据。

app Debug和测试APK构建通过（1m27s），已安装Pixel 8。配置校验通过。本轮未重跑全套JVM、lint、布局矩阵或持续跟随性能；未单独强杀进程/重启手机验收。页面布局、文案和核心SDK未改变。OPPO未操作。

证据：[源码指纹及结果](evidence/sticker-persistence-20261026.json)。日志：build/sticker-persistence-build.log、build/sticker-persistence-device.log。

---

# 2026-10-26：相机合并公共帧入口

相机Demo已从直接BeautySession改为BeautyEngineConnector<TextureFrame> → BeautyEnginePipeline(sharedAnalysis) → BeautySession；CameraX/OES适配与双画面显示仍由宿主负责。独立RGBA小图直接交给共享检测运行时，不新增GPU读回。新增onRedraw让调参沿用同一采样时间，不重复投递分析；借用运行时的需求、换源reset、最终释放仍由宿主统一管理，切后端不关闭模型。代码有中文注释，未引入厂商依赖。

## CAMERA-CONNECTOR-01 本次结果

- JVM：核心74项、feature_app 8项全部通过，0失败/错误/跳过。新增3项覆盖真实美白同帧重绘、错误重绘与新时间线隔离、不支持重绘旁路、共享检测跨启停/释放仍存活。
- Pixel 8 / Android 16 API36：TextureBridgeDeviceTest 4项 + CameraDeviceTest 2项全部通过，4.559秒。覆盖实际GPU输入输出像素、桥接隔离及状态恢复、实际CameraX前后摄启停与MediaPipe相机页分析；不保存相机图像。已安装本轮Debug及测试APK，未清除应用数据；OPPO未操作。
- 构建：核心Release AAR、核心/feature JVM、app Debug及测试APK、app lint通过，3m32s。app Lint 0 errors / 104 warnings，未作为警告清零。本轮不改布局，未重跑全套布局、静态图和资源工坊用例；保留历史基线。源码最后仅补充Pipeline中文契约注释，执行逻辑与实测版本相同。
- 代理结构、配置和架构校验通过，架构0 warnings。未执行正式签名/R8/16KB验收。

本次通过：82项JVM、6项设备断言；失败0，受阻0。没有新增相机纹理拷贝链路，但未进行持续真人跟随、热稳态、延迟百分位和跨上下文压力验收；不能据此保证闪烁消失或性能提升。声网/ZEGO具体适配器、远端推流和真实异步消费同步仍未实现。

证据：[源码指纹及验证摘要](evidence/camera-connector-20261026.json)。本机日志：build/sdk-camera-connector-final.log、build/sdk-camera-connector-device.log。命令为证据JSON中的Gradle任务（offline/no-daemon/max-workers=1）及Pixel 8 instrumentation指定上述两个测试类。

---

# 2026-10-26：统一引擎取帧→标准帧→处理→写回入口

已增加BeautyEngineFrameAdapter、BeautyEngineConnector及BeautyEnginePipeline。业务回调只调用onFrame，公共层依次read/process/write；Pipeline组合现有Session、AnalysisRuntime及可选纹理检测桥接，不重复算法。原图仍写回；读/处理失败、尚未写回时旁路一次；写回失败不再重试，避免重复送帧。生命周期/线程检查在接触引擎帧前执行，拒绝重入；resetSource重启入口及处理器时间线。新增及修改代码附中文注释。

## 本轮验证

- ENGINE-CONNECTOR-01：新增8项Kotlin入口测试及1项Java调用测试。包含真实CPU美白输出、原图交回、读/处理失败旁路、写回失败不重试、显式拒绝、换源/重复采样、启停与错误线程、重复关闭及重入。核心库全部71项通过，0 failures/errors/skipped。
- Pixel 8：TextureBridgeDeviceTest全部4项通过（新增1项统一GPU回调集成，0.172s）。生成纹理由connector读入、pipeline内部检测小图与共享Session处理，再write输出；检查美白后的实际像素、检测状态及换源重启。其他3项覆盖2D/OES桥接、采样矩阵、过期/几何/需求隔离与GL状态恢复。未采集摄像头或保存用户图像。
- 检测使用固定结果FaceAnalyzer夹具，不运行MediaPipe；不将设备测试时长当性能数据或声称真实引擎已接通。
- 最终构建：核心testDebugUnitTest、assembleRelease、lintDebug及app assembleDebug/assembleDebugAndroidTest通过（3m22s）。库级Lint 0 errors / 0 warnings；未重跑app级Lint、宿主Release签名/R8或16KB验收。既有工具/其他模块编译警告仍在。
- 最终APK/测试APK已安装Pixel 8；代理结构、配置、架构检查通过（架构0 warnings）。未引入依赖、native或直播厂商SDK，未修改相机Demo默认处理入口。

## 接入边界

BeautyEngineFrameAdapter仍由选定厂商薄封装实现真实帧读取/结果写回/原帧旁路；本轮没有声网或ZEGO的具体帧类型、注册接口或账号配置。Connector同步执行，不自动创建GL线程；变化线程的引擎需在入口前建立固定GL执行环境并保证输入有效期。异步消费输出须用明确租约及GPU同步，不能把write返回视为GPU完成。

尚未验证厂商集成/远端画面、可变线程调度、跨context同步、异步资源释放、MediaPipe联合持续性能、热稳态及真人跟随。公共回调集成通过不等于厂商即插即用。

证据：[本轮结果与源码哈希](evidence/engine-connector-20261026.json)。原始日志：build/sdk-engine-connector-final.log、build/sdk-engine-connector-device.log。使用示例见core/lib_beauty/README.md“统一引擎帧回调入口”。

---

# 2026-10-26：可选纹理检测小图桥接

新增GlTextureAnalysisBridge：原始2D/OES纹理由GPU按采样矩阵缩小，两个PBO/fence槽位在后续帧轮询，仅已完成、未过期且会话代次有效的小图才投递到已有BeautyAnalysisRuntime；BeautySession和MediaPipe接口复用。不创建Bitmap，不读回高清图，不主动以glFinish或非零timeout等待GPU。默认最长边320、采样间隔33,333,333ns、待取年龄100ms。驱动调用仍有成本，且结果需后续帧才能取回，不能宣称零拷贝/零延迟/效率提升。

现有相机Demo仍用独立RGBA检测小图，未启用新桥接器，未增加其运行时读回成本。YUV兼容工具不加入主线。公共能力不等于完成声网、ZEGO或其他厂商适配。

## 本轮验证

- ENGINE-TEXTURE-01：Pixel 8的TextureBridgeDeviceTest共3项通过（0.194s）。生成非对称2D图案和Surface画布OES，验证缩小像素、UV翻转矩阵、旋转/镜像元数据、真实采样时间、共享Session合成、重复时间跳过、限频、过期丢弃、转向重置、需求关闭隔离及GL状态恢复。不使用相机，不保存人脸或图像。
- 此设备测试使用固定输出的FaceAnalyzer夹具，不运行MediaPipe；仅证明桥接和公共Session对接正确，不是联合模型或实时性能测试。测试内glFinish用于确定GPU完成，生产桥接器无glFinish。
- 核心库62项JVM回归，0 failures/errors/skipped。核心Release AAR、库级lintDebug构建通过（1m41s）；库Lint 0 errors / 0 warnings。
- 宿主assembleDebug、assembleDebugAndroidTest通过（1m28s），最终APK/测试APK安装Pixel 8。未重跑app级Lint或其他设备用例，既有构建警告仍存在。
- 代理结构/配置及架构检查通过，架构0 warnings。无依赖/native/签名变更。

## 未验证

真实引擎纹理交回与远端画面；跨context生产/消费同步及context丢失；GPU繁忙时尾延迟、MediaPipe联合持续性能/温升、真人贴纸跟随。新桥接增加后续帧读回延迟，有独立RGBA小图时仍优先走原路径。关闭桥接器不关闭借用运行时，宿主停止视频源时需同时禁用/重置或释放分析。

证据：[本轮结果与源码哈希](evidence/texture-analysis-bridge-20261026.json)。原始日志：build/sdk-texture-bridge-core.log、build/sdk-texture-bridge-host.log、build/sdk-texture-bridge-device.log。使用示例见core/lib_beauty/README.md。

---

# 2026-10-26：公共引擎输入适配与2D输出租约

本轮不接厂商。新增Yuv420Frame/YuvPlane、Yuv420RgbaConverter和GlTextureOutputPool；不改变默认Session输出有效期，需显式copy获得租约。

- ENGINE-INPUT-01：6项新增JVM用例覆盖颜色矩阵/范围、NV21偏移与交错UV、padding/奇数尺寸、先缩小后转换、direct缓冲复用、非法容量及跨线程幂等归还。核心库全部62项，0 failures/errors/skipped。
- ENGINE-OUTPUT-01：Pixel 8单项GL增量回归通过（0.357s）；覆盖池满不等待、未归还禁止销毁、输入覆盖不破坏副本、归还复用，且保留原CPU/GL像素及宿主状态测试。设备仅执行这一项，不将历史12项重复计为本轮通过。
- 最终构建：核心testDebugUnitTest、assembleRelease、lintDebug，以及app assembleDebug/assembleDebugAndroidTest通过（1m59s）。库级Lint 0 errors / 0 warnings；未重跑app级Lint。Gradle仍有既有SDK工具版本与其他模块编译警告。
- 代理结构/配置通过；架构检查通过、0 warnings。未改依赖、native或发布签名。
- 最终APK和测试APK已安装Pixel 8，未操作另一台设备；不保存相机图像。

公共转换器是Kotlin小图兼容路径，不是libyuv或高清YUV处理管线。纹理池仅复制RGBA 2D纹理，额外一次GPU拷贝；Lease.close需在消费者实际GPU读取结束后调用，不因回调返回便可归还。跨共享context的生产/消费同步由宿主完成；不共享context禁止直接传ID。OES规范化、纹理检测图生成、context丢失恢复、真实厂商接入及远端画面、持续性能尚未验证，不能声称多引擎即插即用或效率提升。

证据：[本轮结果与源码哈希](evidence/engine-input-output-20261026.json)。本地原始日志：build/sdk-engine-final.log、build/sdk-engine-pool-device.log。接入示例见core/lib_beauty/README.md。

---

# 2026-10-26：公共SDK运行链路、素材和诊断优化

## 结论与范围

本轮按用户明确选择完成公共SDK优化，不接直播厂商。相机通过BeautySession借用BeautyAnalysisRuntime合成；未完成实际引擎接入、远端验证或持续性能前后对比，不能声称闪烁全部修复或帧率提升。

## 实际变更

- 公共BeautyAnalysisRuntime统一按需分析、限频、跟踪及maxSide；普通Session仅在人脸效果开启时分析，修复跟踪组合忽略analysisMaxSide。共享运行时支持采集/GL双回调，生命周期及所有权明确。
- BeautySession.redraw仅重绘最近采样帧，不伪造时间戳、不重复提交分析；相机Demo复用公共会话处理快照及渲染异常，不再另行直接合成。
- PNG CPU/GL统一双线性采样；GL Shader从强制texelFetch改为texture，原CPU/GL每通道差<=2的严格断言保留。
- 内置素材只加载当前选择，有界最新需求、不为滑杆堆积IO；最多缓存当前三层，逐个发布错误隔离。SDK解码独占像素数组省一次完整复制，公开构造仍防御复制。
- 工坊清除动作使在途导入不能重新启用贴纸；私有原子ZIP草稿600ms防抖、退后台立即安排写入，恢复默认关闭效果。ZIP保持批量写入，不做整包内存再复制。设备夹具使用独立草稿，并在UI用例后恢复原草稿。
- 公共BeautyTimingWindow与BeautyFrameWindow提供固定容量P95/P99、状态/年龄、新帧与重绘计数。CPU提交不冒充GPU完成；不足两帧时FPS unavailable。诊断脚本只读固定SDK数值日志、PSS及电池温度，不保存相机图像。

## 本次通过

- JVM：core 56项、feature 8项，共64项，0 failures/errors/skipped。新增6项公共运行时/诊断回归。
- 完整构建：testDebugUnitTest（两个模块）、:core:lib_beauty:assembleRelease、:app:assembleDebug、:app:assembleDebugAndroidTest、:app:lintDebug通过，4m10s；最后草稿批量IO调整后宿主三个任务复验通过，1m25s。
- Lint 0 errors / 103 warnings；UI/i18n/architecture 0 warnings，代理结构和配置通过。
- Pixel 8 Android 16：最终APK安装成功，12项设备用例全部通过（13.63s）：资源包/工坊4项、CPU/GL对照及过期2项、相机启停/前后摄2项、分辨率1项、GPUPixel1项、Native运动1项、相机GPU delegate1项。
- 首轮像素对照曾1项失败（其余11项通过），定位为旧Shader强制最近邻采样；修正后最终整组通过，没有降低断言。
- 冷启动数值日志确认localConsumeFps=unavailable。诊断脚本语法/help/2秒空闲采集通过，0帧窗口如实标记无帧诊断，**不是性能基线**。

## 剩余验收

- 未执行同配置原图/美颜/美颜贴纸持续前后对比、P95性能收益、热稳态、真人遮挡/快转/进出画面完整视觉验收。
- 未验证全部横竖屏/小屏/字体缩放、系统SAF实际选文件、完整EGL丢失矩阵；构建和夹具不代表上述通过。
- 按用户要求本轮不接厂商；YUV/libyuv、异步编码纹理池及远端合成仍需实际适配，不宣称零拷贝或生产可交付。
- 本轮未改Native算法/依赖版本；未做新的宿主Release R8或16KB发布验收。旧CPU参考耗时不代表实时GPU主线。

## 证据

[源码哈希与结果](evidence/sdk-consolidation-20261026.json)、[公共链路说明](../SDK_PIPELINE.md)。工作区未提交，采用源码哈希；报告、计划、基线及证据未被ignore。本机日志见build/sdk-converge-final-metrics.log、build/sdk-converge-host-final.log、build/sdk-converge-device-verified.log。没有推进持续性能及厂商接入基线。

---

# 2026-10-26：工坊编辑器优化

- 工坊拆为素材/布局/导出三个工具分区；布局选择参数后只显示一个滑杆，保留六种变换、图层与锚点选项。使用现有工具按钮切换同一编辑器，不新增页面依赖。
- 直接进入工坊，不再二次展开，不自动载入样例/覆盖当前贴纸；用户主动加载样例或导入ZIP。未加载资源时禁用布局、导出。
- 弹框高度从可用窗口72%改为58%，保留更多相机预览；分区与参数选择由ViewModel保留至Activity重建。同一缩略图不重复绑定。
- UI/i18n检查均0警告；assembleDebug、assembleDebugAndroidTest、lintDebug通过（1m33s）。Lint 0 errors / 103 warnings。
- Pixel 8 最新APK安装成功；4项设备用例通过（5.677s）：工坊主动载入/分区显示/调参/导出导入/重建1项，既有相机预览/贴纸选择/图片布局3项。
- 未验证：系统文件选择器实际文件选择、新分区大字体/横屏/小屏视觉、手动贴合、持续性能。未改变检测/渲染算法，未截取保存相机画面。
- 证据：[workshop-layout-20261026.json](evidence/workshop-layout-20261026.json)；本机日志 build/workshop-layout-build.log、build/workshop-layout-device.log。

---

# 2026-10-26：首页入口精简

- 根据用户反馈，从首页移除图片实验室卡片及跳转绑定；保留旧静态参考代码与历史测试，不作为产品入口。
- 首页聚焦实时美颜、素材工坊；开发者工具改为紧凑次级入口，避免与体验功能争抢视觉层级。
- 本轮 UI 静态检查通过（0 warnings），:app:assembleDebug 通过；日志 build/home-simplify-build.log。
- 仅修改 activity_main.xml 与 MainActivity.kt；无 SDK 算法、依赖、权限变化。未复测设备操作、小屏、横屏、大字体，不推进历史设备通过基线。

---

# 2026-10-26：首页导航与相机 Demo 整理

- 首页改为浅色导航，突出实时美颜；新增工坊、图片实验室、开发者工具入口，并标明直播引擎适配未实现。
- 原首页图片测试完整迁至 ImageLabActivity；相机工坊独立分区，保留原图大画面/效果小窗，新增显式对比切换。首页/相机/图片页补充系统栏 Insets；相机弹框按窗口可用高度计算。
- UI、i18n 静态检查通过，均 0 警告。最终 assembleDebug、assembleDebugAndroidTest、lintDebug 通过（1m6s）；Lint 0 errors / 103 warnings，不表示零警告。
- Pixel 8 Android 16 最新 APK 安装成功；5 项设备回归通过（7.352s）：DemoLayoutDeviceTest 3 项、图片处理/帧自检 1 项、工坊编辑导出导入/重建 1 项。
- 首次最终 Lint 发现 API 27 导航栏属性误放通用 values，改用 WindowInsetsControllerCompat 后复验通过；没有降低 minSdk 或跳过 Lint。
- 本次未验收：首页逐入口完整视觉、小屏、横屏、大字体、系统文件选择器实际选择、真人贴合、持续性能与远端直播。不推进这些项目的通过基线。未改 SDK 检测/渲染算法。
- 证据：[ui-navigation-20261026.json](evidence/ui-navigation-20261026.json)；本机日志 build/ui-navigation-final-retry.log、build/ui-navigation-device-final.log。源码未提交，以文件哈希标识。

---

# 2026-10-26：标准静态资源包与相机素材工坊

## 本轮交付

- SDK `StickerResourcePack` / `StickerResourceManifest` / `StickerLayerSpec`：v1根目录ZIP、预览PNG及1～3层PNG；显式锚点、跨度、pivot、offset、rotation。CPU与GL共用布局转换，调参复用图片对象。
- 有界压缩/解压内容、条目数、图片尺寸和总像素校验，拒绝路径、重复项、缺失/多余PNG、未知字段/版本/锚点、非法参数、损坏PNG和APNG。
- 6个内置素材标准包、可修改模板与中文制作说明（`doc/stickers/README.md`）；生成脚本可复现ZIP。
- 相机贴纸页工坊：样例/SAF导入、图层选择、锚点/跨度/偏移/角度/pivot调整、重置及JSON/ZIP导出。IO/ViewModel与界面分离，导入失败保留旧资源，Activity重建保留状态。
- 相机内置素材改为独立IO线程按需预加载，不让GL等待ZIP读取；没有新增外部依赖版本、存储权限或网络能力。资源调参不会重置分析或重新上传同一图片。

## 本次通过

- 核心库50项、feature_app 8项，共58项JVM测试，0失败/错误/跳过。新增布局跨尺寸/方向/镜像、默认兼容、非法参数/不可变列表、自定义贴纸检测需求回归。
- `:core:lib_beauty:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`构建通过；同次执行两模块testDebugUnitTest。offline/no-daemon/max-workers=1，JVM1024MB，Kotlin in-process。最终构建3m23s。
- Lint任务成功，0 error，75 warnings（UnusedResources等项目级警告），不称为零警告。UI、i18n、架构脚本各通过且0警告。
- Pixel 8（Android16/API36，arm64）覆盖安装Success。6包往返/像素复用、非法包拒绝、工坊UI滑杆编辑/ContentResolver导出导入/Activity重建、GL固定输入对照及状态恢复、相机GPU检测+GL启动，共5项设备用例最终通过。
- 工坊闭环使用本轮专属cache文件并在finally删除，导入导出操作未保存人脸帧。ContentResolver用file URI验证实际读写；不把它描述为已经操作系统文件选择器选定/保存文件。

## 首次失败与修正

首次设备批次4通过、1失败：工坊测试打开BottomSheet后仍从Activity窗口查找已经移走的控件，空引用导致测试失败。修正测试为打开前取得panel引用并在panel内定位，不修改业务代码绕过问题。测试APK重建32s；该UI用例重跑OK（1 test，1.824s），包括真实Activity.recreate后的同一ViewModel与编辑状态验证。其余4项生产代码未变，保留本轮已通过结果。

## 未执行与边界

系统文件选择器实际选文件和保存位置、小屏幕/大字体完整视觉矩阵、真人贴合与持续闪烁/延迟/温升、第三方直播引擎与远端、最新Release宿主R8未验证。v1为静态PNG，非动画/3D/遮挡系统。工坊退出进程后不保证恢复，需导出保存。此次不包含公共Session与Demo全面统一或厂商适配。

日志：`build/sticker-workshop-final.log`、`build/sticker-workshop-test-fix.log`、`build/sticker-workshop-device.log`、`build/sticker-workshop-ui-retry.log`。可跟踪源码指纹与脱敏证据：[sticker-workshop-20261026.json](evidence/sticker-workshop-20261026.json)。

---

# 2026-10-26：短暂匹配失败保留与小幅姿态更新

- 问题：跟踪空结果覆盖成功位置，模型过期时短暂隐藏；小幅坐标/尺度/角度噪声未做时序处理。
- 实现：跟踪成功结果写入独立8条有界历史，失败不覆盖，不刷新采样时间，仍受150ms帧期限及300ms原模型参考期限约束。模型无脸、错误及连续段变更仍撤销旧结果。
- OpenGL姿态层：只处理三个挂件的中心/跨度/角度，小变化35ms时间常数；位移/尺度变化达到锚点跨度8%或角度变化达到0.10弧度时直接跟随。跨±π最短角路径、缺锚点/几何变化/时间回退/旁路清理。无图像复制、Bitmap或478点网格平滑。CPU静态参考保持原逻辑。
- 单元回归：核心库47项、feature_app 7项，共54项通过，0失败、0错误。新增6项覆盖短暂失败、过期、无脸/重置、小幅与快速移动、分辨率、渲染帧率、角度及清理。
- 构建：`:core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`，offline/no-daemon/max-workers=1，JVM1024MB、Kotlin in-process。BUILD SUCCESSFUL（1m11s）。
- Pixel 8（Android16/API36，arm64）：应用和测试APK覆盖安装Success；固定输入GL/CPU及状态恢复、相机GPU检测与GL渲染启动两项设备回归通过（OK 2 tests，2.035s）。相机冒烟不要求真人入镜，不代表贴纸视觉或持续性能通过。
- 未执行：真人持续静止/转头/遮挡闪烁验收、延迟/温升测量、真实直播引擎。平滑参数需真人效果验证，小幅运动有短暂平滑延迟；持续失效仍会隐藏。
- 日志：`build/sticker-stability-build.log`、`build/sticker-stability-device.log`；脱敏源码指纹与结果：`doc/testing/evidence/sticker-stability-20261026.json`。

---

# 2026-10-26：检测与跟踪参考交接修复

- 原因：模型参考更新使已完成或执行中的跟踪结果失效；可能从较新的跟踪帧回退到较早采样的检测位置。
- 修改：连续有效人脸检测共享交接标记，按采样时间选取较新结果；原始检测校正期限不刷新。无脸、错误、几何变化和reset隔离旧标记。元数据索引仅保存时间和标记，不保留灰度图历史。
- 回归：新增已完成/执行中两种交接测试，均验证过期、无脸和重新检出隔离。核心库41项单元测试通过（0失败、0错误）。`:app:assembleDebug`通过。
- 构建命令：Gradle offline/no-daemon/max-workers=1，JVM 1024MB，Kotlin in-process；执行`:core:lib_beauty:testDebugUnitTest :app:assembleDebug`。日志：`build/tracking-handoff-build.log`。
- Pixel 8 安装返回Success并启动MainActivity。本轮未执行设备自动化回归或真人视觉验收，不宣称所有闪烁已解决；匹配失败、模型坐标校正及关键点噪声仍需观察。
- 可跟踪证据：`doc/testing/evidence/tracking-handoff-20261026.json`。

---

# 本轮：实验性图像跟踪补齐检测空档（2026-10-26）

新增公共TrackedFaceAnalysis/FaceMotionEstimator并接入BeautySession可选参数；相机Demo默认勾选实验跟踪，CPU MediaPipe＋基础OpenGL不变。原生NativeFaceMotion采用最长边160灰度图、16点局部零均值匹配、前后向检验、一致性筛选和最小二乘相似变换。不是成熟光流或三维跟踪库，没有添加外部依赖。

跟踪与模型各自一个有界worker：模型最多最新候选，跟踪忙时丢帧。宿主回调只复制RGBA，不等待推理/匹配。当前跟踪灰度缓冲复用，模型参考使用独占小灰度快照；不创建Bitmap，不保存用户相机帧，但也不是零分配/零拷贝。BeautySession模型限频不限制轻量跟踪提交。

- 模型原始采样时间保留，最多300ms未校正时撤销跟踪；跟踪帧仍受150ms新鲜度约束。仅有新图像匹配成功才提供跟踪结果，不把旧检测时间改成当前时间。新无脸/错误、重置、校正过期不得被旧跟踪结果覆盖。
- FaceAnalysisSnapshot新增tracked/detectionTimestampNs，UI区分“图像跟踪更新”，日志增加tracked帧数、检测校正年龄与BeautyMotion全量窗口统计。
- 公共映射与跟踪兼容，先按真实源帧跟踪，再按AnalysisFrameMapping映射；实际厂商仍须提供同视场小图和时间线。

## 验证结果
- JVM46项通过（共享库39、feature7），0失败/错误。新增2项覆盖延迟模型、跟踪300ms边界、新无脸、reset和公共Session模型限频时继续跟踪；旧过期/内存/映射测试继续通过。
- app debug/androidTest、MediaPipe Release AAR构建通过；最终build/motion-verified-build.log，1m48s。新增C++两ABI编译通过，arm64-v8a/armeabi-v7a ELF LOAD均0x4000（16KB）。consumer JNI keep规则已添加；未跑新Release宿主R8。
- Pixel 8 native人像已知平移及空白遮挡拒绝通过。15次匹配平均3.548ms，最大5.782ms；仅estimate，不含模型推理、相机复制和完整端到端，不能推算直播FPS。已知转角/缩放/复杂遮挡完整矩阵尚未验证。
- 原有RGBA转换方向/镜像/stride设备测试通过。
- 相机+GPU检测+基础GL启动测试第一次30秒超时；检查设备mWakefulness=Dozing且无相机分析输出。唤醒、解锁后单项重跑通过（1.778s），未改业务逻辑掩盖失败。测试允许NO_FACE，不能当真人跟踪验收。
- UI静态检查0警告。已覆盖安装最新APK，并通过MainActivity相机按钮打开测试页。
- 日志：build/motion-final-device.log（含首次相机超时）、build/motion-camera-awake.log（重测通过）。脱敏证据：[frame-motion-20261026.json](evidence/frame-motion-20261026.json)。

## 仍需验证
持续真人闪烁是否减少、快速侧脸/遮挡/出入画面、跟踪误匹配、长期CPU/内存/温升、GPU争用、Release宿主R8和厂商远端均未完成。实验算法可以拒绝大运动，拒绝且检测过期时仍会隐藏；不宣称闪烁已经解决。用户可在调试页关闭图像匹配作同场景对照。

---

# 本轮：跨分辨率与裁剪坐标适配（2026-10-26）

新增公共AnalysisFrameMapping、FrameGeometry、FrameRegion和BeautySession.setAnalysisMapping。显式转换源/目标的归一化裁剪、留边、旋转和镜像；不同分辨率不再必须伪造检测帧尺寸。此模式独立提交分析帧，处理输出RGBA时不重复分析。输出像素本身仍由宿主提供，不自动裁剪或读回纹理。

- 映射变更一律保守失效旧结果、候选和在途代次；几何不符明确拒绝。结果保留原采样时间，150ms有效期不变。同一结果映射只计算一次，避免每渲染帧分配478点。裁剪外脸丢弃、裁剪外锚点移除、框限制在目标有效内容区。
- 旧同视场模式和相机Demo保持兼容；动态分辨率切换不是无缝跟踪，等待新结果时可以暂时隐藏贴纸。

## 本次通过
- :core:lib_beauty:testDebugUnitTest（37项）、:feature:feature_app:testDebugUnitTest（7项），共44项，0失败/错误/跳过。新增5项覆盖裁剪/留边、旋转镜像、裁剪外目标、公开session缓存/结果年龄/配置失效和非法参数。
- :app:assembleDebug，1m6s；:app:assembleDebugAndroidTest，35s。offline、单worker、in-process Kotlin；本机日志build/resolution-build.log和build/resolution-device-build.log。
- Pixel 8覆盖安装；ResolutionDeviceTest通过（0.089s），同一GL renderer连续640x480→1280x720→720x1280→1920x1080→640x480，验证FBO完整、返回尺寸、新右下角像素及GL无错误。使用纯色图，不采集或保存人脸。日志build/resolution-device.log。
- 证据与源码指纹：[resolution-mapping-20261026.json](evidence/resolution-mapping-20261026.json)。

## 未完成/未验证边界
本次没有实现Zego/声网实际适配、NV21/I420输入、高清纹理低分辨率读回、分辨率相关磨皮强度归一化。未执行真人跨尺寸完整视觉矩阵、长期性能、远端推流或新Release/R8；不能把纯色FBO测试等同真人贴纸视觉验收。宿主必须保证实际像素裁剪与声明一致；只看宽高比仍不能识别视场变化。

---

# 本轮：MediaPipe GPU 对照接入（2026-10-26）

已实现可选GPU delegate、同线程生命周期检查、相机调试开关（默认CPU）和逐次检测的窗口聚合。切换开关重置分析代次，旧结果失效。没有引入Bitmap实时转换、修改时间戳或延长150ms过期限制；未实现光流跟踪。

## 本次验证
- app debug及androidTest构建通过；最终宿主构建1m28s，补充设备冒烟测试构建25s。
- JVM39项通过：共享库32，feature7；新增窗口统计测试验证慢帧与缺失阶段数据。UI静态检查0警告。
- Pixel 8：固定人像CPU/GPU/GPU/CPU对照通过（27.395s）；各轮5次预热+40次全量计时，240x300直接RGBA，同模型。180次均单脸478点及上唇；最后CPU/GPU鼻点差<0.03。
- 总分析耗时p50/p95（ms）：CPU首轮185.26/214.82，GPU首轮108.98/124.16，GPU次轮100.84/111.03，CPU次轮103.38/139.06。GPU首次初始化2249ms，第二轮580ms。轮次波动大，不作GPU稳定提速倍数承诺。
- 相机+基础GL+GPU检测启动冒烟通过（1.695s）：程序操作GPU与眼镜复选框，等待READY或NO_FACE分析结果，无错误。无需用户出镜，不证明贴纸视觉跟随或持续性能达标。
- adb直接启动未导出CameraTestActivity被拒绝，改从MainActivity入口；连续相机刷新使uiautomator无法idle，最终通过instrumentation运行上述开关冒烟，没有修改exported属性。
- 本机日志：build/delegate-build.log、delegate-final-build.log、delegate-device.log、delegate-smoke-device.log。可迁移证据：[mediapipe-delegate-20261026.json](evidence/mediapipe-delegate-20261026.json)。未拍摄/保存用户相机帧。

## 剩余限制
GPU没有变成默认。固定人像基准不含采集等待、并行美颜或运动；相机冒烟只证明管线启动。持续真人CPU/GPU对照、完整GPU渲染耗时、热稳态、旋转/镜像全矩阵、横屏/大字体视觉、新Release/R8和直播厂商远端验证未执行。此前过期闪烁不能标记修复。BeautyAnalysisWindow记录所有完成事件的数量/mean/max，而旧BeautyAnalysis仍是每秒抽样，不能混用推算分布。

---

# 本轮：Pixel 8 用户复现日志分析（2026-10-26）

结果：仍有检测过期，未达到连续贴纸验收。仅抓取日志，未修改代码、未保存图像、未重新构建或运行测试；不推进通过基线。

- 实时抓取当前 Demo 进程 BeautyAnalysis/BeautyTracking/AndroidRuntime 日志，已停止抓取。原始日志：build/functional-test/pixel8-expiry-20261026/live.log；脱敏统计：[pixel8-expiry-20261026.json](evidence/pixel8-expiry-20261026.json)。
- 70个渲染统计窗口：READY356、STALE160、NO_FACE1382、PROCESSING0、ERROR0。整体过期160/1898=8.4%，含大量无人脸窗口，不适合作为贴纸持续跟随指标。
- 筛选READY>0且NO_FACE=0的13个窗口：READY286、STALE88，过期88/374=23.5%。这只是筛选窗口统计，不证明整个窗口持续存在人脸。
- 分析日志69条，其中16条faces=1。人脸样本中位数/最大值（ms）：复制0.126/0.219，候选等待26.644/53.300，准备1.654/3.991，推理65.856/195.846，分析70.197/202.401，单次完成间隔70.284/216.293。结果最大年龄299.500ms。
- 分析日志每秒采样一条，并非全量推理，不能推算真实分析FPS或完整P95/P99；没有相同场景旧版对照，不宣称最新候选调度改善或恶化。
- 原因：候选等待、推理波动以及等待下一结果共同消耗150ms年龄预算；部分推理自身已超预算。复制/预处理不是当前主要耗时。历史快照先判STALE再判NO_FACE，因此过期提示也会来自空结果。
- 后续需采集完整分阶段聚合统计（含入口延迟，先核验时钟域）并验证CPU/GPU对照；帧间跟踪仍未实现。固定超时隐藏策略本身无法保证当前推理波动下持续显示。未以增加超时或改写采样时间掩盖过期。

---

# 本轮：最新候选帧调度（2026-10-26）

- 修改公共 AsyncFaceAnalysis：新增 keepLatestPending，默认关闭；相机 GPU Demo 显式开启。最多一张执行、一张候选，候选覆盖更新，两个直接缓冲轮换；reset/close 清空候选。150ms 过期限制不变，没有实现光流或 GPU delegate。
- 本次通过：共享库32项、feature6项 JVM 测试（共38，零失败/错误/跳过），app debug 构建成功（3m39s）。新增 PENDING-01/02 覆盖候选覆盖、在途内存独立、源缓冲回收、reset/close 丢弃。旧忙时丢帧测试仍通过。
- 命令：gradlew.bat --offline --no-daemon --max-workers=1，in-process Kotlin，任务 :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug。日志 build/pending-frame-build.log。
- Pixel 8 覆盖安装成功。未执行本轮 instrumentation、持续实时性能、同场景前后对照、GPU/CPU对比、直播厂商集成与远端验证；不能据此认定闪烁解决。新增候选会增加忙时复制及候选等待，应以结果年龄/过期占比实测选择策略。
- 脱敏证据与源码指纹：[latest-pending-20261026.json](evidence/latest-pending-20261026.json)。本轮基线单独记录；下方为历史结果。

---

# 本轮：统一相机与直播引擎的SDK处理核心（2026-10-26）

相机Demo与公共BeautySession已共用AsyncFaceAnalysis的缓冲、worker、检测后端调用、历史结果和代次隔离。Demo不再自行创建/调用模型或使用PreviewDetectionHistory。渲染仍共用GlBeautyRenderer，CameraX只承担采集和显示适配。实际厂商回调及远端推流尚未接入。

## 修改与契约

- AsyncFaceAnalysis提交阶段仅批量复制借用RGBA到复用DirectByteBuffer，回调返回后宿主可立即回收/覆盖原始帧；worker通过analyzeRgba调用后端，缩放/转换/推理不在采集回调执行。忙时复制前丢帧，不增加待处理队列。
- 新增期望代次检查：停止/切源后，旧token提交被拒绝；reset期间正在使用的缓冲不允许提前覆盖，close等待worker顺序释放模型与缓冲引用。
- BeautySession显式allowScaledAnalysis支持同视场小分析图/高清纹理，不伪造FrameInfo尺寸。方向/镜像/不匹配宽高比禁止复用；不同crop仍须适配层统一或重置，不能仅靠宽高比判断同视场。
- 公共Session默认有效期由200ms统一到实时Demo原有150ms，旧CPU参考页显式500ms不变。没有延长阈值、添加预测或声称解决闪烁。
- 增加公共复制/排队/分析/结果更新间隔统计；后端可提供准备与推理阶段耗时。诊断回调在锁外调用，异常不覆盖已成功结果。
- FaceAnalyzer保留旧RGB及RGBA方法兼容，MediaPipe指定最长边接口复用原生转换缓冲；RFB使用通用转换回退。一次RGBA复制是异步内存安全边界，非零拷贝实现。

## 验证

- 命令：`:core:lib_beauty:testDebugUnitTest :core:lib_camera:testDebugUnitTest :feature:feature_app:testDebugUnitTest :core:lib_beauty:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest`。隔离Gradle home、单worker、in-process Kotlin，构建2分钟通过。
- JVM 49项通过（共享库30、相机13、feature6），0失败/错误/跳过。新增用例模拟宿主立即覆盖源缓冲、worker使用独占直接输入、忙时不覆盖/旧代次拒绝，以及同视场小图/高清纹理的公开Session处理。
- Pixel 8 Android16 API36 arm64-v8a：应用和测试包覆盖安装成功，6项instrumentation通过（7.465秒）：C++像素参考、真实人像模型、相机公共worker出帧及停止、前后摄重启、GL状态/像素、异步贴纸有效期。
- 代理结构与配置检查通过。Skill提及的validate_architecture.py未在本项目脚本目录提供，未虚构其执行；模块依赖未变。
- 本轮未重跑宿主release/R8；两库Release AAR已构建，native实现/入口/keep规则未改，10月23日的R8与16KB证据保留为历史。

## 未验证

- 实际ZEGO/声网SDK版本未选定，尚无厂商适配/编码前交回与远端画面验证；通用RGBA/纹理API及模拟回调生命周期通过，不能代替厂商集成。
- 当前未新增I420/NV21输入、仅纹理源的检测读回或复杂crop映射。
- 本轮未重新测量持续性能/温升/真人快速转头，不把功能通过表述为帧率提高或闪烁消除。公共管线原生前处理的上轮耗时不能直接冒充本轮结果。

接入与线程/内存契约见 [共享SDK README](../../core/lib_beauty/README.md)。证据：[unified-input-20261026.json](evidence/unified-input-20261026.json)。本机日志：`build/functional-test/unified-input-20261026/`。本轮仅更新UNIFIED用例基线，其他历史记录保留。

---

# 本轮：直接缓冲输入与 C++ 融合转换（2026-10-23）

实时 MediaPipe + BASIC_GL 主线不再经逐帧 Bitmap、RGBA 打包数组、RGB 中间数组及独立旋转副本。相机分析线程借用带裁剪/stride的 RGBA 缓冲，C++ 一次写入复用的 RGB DirectByteBuffer，MPImage 包装后同步推理；GL 合成独立运行。150ms有效期未修改。

## 实现和边界

- lib_camera 显式借用模式；默认独占副本契约兼容。借用输入在同步回调结束后失效并关闭 ImageProxy，禁止保存指针/缓冲。pixelStride非4保留兼容打包。
- lib_beauty 提供 analyzeRgba、ReusableRgbInput 和无图像的 AnalysisCoordinates；lib_beauty_mediapipe 使用 NativeRgbaConverter/beauty_rgb_input，Java/Kotlin不再逐像素转换实时直接缓冲。堆缓冲保留Kotlin参考回退，旧RGB接口仍兼容。
- Native 入口容量/stride/尺寸/旋转检查，无逐帧原生分配；新增 consumer JNI keep 与16KB链接设置。AAR包含四种ABI，宿主仍为arm64-v8a/armeabi-v7a。
- 仅素材解码、静态图片参考页/测试夹具可使用 Bitmap；CameraX 内部 YUV→RGBA 与 MediaPipe 内部复制仍存在，不能称全链路零拷贝。尚无libyuv、YUV输入适配或真实RTC厂商集成。

## 本次验证

- 47项JVM通过，0失败/错误/跳过：共享库28、相机13、feature6。覆盖旋转×镜像、缩放、padding/position、不修改源、缓冲复用及借用失效。
- Debug APK、测试APK、MediaPipe Release AAR构建通过（1m35s）；宿主unsigned release/R8通过（2m35s，显式 `-PrequireReleaseSigning=false`），JNI类名/原生入口保留。Release未安装，不代表发布签名或运行验收。
- Pixel 8 / Android16 API36 / arm64：5项设备测试通过（6.086s）：原生与参考逐字节一致及容量拒绝、真实人像直接RGBA/旧RGB交替输入、相机实际检测与GL出帧停止、前后摄重启及旧独占输入兼容、GL像素及状态恢复。
- 第一轮非原生设备检查中两项相机测试因屏幕Dozing无帧超时；唤醒并关闭非安全锁屏后4项通过。最终原生包5项全部通过，不把失败尝试隐藏为首次通过。
- 新增so所有LOAD段16KB对齐；debug与unsigned release APK均通过 `zipalign -c -P 16 4`。未在16KB页设备运行，不能据此保证所有上游依赖的运行兼容。
- 代理结构和配置检查通过。日志保留于 `build/functional-test/direct-buffer-20261023/`。

## 实时采样与剩余问题

Pixel 8新包在有人脸时取得56条每秒采样：准备耗时中位数1.41ms，范围0.92～8.42ms；推理中位数54.29ms。准备时间包含融合转换和MPImage包装；不含CameraX内部转换，也不是完整帧时延。此前Kotlin直接缓冲版观察到23～29ms准备耗时，但并非受控同场景基准，不计算加速比例。

连续有脸统计桶仍累计43帧STALE（READY 1541帧），所以输入开销下降不等于闪烁已彻底解决。未验收长期温升、快速转头视觉跟随、远端编码合成；后续瓶颈主要仍需核查模型推理/采集到结果年龄。没有提高超时边界。

可迁移脱敏证据：[direct-buffer-20261023.json](evidence/direct-buffer-20261023.json)。仅更新DBUF用例基线；原有用例保留各自历史记录。

---

# 本轮：贴纸闪烁的时间戳选择修复（2026-10-23）

发现明确缺陷：原先仅保存最新结果，若分析时间戳领先当前预览，就将结果判定为未来数据并清空贴纸，即使上一条结果仍在150ms有效期内也无法回退。可通过确定时间序列复现；真人画面仍可能因检测无脸或实际超时出现隐藏，本轮不声称已消除所有视觉闪烁。

## 修改

- 公共 lib_beauty 新增 FaceAnalysisHistory：最多8条元数据/人脸结果，选择不晚于视频帧的最新同几何结果；不保存图像、不增加推理队列、不预测或修改时间戳。
- AsyncFaceAnalysis 与相机 PreviewDetectionHistory 共用上述选择实现；相机附加会话、后端与配置代次隔离。停止/切镜头清空，跨方向和镜像禁止复用。
- 当前视频时间点的最新结果明确无脸/错误时隐藏贴纸，不能绕过该结果回退旧人脸；过期仍隐藏，预览150ms未改，CPU参考用例的独立有效期也未改。
- 修正 CameraDeviceTest：实际勾选眼镜/帽子，显式 MediaPipe+BASIC_GL，并等待模型分析诊断到达，避免只选款式时跳过模型还误判通过。

## 验证

- 本地：`:core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 通过，1m28s。JVM 32项，0失败/错误/跳过；新增6项共享历史测试、2项预览时间/代次测试。
- OPPO PCAM00 / Android11 API30 / arm64：覆盖安装应用和测试包成功，3项 instrumentation 通过（6.813秒），分别为实际开启贴纸的相机+MediaPipe冒烟、GL/CPU合成和宿主状态恢复、公开人像素材的异步贴纸有效期回归。
- CPU素材测试输出的93ms仅是单次CPU参考合成耗时，不代表实时OpenGL性能，不作为本轮加速结论。
- 配置检查：`validate_android_workflows.py --project-root . --config-only` 通过。
- 已重新打开OPPO相机页并开启圆框眼镜，当前状态为无脸；未保存相机图像。实际人脸贴合/快速运动闪烁未验收，未取得FPS/GPU耗时/长时间温升数据。

证据：[sticker-flicker-20261023.json](evidence/sticker-flicker-20261023.json)。本轮仅更新对应基线，历史用例保留原基线。手机序列号与人脸图像不进入报告。

---

# 本轮：效率优先优化（2026-10-23）

- 默认恢复基础 OpenGL；GPUPixel 仍可手动选择，两者算法与视觉不同，不能把默认切换解释为同画质加速。
- 没有有效贴纸或只显示原图时，跳过 SDK 分析像素复制及模型推理。分析代次随检测需求变化，晚到结果不用于新配置。CameraX 分析 UseCase 仍绑定，其内部 RGBA 转换不保证停止；模型保留到流停止，避免频繁重新初始化。
- 原图模式跳过美颜/贴纸 render；GL uniform 按程序缓存；常见 RGBA 使用整块/逐行 bulk get，保留 pixel stride 非 4 的路径及原 buffer position/limit。
- 未改动 MediaPipe 的 RGB/旋转/Bitmap 转换链，未引入预测、未减少输出分辨率或检测配置频率。活跃贴纸仍有转换和推理成本。

验证：`gradlew.bat :core:lib_camera:testDebugUnitTest :core:lib_beauty:testDebugUnitTest :feature:feature_app:testDebugUnitTest :app:assembleDebug`（隔离 Gradle user home、单 worker、in-process Kotlin）通过，1m42s。34 项 JVM 测试，0 失败/错误/跳过；其中相机10、共享美颜20、feature4。新增3项测试覆盖直接只读buffer、裁剪/stride/position/limit以及贴纸/原图门控。代理结构和配置检查通过。

第一次启动命令因 PowerShell 未引用 `-Pkotlin.compiler.execution.strategy=in-process` 参数而失败，引用后重跑上述任务全部成功。日志：`build/efficiency-local.log`；可迁移证据：[efficiency-local-20261023.json](evidence/efficiency-local-20261023.json)。本轮源码指纹及通过基线已单独记录，不覆盖旧用例的历史结果。

按用户要求未执行任何设备操作；GL 驱动行为、视觉、启停快速切换、实际 CPU/GPU 耗时、FPS、温升仍未验证。不能根据构建或调用次数承诺性能提升比例。

---

# 本轮：GPUPixel 美颜源码适配（2026-10-23，本地验证）

结论：新增独立 `core/lib_beauty_gpupixel`，复用固定上游提交的磨皮/美白 Shader、box blur/高通差分和四张 LUT，由 C++/JNI 在宿主当前 ES3 EGL 上下文处理 OES/2D 纹理；不是完整 GPUPixel 运行时依赖，不包含其独立 EGL、人脸检测、模型、瘦脸或美妆。Demo 相机页“美颜”弹框新增基础 OpenGL / GPUPixel 切换，默认 GPUPixel；MediaPipe/RFB 选择独立，处理后继续叠加原有眼镜、胡子和帽子。静态图片页仍为 CPU 参考。

## 实现及边界

- 上游锁定 `ef552bf8ce2d0d41fa9b979bfb5c7cf79374ca88`。原始参考源码、SHA256、Apache-2.0 和适配声明保留；许可证及 NOTICE 随 AAR/APK assets 分发。文本校验归一化 CRLF 至 LF，PNG 按原始字节校验。
- 视频主链路不做 glReadPixels/逐帧 Bitmap；LUT 初始化解码上传，五张全尺寸 FBO 纹理复用。标准磨皮美白五次 native 绘制，两个均值支路共享 blur，只有美白时两次；色调/贴纸及双画面显示另计。
- 输入采样矩阵在首阶段应用，输出 identity 2D，保留帧尺寸、时间戳、旋转/镜像元数据与 alpha；不重复磨皮美白。创建/渲染/正常关闭校验线程和 EGL，上下文丢失仅清 native 元数据，不跨 context 删除旧句柄。
- 初始化/渲染异常显式显示错误并旁路原帧，首个错误锁存，切换后端或重建上下文允许重试，不逐帧重复初始化或抛同一异常。
- 不创建另一套人脸检测，未修改150ms结果有效期；没有新增关键点预测、3D贴合或遮挡。新后端不代表已解决跟随滞后，也没有实际GPU耗时/FPS结论。

## 本次实测

| 范围 | 结果 | 证据及限制 |
|---|---|---|
| 源码/Shader/License | 通过 | `python scripts/verify_gpupixel_sources.py`；11文件、2段Shader及许可证匹配 |
| 新模块 Debug/Release AAR | 通过 | arm64-v8a、armeabi-v7a native 编译；`build/gpupixel-verified-local.log` 中对应任务成功，综合命令之后因守护进程停止而中断 |
| app Debug / Test APK | 通过 | `build/gpupixel-verified-isolated.log`，测试APK仅编译 |
| app 未签名 Release / R8 | 通过 | 显式 `-PrequireReleaseSigning=false`；三个 JNI 方法在 seeds 中保留；未签名产物不发布 |
| 既有 JVM 回归 | 35通过，0失败，0跳过 | lib_beauty 20、lib_camera 8、lib_beauty_rfb 4、feature_app 3 |
| app Lint | 0错误、87警告 | `app/build/reports/lint-results-debug.xml`；没有把警告当成零 |
| 新增 native 16KB ELF | 通过 | Debug/Release两个ABI，共4个ELF，LOAD段对齐至少16384，offset/vaddr同余 |
| Debug/Release APK zip 对齐 | 通过 | build-tools zipalign `-c -P 16 4`，两次退出码0 |
| 架构/UI/i18n/代理配置 | 通过 | 架构/UI/i18n均0警告，代理结构与配置检查通过；不代表视觉验收 |
| GPUPixel GPU/相机设备回归 | 未执行 | 用户排除真机测试；新增 GpuPixelDeviceTest 仅编译；没有查询设备、安装或运行 instrumentation |
| 持续性能 / 引擎远端 | 未执行 | 未集成 Zego/声网，不承诺速度、画质或跟随效果 |

综合验证：`:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :core:lib_beauty:testDebugUnitTest :core:lib_camera:testDebugUnitTest :core:lib_beauty_rfb:testDebugUnitTest :feature:feature_app:testDebugUnitTest`，附 `-PrequireReleaseSigning=false --no-daemon --max-workers=1`、1024MB堆/512MB metaspace/in-process Kotlin。前两次构建收到守护进程 stop 指令而中断；使用独立 Gradle user home（依赖缓存仍复用）重跑后，`build/gpupixel-verified-isolated.log` 为 **BUILD SUCCESSFUL in 6m 44s**。构建末尾有 metaspace 余量不足提示，但本轮完成；未修改项目全局内存设置。

可跟踪证据：`doc/testing/evidence/gpupixel-local-20261023.json`，包含源码逐文件哈希、测试数量、Lint、ELF段、产物大小与GPUPixel打包项；`TEST_BASELINE.json` 仅新增 `gpuPixelLocalBaseline`，不推进设备/性能基线。

## 未验收项

OES实际方向与裁剪、效果强度与上游LUT的视觉表现、低强度美白连续性、切换/后台/EGL丢失、快速转头、皮肤细节、GPU执行与端到端时延、持续内存/温升，以及真实直播引擎交回和远端结果均待后续设备验证。当前GL提交耗时不等于GPU执行时间；AAR文件大小不是APK安装包净增量，16KB静态检查不等于16KB设备运行验收。

---

# 本轮：美颜中文注释补充（2026-10-23）

已为共享美颜库、CPU/GL渲染、MediaPipe/RFB后端、贴纸几何/素材及Demo处理适配补充中文KDoc，说明方法用途、强度/时间单位、坐标空间、线程和资源所有权；保留既有完整注释。RFB细化接口说明纠正为“输入分析图坐标，再由检测器还原原始方向”。

- `:app:assembleDebug`通过，日志 `build/beauty-chinese-comments-build.log`，BUILD SUCCESSFUL（53秒）。
- 对20个范围内Kotlin文件执行词法校验：忽略注释与代码空白后，代码字符、普通字符串和Shader字符串全部保持原值。审计保存在 `build/beauty-comments-code-before.json` 与 `build/beauty-comments-audit.json`。
- 本轮仅注释变更，没有新写或重跑功能测试，不推进GPU/真机功能通过基线；按用户要求未操作真机。此前GPU验收限制继续有效。

---

# 本轮：GPU 相机管线与演示页（2026-10-23，本地验证）

结论：实时相机页实现已切换到 OES/ES3 GPU 合成；本地35项JVM测试失败0，Debug APK/Test APK构建成功，app Lint错误0、警告86。用户要求先分析处理、暂停真机测试；最终源码没有通过新的GPU相机设备验收，不宣称跟随延迟、帧率、视觉或生命周期已验证。

## 变更与原因

- 新增 feature_app/GpuBeautyPreview：CameraX Preview → SurfaceTexture/OES → GlBeautyRenderer复用FBO → 单EGL上下文双画面。GPU合成磨皮/美白/PNG，移除实时页CPU逐像素美颜、逐帧Bitmap显示及GPU读回；静态页与CameraBeautyController保留CPU参考。
- 独立低分辨率分析，CameraX KEEP_ONLY_LATEST，无额外检测积压；不等待检测返回。原8Hz渲染约束移除，分析请求上限30次/秒，实际吞吐未测。纯基础美颜方案跳过分析RGBA打包，CameraX内部转换仍可能发生。
- 共享UseCaseGroup/ViewPort，按照实际SurfaceRequest尺寸/crop/rotation及ST矩阵采样。用会话、后端、方向、镜像、比例和时间戳过滤锚点，150ms后隐藏；不再靠延长到500ms掩盖旧坐标拖尾。未引入未经验证的外推预测。
- GL资源创建、使用、释放绑定context；queueEvent时仍检查current context。旧SurfaceRequest拒绝、Surface完成回调后释放生产者、切摄失败保留旧画面；错误回退原纹理并正确报告未合成。
- 深色相机页、原图底层/效果左上、可交换主副画面；底部美颜/贴纸/调试三个面板，磨皮美白实时数值，六素材缩略图与选中状态；默认无贴纸。横屏小窗有独立尺寸资源，实际视觉/大字体仍未验收。

## 本次验证结果

| 范围 | 结果 | 证据/限制 |
|---|---|---|
| lib_beauty JVM | 20通过，0失败 | 结果状态/过期/异步调度、像素/贴纸基础回归 |
| lib_beauty_rfb JVM | 4通过，0失败 | 原模型解析/生命周期回归 |
| lib_camera JVM | 8通过，0失败 | 原RGBA打包 + ST/crop顺序、旋转镜像、输入不可变 |
| feature_app JVM | 3通过，0失败 | 150ms边界、未来/异会话/方案/几何拒绝、无脸与异常 |
| app Debug及Test APK | 通过 | 仅编译测试APK，不代表执行设备测试 |
| app Lint | 0错误、86警告 | build/reports的XML与HTML，未把警告当成零 |
| UI/i18n/代理配置静态 | 通过，0警告 | 不等同视觉检查 |
| 最终GPU真机回归 | 未执行 | 用户明确暂停，设备通过基线不推进 |
| GPU持续性能/引擎远端 | 未执行 | 未提供毫秒/FPS承诺 |

主验证命令（使用单worker/in-process Kotlin与1024MB堆）：`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :core:lib_beauty:testDebugUnitTest :core:lib_camera:testDebugUnitTest :core:lib_beauty_rfb:testDebugUnitTest :feature:feature_app:testDebugUnitTest`。最终日志 `build/gpu-camera-verified-local.log` 显示 BUILD SUCCESSFUL；具体源码指纹与逐文件SHA256见TEST_BASELINE.json的gpuCameraLocalBaseline，旧通过基线保留。

## 已发现问题与未验证项

- 早期页面构建曾因API27的windowLightNavigationBar资源产生Lint错误，移除不兼容项并改由Compat控制；早期GPU编译曾有递归类型推断/测试跨模块smartcast错误，最终构建已通过。
- 暂停前启动过早期GPU设备回归：Beauty6项及基础相机1项通过，GPU相机页在释放时因EGL context不匹配崩溃（1项失败，后续3项页面测试未执行），日志 `build/gpu-camera-device.log`。已代码修订context校验及销毁策略，但用户要求暂停后没有复测，不能把修改认定为实机修复验收通过。早期单CPU样本111ms不是GPU性能数据。
- 之前CPU/离屏GL/页面历史通过结果不能替代新版OES真实摄像头、前后摄/旋转镜像/裁剪、后台恢复、context重建、实际快速转头/遮挡及小屏/字体矩阵。
- Zego/声网版本及编码前纹理处理契约尚未选定，未接引擎、未验证远端；MediaPipe保持CPU VIDEO推理而非GPU推理。详见 [GPU管线说明](../GPU_CAMERA_PIPELINE.md)。

---

# 本轮：六张 PNG 素材实际接入（2026-10-23）

结论：本次 23 项 JVM（共享库19、RFB4）及7项真机 instrumentation 全部通过，失败0；Android 库及宿主构建通过。该结论针对本轮覆盖，不代表生产推流或持续性能合格。

## 变更与原因

- `lib_beauty/ImageSticker.kt`：不可变 ARGB 图片、最多三层素材包、可配置锚点/pivot/眼距嘴距比例；不绑定 Android Bitmap 或 Demo 素材文件。
- 共享 CPU/GL：同一旋转/镜像坐标规则、PNG 非预乘 source-over、缺脸/缺锚点隐藏；CPU 独占输出，GL 缓存三素材槽并释放，恢复宿主纹理单元/sampler/像素解包状态。
- `feature_app/DemoStickerCatalog.kt`：读取真实 manifest、按需解码并缓存六 PNG；两款眼镜、独立胡子和三款头饰选择。关闭控制器时清理 CPU 缓存。
- 两测试页增加中英文款式选择；静态图“应用”及相机下一帧均使用 PNG，而非仅展示目录。仍保留公共程序化 API 兼容原调用。
- 初次 GL 对比曾发现 source texel 边界的 CPU/GPU 浮点取整不同；统一最近邻边界规则后复测通过，未放宽逐通道误差≤2的断言。

## 本次通过证据

- JVM：ImageStickerTest3项涵盖四旋转×两镜像非对称图案、透明底两层alpha数值、缺锚点、输入/配置副本；共享原16项与RFB4项保持通过。
- Pixel8/API36/arm64：BeautyDeviceTest5项通过（5.104秒）；包括真实 Demo manifest 加载及公开人像3组预设覆盖六 PNG，和同一 GL 渲染器切换素材包/清除、像素一致性及宿主状态恢复。
- 同设备 CameraDeviceTest2项通过（4.638秒）：前后摄/启停/独立帧，以及 MediaPipe+爱心眼镜+猫耳配置下相机页输出与停止清空。未要求镜头中必须出现真实人脸，因此不以此宣称动态佩戴视觉效果验收。
- Release AAR：lib_beauty、lib_beauty_mediapipe、lib_beauty_rfb；Debug APK/Test APK；宿主 unsigned Release/R8（4分44秒）。Release使用 `-PrequireReleaseSigning=false`，未签名发布。
- lib_beauty lintDebug无问题；app lintDebug为0错误、63警告，主要为依赖更新与反射/ViewBinding相关未使用资源提示，未将警告视为零。
- UI/i18n/架构/代理配置检查通过。Debug与Release APK中六PNG逐字节等于源文件，manifest存在；共享AAR不带Demo素材。

日志与报告：

- `build/png-sticker-final-build.log`：三库Release、JVM、Lint、宿主Release/R8。
- `build/png-sticker-camera-build.log`：最终共享库JVM/Release及Debug/Test APK。
- `build/png-sticker-beauty-suite.log`、`build/png-sticker-camera.log`：5+2项设备结果。
- `build/png-sticker-device-final.log`：采样修复后两项专项设备结果。
- `build/png-sticker-package-audit.json`：APK哈希与素材打包核对。
- 两库 `build/test-results/testDebugUnitTest/TEST-*.xml`：JVM逐项证据。

设备已有CAMERA授权且处于Awake；本轮未改变权限或系统显示设置。相机帧仅内存处理，没有录制/截图/上传。静态图用项目公开测试人像，临时缓存文件在测试finally删除。未清理用户应用数据，未提交或推送Git。

## 执行命令

```powershell
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_rfb:testDebugUnitTest :core:lib_beauty:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :core:lib_beauty_rfb:assembleRelease :core:lib_beauty:lintDebug :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false --console=plain
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s <Pixel8> shell am instrument -w -e class com.beautysdk.demo.BeautyDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s <Pixel8> shell am instrument -w -e class com.beautysdk.demo.CameraDeviceTest com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

## 未验证/限制

- UI小屏、字体放大、完整视觉矩阵，真人快速转头/遮挡及挂件贴合效果未验收；头饰由双眼粗定位，无精确头顶测量和分割遮挡。
- GL仅验证离屏2D纹理；相机GPU纹理链路、OES、context丢失、RTC写回/远端解码、持续FPS/温升未验证。
- RFB外部关键点权重未选定，默认只有人脸框，不会显示PNG挂件；精确上唇是胡子前提。
- 仅本地静态PNG与轻微缩放动画，没有GIF/序列帧/3D/素材下载平台。
- 本轮未变更native依赖，既有32位MMKV/16KB设备验证限制仍存在，未做新的native兼容声明。

以下为历史记录：

# 本轮：眼镜、胡子与新增礼帽特效（2026-10-23）

本次通过：共享库 16 项 JVM 测试；Pixel8/API36/arm64 的礼帽 CPU/GL 像素一致性与状态恢复测试 1 项（0.097 秒）；两个库 Release AAR、Demo Debug/Test APK、app lintDebug；UI/i18n 静态检查均为 0 警告。失败 0。

实现：BeautySettings.hat、镜像/旋转手性修正的帽子定位、CPU/GL 蓝色礼帽合成；保留眼镜和胡子；静态图及相机测试页新增开关与中英文文案。眼镜、胡子已有实现，本轮未宣称新增素材系统。

证据：build/sticker-verification.log、build/sticker-device.log、core/lib_beauty/build/test-results/testDebugUnitTest。执行 Gradle：:core:lib_beauty:testDebugUnitTest :core:lib_beauty:assembleRelease :core:lib_beauty_rfb:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug。设备测试仅选择 BeautyDeviceTest#glMatchesCpuAndRestoresHostState。

未执行：本轮没有相机采集或人脸动态视觉验收，没有调整权限或显示设置；模型测试/RFB JVM 为历史通过未复测。UI 小屏/字体缩放、真实头部遮挡、PNG 素材、3D、引擎推流及持续性能未验证或未实现。礼帽由双眼推算2D位置，不宣称精确头顶锚点。

以下为历史记录：

# 本轮：两库美颜管线完善（2026-10-23）

按 android-beauty-sdk-workflow、架构、构建及功能回归 Skill 执行。变更范围：lib_beauty 采样节流、共享分析/纹理几何状态、CPU 临时分配；lib_beauty_rfb 统一入口、float32 模型契约及线程资源生命周期；中文注释和模块 README。

- 本次通过：19 项 JVM 测试（共享库 15、RFB 4）；Pixel 8/API36/arm64 3 项 instrumentation（本地 MediaPipe、RFB 真实模型及 CPU/GL 一致性与状态恢复），1.296 秒。
- 构建通过：两个库 assembleRelease、库级 lintDebug（均无问题）、app assembleDebug/assembleDebugAndroidTest；架构静态检查 0 警告。
- 新增回归：分析节流、方向变化后先分析再渲染纹理的结果保留、重启采样时间重置；RFB 生命周期关闭幂等、关闭后拒绝推理及跨线程拒绝释放。
- 失败 0。相机/UI 本轮未执行，上一轮设备通过仅作历史记录；本次未更改相机实现或页面。
- 本轮设备仅安装更新 Demo/测试 APK 并运行模型/离屏 GL 测试，未采集、保存人脸相机帧，未修改权限和系统显示设置。
- 日志：build/beauty-libraries-final.log、build/beauty-device-build.log、build/beauty-libraries-device.log；JVM XML 位于两库 build/test-results/testDebugUnitTest。
- 未验证：RFB 外部五点模型（无权重）、OES/context 丢失、引擎写回与远端输出、持续性能及商业模型许可；没有发布保证。

执行命令：

```powershell
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_rfb:testDebugUnitTest :core:lib_beauty:assembleRelease :core:lib_beauty_rfb:assembleRelease :core:lib_beauty:lintDebug :core:lib_beauty_rfb:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
adb -s <Pixel8> shell am instrument -w -e class 'com.beautysdk.demo.BeautyDeviceTest#mediaPipeDetectsActualPortraitAndReleases,com.beautysdk.demo.BeautyDeviceTest#rfbDetectsActualPortraitWithoutInventingLandmarks,com.beautysdk.demo.BeautyDeviceTest#glMatchesCpuAndRestoresHostState' com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

以下为历史记录，不能视为本轮复测：

# BeautySDK 验证报告

## 最新增量：自定义相机功能（2026-10-23）

- 已实现 CameraX 1.4.2 预览、前后摄、独立RGBA帧、限频/背压、生命周期启停/销毁、晚到帧检查、错误回调；feature测试适配与app相机测试页已接入。请求分析320×240/最多8帧每秒，实际分辨率由设备协商。
- 本次通过：相机模块 JVM 5项，0失败/错误/跳过；Pixel 8 / API 36 / arm64 上6项设备测试（原有4项 + 相机2项），`OK (6 tests)`，7.684秒。证据：`core/lib_camera/build/test-results/testDebugUnitTest`、`build/camera-device-pixel8.log`。测试总耗时不是单帧推理速度。
- 相机设备断言：前/后摄均交付RGBA、alpha=255、前摄镜像标记、只读紧凑缓冲在后续帧后仍保持内容、切摄像头更换session、旧帧失效、stop后不再交付、重新start正常。相机页面基础美颜产生处理图，stop清图。
- 本次通过：相机库Debug/Release、app Debug/AndroidTest/未签名Release及R8；主验证构建 `BUILD SUCCESSFUL in 4m`，末次Debug AAR打包成功。日志 `build/camera-implementation-verification.log`、`build/camera-final-debug-aar.log`。
- 本次通过：相机库lint无问题，app lint 0错误51警告（依赖更新/反射ViewBinding unused等）；架构、代理结构/config-only、UI及严格i18n检查通过。静态检查不等于完整视觉通过。
- Native：CameraX新增 `libimage_processing_util_jni.so`、`libsurface_util_jni.so` 在arm64和armeabi-v7a的PT_LOAD均>=16384；arm64全部库静态对齐通过，既有MMKV的32位4096对齐仍单列。Release `zipalign -c -P 16 4`退出0；未做16KB页设备运行。明细 `build/camera-native-audit.json`。
- 权限仅在宿主声明/请求；merged Manifest新增CAMERA，没有新增录音、存储权限。测试临时授权CAMERA并唤醒原先Dozing设备，结束后已撤销临时授权并恢复熄屏。没有清数据、卸载、录像、保存相机照片或截图。
- 未执行：权限拒绝/永久拒绝完整UI、旋转与后台返回的完整视觉/配置矩阵、缺失镜头设备、真实人脸动态佩戴的效果验收、持续性能和GPU纹理相机输出。CPU路径不是直播引擎集成证据。
- 美颜共享库原16项JVM测试本轮未复测，作为历史通过保留；原4项设备测试本轮已复测通过。新的相机基线独立记录，不用旧骨架AAR代表本轮功能。


## 历史增量：自定义相机模块骨架（2026-10-23）

- 新增 `:core:lib_camera`，namespace `com.beautysdk.camera`，API 24 / JDK 17；尚未实现采集、预览、帧输出，也未被宿主依赖。
- `:core:lib_camera:assembleDebug :core:lib_camera:assembleRelease :core:lib_camera:lintDebug` 返回 0，Debug/Release AAR 生成成功，lint 0 错误、0 警告。日志：`build/camera-module-verification.log`。
- 架构、代理结构和 config-only 校验通过。Release AAR Manifest 无权限声明，产物无 `.so`，native 16KB 检查不适用。
- 本轮只验证新模块与注册，未执行宿主构建、设备测试或相机功能测试。下方美颜/设备结果属于上一轮通过，本轮未复测。
- 新模块源码指纹与产物哈希记录在 `TEST_BASELINE.json` 的 `cameraModuleBaseline`，不推进其他功能用例的通过基线。

日期：2026-10-23，Asia/Singapore。范围：共享处理管线、两个人脸后端、基础美颜、程序化挂件与静态图演示。未提交工作区，使用源码文件 SHA256 作为基线，不虚构 commit。

## 本轮结果

| 范围 | 状态 | 证据 |
| --- | --- | --- |
| JVM | 本次通过 | 共享库14项（原有7项+新管线7项）及RFB后处理2项，共16项，0失败/错误/跳过；各库 build/test-results/testDebugUnitTest |
| MediaPipe 设备 | 本次通过 | Pixel 8 / API 36 / arm64，公开人像单脸478点、锚点及关闭成功 |
| RFB 设备 | 本次通过 | 同设备真实ONNX推理，检出人脸框且没有虚假锚点 |
| GL 设备 | 本次通过 | 同设备 ES3 pbuffer；16x16美颜+眼镜/胡子逐通道误差<=2，宿主状态恢复断言通过 |
| Demo 设备 | 本次通过 | 同设备启动真实Activity，旁路按钮与默认MediaPipe方案处理合成图产生输出；不是人像美观度验收 |
| Android 11 设备 | 受阻 | PCAM00 / API 30：adb install未返回，停止尝试；未执行设备测试，不计为通过 |
| 库与宿主构建 | 本次通过 | 三库Release、app Debug、AndroidTest与未签名Release/R8成功；build/implementation-final.log |
| 最后许可打包 | 本次通过 | 仅增加后端assets许可/来源文字后，重新打包后端AAR、Debug/测试/Release；build/implementation-notices-packaging.log，无逻辑变化，未重复推理测试 |
| lint | 本次通过 | 三个SDK库均No issues found；app 0错误37警告，主要为依赖更新及反射ViewBinding/资源unused提示 |
| 代理/架构/UI/i18n | 本次通过 | 结构、config-only、架构、UI与严格缺失文案检查返回0；静态检查不是视觉验收 |
| 模型校验 | 本次通过 | scripts/verify_models.py，两模型大小与SHA256匹配 |
| Native与打包 | 本次通过（静态范围） | arm64所有库PT_LOAD>=16384；新增MediaPipe/ONNX的armeabi-v7a也>=16384；宿主既有MMKV的32位库为4096；Release zipalign -c -P 16 4退出0 |
| 本地Maven发布 | 历史通过未复测 | 本轮没有执行发布任务，不能使用旧旁路AAR代表当前代码 |
| OES/引擎/持续性能 | 未执行 | 尚无选定引擎版本；不把静态图或pbuffer结果当远端推流验证 |

Pixel 8 instrumentation结果：`OK (4 tests)`，2.829秒，日志 `build/device-tests-pixel8.log`。此时间为整套测试耗时，不是单帧推理时延或FPS。Pixel 8实际PAGE_SIZE为4096，**没有16KB页设备运行证据**。

JVM与完整构建命令见TEST_PLAN，主构建 `BUILD SUCCESSFUL in 1m 27s`。最后许可文字重打包也成功。源文件指纹、精确产物大小/哈希、测试名称与环境见TEST_BASELINE.json。

## 已实现边界

- 共享Session支持RGBA参考处理和ES3纹理处理；纹理人脸分析需要宿主提供空间一致的RGBA输入，没有隐藏GPU读回。
- MediaPipe本地模型能输出关键点及挂件；RFB只附检测模型，五点适配器没有权重，SeetaFace .csta未接入。
- 美颜是基础颜色相似度加权平滑/线性美白/暖冷滤镜；挂件为程序化2D图形，没有瘦脸、大眼、皮肤分割、素材包或像素级遮挡。
- GL测试覆盖2D identity采样矩阵、挂件、alpha、尺寸/旋转镜像元数据保留和部分宿主状态；不代表OES、所有裁剪矩阵或context丢失通过。
- UI断言覆盖启动和按钮/图片输出，设备处于Dozing时截图全黑，未把截图当视觉通过。未修改设备锁屏或个人数据。
- 未验证图片选择器完整往返、多语言/日夜/大字体/小屏/横竖屏视觉、真正的引擎回传及远端画面。
- 模型具体商业再分发条款见doc/third-party/MODELS.md，记录许可证来源不等于已完成授权审核。

## 产物

各库 `build/outputs/aar/*-release.aar`；演示 `app/build/outputs/apk/debug/app-debug.apk`；未签名混淆包 `app/build/outputs/apk/release/app-release-unsigned.apk`。演示同时打包两套后端，不用它的大小代表任一单方案包体增量。未配置远端发布、正式签名或外部Maven消费验证。


## 2026-10-27 场景与美颜资源V2

本次通过：Web 7项Node测试及静态构建；核心83项JVM、feature10项JVM；Pixel 8 `39061FDJH00D3J` 6项instrumentation（2.244秒为测试总耗时，不是帧延迟）。构建包括app Debug与androidTest。新增测试最初变量重名导致编译失败，修正后androidTest构建成功。

本次通过：代理结构、工作流配置、架构、UI、国际化检查（0警告）。浏览器本地加载背景示例，导出状态为“已导出 · 山岚背景”，控制台无error；下载事件工具超时，未将下载文件交叉加载结果计为通过，跨端加载使用正式网页打包器生成夹具。

未执行：Swift/XCTest/Metal编译及设备验证（Windows环境）；iOS分割与美型算法不包含在本库；Demo完整导入/恢复的人工UI测试、横竖屏/大字体视觉检查、持续直播性能和厂商流写回。Cloudflare新版尚未推送或部署。本轮不修改贴纸跟随算法。

实现：V1贴纸兼容，V2独立背景/运镜/美颜；IO有界校验与一次解码、类型独立合并/保存；运镜关键帧按帧时钟；Android焦点裁切；Swift严格加载及Metal场景/美颜参考实现（美型明确拒绝）。

证据目录：`doc/testing/evidence/effects-v2-2026-10-27/`，含真实设备结果与源文件SHA256。最后Demo清除任务加代次保护后的app构建/lint结果追加下文；6项设备结果只覆盖核心资源/GL逻辑。

最终app Debug构建与 `:app:lintDebug` 通过（5分20秒，见evidence/build-effects-final.log）。之后补充“总美颜关闭时不应用导入美颜预设”，仅影响宿主配置组装；最终增量打包结果追加记录。

宿主总美颜开关修订后的增量app打包通过（42秒）。补充Android periodMs/timeMs按Double精确验证整数，避免Float舍入接受小数；最终资源回归构建与设备复验结果随后记录。

最终冻结源码回归：core 83项、feature 10项JVM及app/androidTest打包通过（2分11秒）；最终APK在Pixel 8覆盖安装后6项设备测试再次通过（2.129秒）。最新源文件SHA256和日志已归档。最后两项修订后未重复完整lint；完整lint通过对应此前同一轮构建，详见日志。


## 2026-10-27 工坊V2发布

用户授权发布后，独立Web仓库main提交 `5d00ea7` 已推送，Cloudflare线上 `/effects` 已显示背景、运镜、美颜三类入口。发布前7项Node测试与静态构建通过；Chrome线上确认运镜示例“轻推近”加载完成、美颜示例“自然暖色”加载及导出状态成功，控制台无error。主站入口保持 https://beautystickerworkshop.pages.dev/ ，场景工坊 https://beautystickerworkshop.pages.dev/effects 。Python HTTP直连校验返回403，未将其当作内容hash验证通过；实际发布功能以Chrome线上验收为依据。GitHub main远端与本地提交一致。未改动Android/iOS源码或重新宣称真机功能已验收。


## 2026-10-28 相机整体重置与长按原图

- 新增常驻整体重置入口：经确认停用旧工坊及内置贴纸覆盖，通过 ResourceCenterModel.resetDefaults 恢复全部资源分类、参数和手动背景/运镜默认值；保留已安装资源。分类重置继续保留。
- 原图对比改为按住显示原图，UP/CANCEL 恢复，页面停止时清理临时原图状态。人脸、处理 FPS、CPU 合成提交耗时及手势放在原图按钮下方同一半透明框，未新增性能采样。
- `:app:assembleDebug :app:lintDebug` 通过；UI/i18n 校验均 0 warning。
- Pixel 8 / Android 16：DemoLayoutDeviceTest#originalHoldReleasesAndDiagnosticsStayBelowButton，1 项通过，覆盖按下/松开/取消、三项信息位于按钮下方、整体重置确认及旧贴纸关闭。日志 build-ui-hold-device.log。
- 未覆盖小屏横屏、系统大字体及强杀后的重置持久化；本次不代表真人画质或持续性能验收。


## 2026-10-28 诊断卡稳定布局与默认检测开关

- 诊断卡固定168dp宽，统一无按钮背景的11sp等宽字体、左对齐、单行省略；FPS和CPU合成提交时间固定两行。圆角半透明底与细描边替代各行按钮样式，动态文字不再撑开外框。
- 更多菜单增加人脸检测/手势检测，Demo新页面默认开启；配置重建保留当页选择。回调详情不再显示手势开关，诊断文字不承担开关操作。
- Demo新增显式人脸观察需求与关闭门控，不依赖是否选中贴纸/美型；关闭后效果不能隐式重新启用人脸分析。公共SDK默认需求规则不变，长按原图仍暂停人脸和手势分析。
- assembleDebug、lintDebug、feature_app JVM测试通过，PreviewRequestTest共8项零失败（含显式观察/关闭门控及原图旁路）。UI/i18n脚本0 warning。
- Pixel 8 Android16，DemoLayoutDeviceTest两项通过：diagnosticsKeepBoundsAndMoreControlsDefaultDetection、originalHoldReleasesAndDiagnosticsStayBelowButton。覆盖长手势状态/123.4 FPS与9999ms下卡片全局矩形不变、默认开关及独立切换、长按松开/取消与重置。日志build-ui-diagnostics-device.log。
- 未验证大字体/横屏小屏视觉，未执行默认双检测的持续温升与性能验收；自动断言尺寸稳定不等同主观美观验收。
## 2026-10-29 效果弹框默认预设与抖动修复

本次通过：PANEL-01/PANEL-02 及相关回归共 8 项 Pixel 8 设备测试，11.61 秒，最终 0 失败；APK 构建、lint、UI 静态门禁通过（0 UI 警告）。修复版已安装到 Pixel 8。测试完成后设备断开，最后一次启动 Demo 的命令受阻（device not found），不影响已完成的设备断言。

默认打开/切换分类/关闭重开进入预设；微调保留单项强度设置。预设与微调使用同一固定面板高度，避免重复标题、padding 和尺寸写入；资源状态变化复用参数控件，避免拖动中重建滑杆。设备测试在模式切换、连续调参、分类切换后分别采样 12 个绘制帧，面板位置和尺寸一致，滑杆实例保持。

首次设备测试 8 项中 1 项失败：主题首次展开为子资源后重建了滑杆；增加独立参数控件缓存后上述 8 项复测全部通过。首次最终构建在 lint 阶段 Gradle daemon 消失，单 worker/1536 MiB 重试构建和 lint 成功。未将这些首次失败隐去。

证据：[summary.json](evidence/effect-panel-20261029/summary.json)，包含实际源码指纹、APK 摘要和初次/最终日志。未保存相机图像。横屏/字体完整矩阵、真人观感、持续性能、直播厂商链路未复测，逐帧布局断言不等于所有场景视觉验收。
## 2026-10-29 SDK / 个人CMS整体优化

本次完成参数生成契约、排序/语言与跨页刷新、在线手势V2、个人版本备份、统一发布/回滚、APK资源一致性门禁、弹框窗口职责分离与文档更新。用户明确仅个人使用CMS，直播引擎暂不接入。

最终通过：Web75项、资源JVM8项；Pixel8完整26项（130.77秒），最后刷新改动13项补测、测试备份修订7项补测均0失败。APK及资源库Release AAR、lint、架构/UI/i18n通过。发布版本r-2186b343f0f9c403与APK内资源逐字节一致。完整组先于最后小修，补测对应变化范围，不叠加为独立用例数量。

每组约31秒短测：原图24.05FPS、检测24.05FPS、检测加基础美颜24.04FPS，热状态0。无检测到人脸，因此不代表美型/贴妆实际负载、真人画质、长时热稳态或远端推流；没有相机图像落盘。

初次失败及修复包含：息屏导致UI观察失效、测试反射字段变化、默认配置/删除手势的测试隔离遗漏、一次补偿恢复二进制截断及主机内存不足。最终补偿恢复仅针对本轮测试配置/样例，用户参数保留，4组手势包及封面摘要均核对；旧默认文件未保存原文，不声称逐字节恢复。详情与限制见[本轮交付报告](../SDK_CMS_OPTIMIZATION_20261029.md)。

证据：[summary.json](evidence/sdk-cms-optimization-20261029/summary.json)。本轮未部署网站、未提交push，iOS/真人/多机型长测未验收，直播厂商接入排除。


## 2026-10-29 CMS 授权签发与下发验证

相邻BeautyStickerWorkshop新增受登录保护的授权生成、下载、一次性领取链接、续期/撤销/恢复及同源/v1/lease登记查询。Web94项、最终受影响后台/授权10项、实际bundle/workerd/D1/R2集成1项通过；原SDK services/license两组Node测试9项通过，CMS WebCrypto与SDK原工具的grant及4种租约逐字节一致。浏览器14项流程检查、1440px/360px布局及实际管理员登录验证通过，具体记录见相邻项目.site-audit/licensing-validation.md。

本地签发器已初始化并由8090预览加载；密钥未写入SDK、未创建实际客户授权、未部署生产服务。原CLI JSON登记与CMS数据库不自动同步。未修改Android授权运行时，也未执行Android构建/R8/GPU/客户真机激活；先前这些证据不得替代本轮尚未执行的正式HTTPS与发行公钥验证。


## 2026-10-30 素材预览简化（CMS生成端）

用户要求普通贴纸/美妆预览简洁，套装封面更精致。相邻BeautyStickerWorkshop新增resource-cover.mjs共用无文字封面；320×320、中央安全区、透明素材有效边界裁剪、浅底深色图案，套装叠卡使用子项预览。贴纸/睫毛/效果/套装导出及新建参数预设复用；旧自定义封面保留。50份常规资源更新修订，保留历史文件；效果参数与实际贴纸原图不变。完整生成后刷新规格并可重复得到同一发行快照。

验证：Web 94项测试通过；Chrome实际PNG生成、含嵌入PNG的套装SVG转PNG、透明边界读取通过，输出320×320；npm run build通过。完整生成与重复刷新得到r-9a48ce3597141710。通过既有release-library脚本同步app assets完整ZIP/清单/defaults/marker；:app:assembleDebug成功，含verifyBuiltinResources。预览总览位于相邻项目build/cover-contact-sheet.png，规则和验证详见相邻项目RESOURCE_COVERS.md。

限制：未部署线上、未在真机安装本轮APK，未验收全部小屏/横屏/字体场景。Android旧选择和用户清单继续保留，新素材不会自动替代已选旧摘要；浏览器仅更新与历史发行字节相符的内置项，不覆盖自定义修改。不代表真人效果或持续性能通过。
