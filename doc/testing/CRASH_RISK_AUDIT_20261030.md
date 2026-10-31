# 相机与资源链路崩溃风险审计（2026-10-30）

本轮为源码审计，仅新增本报告，未修改运行代码、执行构建或新增真机复现。以下“确认”指代码存在对应异常或分配路径，不代表已在当前设备发生崩溃。此前 OPPO 分割 GPU 原生崩溃的修复与设备证据见 TEST_REPORT.md，本报告不能替代持续稳定性测试。

后续优化已实施：组合加载逐包传入剩余像素预算，超限包在Bitmap/ARGB解码前拒绝；分割诊断回调隔离并报告失败，关闭使用finally清理；预览回调独立保护，错误回调失败不递归，GL释放逐项继续；缩略图增加32项/8MiB的LRU保留上限，不回收View仍可能引用的Bitmap。旧loadZip(InputStream)入口保留，新增带像素预算重载供组合加载使用。后续实测结果以TEST_REPORT.md最新记录为准。

这不等于整个进程内存有统一硬上限：仍有压缩包、预览字节数组、当前已启用资源及GPU纹理等并存开销。未通过捕获OOM或放宽关键点有效期规避错误。以下保留原始审计依据。

## 优先处理

### 1. 手势组合在合计预算检查前解码所有资源（高优先级）

- 位置：`core/lib_beauty_resources/src/main/java/com/beautysdk/resources/BeautyResourceManager.kt:101-107`；解码实现 `core/lib_beauty/src/main/java/com/beautysdk/GestureEffectArchive.kt:21-35`。
- `prepareGestureSelection` 允许最多八个包，先 flatMap 调用 loadZip，之后才判断组合像素是否超过 8,388,608。单包预检正确，但不能限制组合解码的峰值。
- 每包可包含约 8M 独立像素，八包累计约 64M 像素，仅保留一份 ARGB 数组就约 256MiB；解码期间还有 Bitmap、压缩内容及现有渲染资源。最终规则数量/手势互斥检查也晚于解码，不会避免先行分配。
- 风险：大资源组合在返回预算错误之前就 OutOfMemoryError；普通 Exception 捕获不能将其可靠变为业务错误。未在用户设备执行耗尽内存实验。
- 建议：拆分元数据/尺寸预检与解码；先验证整个组合的规则、去重图片像素及预算，再解码；与现有正在使用的资源并存峰值也应纳入预算。不要以捕获 OOM 代替预算。

### 2. 背景分割工作线程的错误回调与释放异常未隔离（高优先级）

- 位置：`core/lib_beauty_mediapipe/src/main/java/com/beautysdk/mediapipe/MediaPipeForegroundMasks.kt:123-133,228-232`。
- drain 捕获 process 的 Exception 后直接调用外部 onError。若回调抛出异常，会逃出 Executor 工作线程；Android 默认未捕获异常处理可能终止进程。scheduled 也未在该异常路径复位，自定义异常处理器即使保住进程，后续调度仍可能停住。
- close 排队执行 segmenter.close 与其余释放，没有异常保护或 finally；底层关闭抛出 Java 异常时，后续清理跳过且异常逃出工作线程。
- onTiming 同样直接执行，回调异常会被当成推理错误送往 onError。当前 Demo 回调尚无真实抛异常证据；公共 SDK 的外部回调场景仍需防护。
- 建议：明确回调错误策略、隔离诊断回调、确保调度标志及释放 finally 收敛；参考现有 AsyncFaceAnalysis/GestureAnalysisRuntime 的处理。Java try/catch 不能拦截原生 SIGABRT。

### 3. 预览投递到主线程的回调没有错误边界（中优先级）

- 位置：`feature/feature_app/src/main/java/com/beautysdk/demo/feature/app/GpuBeautyPreview.kt:555-562`，以及 report 的主线程错误回调。
- main.execute 内对 onImage/onSceneStatus/onGestures 只有 finally。外层 onDrawFrame 的 catch 无法捕获稍后执行的主线程异常。运行状态与代次检查已存在，但不能保护回调内部自身的错误。
- 触发前提是宿主回调抛异常；没有证据表明当前正常回调已经因此崩溃。应先区分宿主业务错误与 SDK 错误，避免统一吞异常导致状态损坏或错误回调递归。
- 建议：补可观测、限频且不递归的错误边界，并测试回调故障、页面退出及快速重建。

## 次级风险与已有保护

- 资源预览：ResourceCenterController 有删除后 retainAll 清理，不是删除泄漏；但已安装/在线缩略图没有字节级 LRU 总预算，ResourceCenterModel 也维护批量预览字节数组。大量资源下仍有内存压力，需要带规模指标的压力测试，不可直接宣称已发生 OOM。
- GPU 释放为串行调用，部分关闭失败可能跳过后续清理，应验证每项独立 finally 与上下文丢失恢复；尚无本轮实际故障证据。
- 已检查的原生 RGBA、蒙版、运动估计入口具有容量、尺寸、步长等校验；GPUPixel 包装具备 EGL/线程/句柄检查。未发现可确认的新增 native 越界路径，但源码审计不证明没有内存安全缺陷。
- GPU 兼容策略仅覆盖已验证的 OPPO OP46B1/API30 分割问题。其他 GPU/固件及长期并发负载仍需设备验证；不应扩大为所有设备都已安全，也不应无证据统一禁用 3D。
- 3D 帽子整顶闪烁仍是独立未验收问题；不得通过延长真实关键点有效期或伪造时间戳掩盖。

## 后续验证顺序

1. 组合资源预算在解码前拒绝，边界值仍能加载；不靠真实 OOM 验证。
2. 用可控故障注入验证 onTiming/onError/close 异常，后续任务及释放仍有明确结果。
3. 验证主线程回调错误策略、页面退出/重进、后台恢复、旋转及上下文重建。
4. OPPO/Pixel 真实相机持续运行，记录 Java/native 崩溃、内存趋势、后台恢复及资源反复切换；不采集保存人像帧。短夹具通过不代表这一项通过。
