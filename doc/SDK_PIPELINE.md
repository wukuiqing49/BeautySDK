# 公共SDK处理链路与接入边界

当前主线为 MediaPipe + GPUPixel，基础 OpenGL 仅作内部对照；本轮用户明确不接直播厂商。

## 统一运行链路

采集方提供RGBA分析小图 → BeautyAnalysisRuntime有界检测/可选跟踪 → BeautySession读取有效快照 → BeautyRenderer美颜及贴纸 → 宿主消费输出纹理。

相机Demo通过BeautyEngineConnector<TextureFrame> → BeautyEnginePipeline(sharedAnalysis) → BeautySession消费同一公共链路。CameraX/OES采集及屏幕双画面属于宿主；核心库不创建相机、不依赖Activity或WKQ、不编码推流。

## 线程和所有权

- 采集线程只提交借用RGBA；运行时批量复制后源缓冲可回收。模型创建、推理、释放在SDK worker完成。
- GL会话start/process/redraw/stop/close必须同线程及合适EGL上下文。跨线程RGBA提交使用共享分析运行时，不跨线程调用Session.submitAnalysis。
- 普通Session拥有其分析运行时；sharedAnalysis由创建者拥有，关闭渲染会话不关闭共享分析。
- 纹理只借用，不允许跨不共享EGL上下文直接传ID；输出在下次render/close前有效。适配异步消费者需要独占纹理及同步协议。
- 停止、更换同尺寸视频源、镜头或裁剪视场必须reset分析运行时；错误、无人脸、过期不能用无限延用旧位置掩盖。

## 本轮优化

1. 公共分析需求、限频、跟踪、分析尺寸配置收敛；无贴纸基础美颜跳过分析。
2. 相机使用BeautySession合成，支持同帧参数重绘，不伪造采样时间。
3. 双线性素材采样及CPU/GL一致的参考规则。
4. 当前选材按需加载、有界任务、最多三层；独占解码数组减少一次复制。
5. 工坊在途载入不能撤销清除动作；参数草稿私有原子保存、恢复默认关闭。
6. 公共有界时序与渲染诊断，明确CPU提交、采样年龄和本地消费帧率的含义。

## 未完成及验收边界

- 未接直播厂商、新增YUV420到RGBA小图兼容转换，但未提供高清YUV美颜及回写管线、未引入libyuv、未实现纹理零拷贝MediaPipe输入。
- 跟踪是实验性小运动匹配，线性采样不保证修复检测过期闪烁；须真人遮挡、转头及进出画面验收。
- 没有3D遮挡、美妆、瘦脸大眼或动态序列帧资源。
- 发布前仍须固定设备/分辨率做原图、基础美颜、检测、检测加美颜贴纸的持续对比，记录尾延迟、FPS、内存和温升；实际引擎再验证远端输出。

测试结果以 doc/testing/TEST_REPORT.md 为准，构建和夹具通过不代表持续性能或所有设备视觉通过。

## 数值诊断采集

先在Demo手动固定前后摄、输出分辨率、delegate、效果强度和光线，再分别设置原图、美颜、美颜加贴纸运行：

```powershell
python -B scripts/collect_beauty_diagnostics.py --serial <目标设备> --scenario original --duration 60
python -B scripts/collect_beauty_diagnostics.py --serial <目标设备> --scenario beauty --duration 60
python -B scripts/collect_beauty_diagnostics.py --serial <目标设备> --scenario beauty_stickers --duration 60
```

脚本不会操作相机或切换效果，场景名是操作者声明；只读取固定SDK诊断标签、PSS及电池温度，JSON默认写build/functional-test。没有帧日志不能当作性能通过，存在日志也不等于持续性能验收。正式比较应预热、保持相同人脸动作，并延长到热稳态；电池温度不等于芯片结温。

## 公共接入补充

- Yuv420Frame/YuvPlane声明借用平面、stride/pixelStride及颜色矩阵范围。Yuv420RgbaConverter用于低分辨率分析输入，先采样再转换、复用direct buffer；不是native加速或YUV输出。
- GlTextureOutputPool显式复制RGBA 2D结果，有限槽位按Lease归还复用，池满返回null；GPU生产及消费同步由适配层负责。不是任意纹理零拷贝，也不支持直接复制OES。
- 引擎专属接口、真实引擎OES输入验收、跨context fence和上下文丢失恢复仍须适配；不能仅凭公共类型即宣称厂商接入完成。

## 纹理来源的检测输入（2026-10-26增量）

只有原始纹理时可选GlTextureAnalysisBridge：2D/OES → GPU缩小 → 双PBO/fence后续帧轮询 → 同一BeautyAnalysisRuntime → 同一BeautySession。原高清纹理继续直接合成，检测支路不改变输出分辨率。现有相机独立RGBA支路不启用此桥接器，避免额外GPU读回。

两槽有界、未完成跳过；默认采样间隔约33ms、最长边320、待取年龄100ms。读回不创建Bitmap，保留原采样时间；不主动等待fence，但GPU驱动开销和新增后续帧延迟仍需测量。只在同创建ES3上下文调用，厂商专属生命周期与纹理同步另行适配。详见lib_beauty README。

## 统一取帧/处理/写回入口（2026-10-26）

厂商回调 → BeautyEngineConnector.onFrame → adapter.read → BeautyEnginePipeline → adapter.write。Pipeline复用Session、AnalysisRuntime和可选纹理小图桥接；业务只调用一次onFrame。read/write/passthrough是厂商薄封装，不把厂商类型引入公共库。

入口同步执行，无新线程或帧队列；固定处理线程/EGL由集成方保证。解包/处理失败、未尝试写回时旁路一次；已尝试写回不再重试，避免重复提交。原图模式也必须write交回。PROCESSED/BYPASSED/REJECTED是本地交付结果，不是远端播放状态。

设置pipeline.settings，关闭人脸效果即跳过分析；没有检测factory仅基础美颜。已有RGBA检测支路可关闭纹理桥接。resetSource重启时间线，close释放自有处理资源；先停止厂商送帧，再在GL执行线程释放。引擎异步消费仍需显式租约和双向GPU同步，不能误把回调返回视作消费完成。

本轮未引入厂商依赖、未实现声网/ZEGO帧类型及写回方法；不宣称直接注册引擎即生效。完整公共调用示例见lib_beauty README。
