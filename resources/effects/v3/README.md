# 美颜资源 V3 与统一交付

## 包格式

- 贴纸：schemaVersion=1，透明 PNG + 相对锚点配置。
- 背景、运镜：schemaVersion=2，保持原字段与像素预算。
- 美颜：V2 的旧 7 项扩展参数继续读取；新制作使用 schemaVersion=3。
- 主题：bundleVersion=1，固定 sticker.zip/background.zip/motion.zip/beauty.zip，缺少类别为 null。容器格式不变，beauty.zip 可为 V2 或 V3。

V3 BEAUTY 的 adjustments 必须完整包含 rosy、brightness、contrast、saturation、sharpening、slimFace、bigEyes、lipstick、blusher、slimNose、chinLength、mouthSize。brightness/chinLength/mouthSize 为 -1…1；contrast/saturation 为 0…2（中性 1）；其余为 0…1（中性 0）。未知字段、缺字段、非有限数及越界值均拒绝；禁止把新字段写入 V2。

Web 是素材制作端，中央预览为布局示意，不执行真实磨皮、人脸分割或美型。Android GPUPixel 消费全部参数；iOS 解析同一资源协议，但当前 Metal 后端明确拒绝非零美型、口红和腮红，不能把解析成功等同于渲染支持。

## 制作与使用

1. Web 首页选择贴纸 / 背景 / 运镜 / 美颜，在右侧调整，点击“应用到主题”。
2. 在中央预演组合与运镜，检查能力要求后统一导出主题 ZIP。
3. Android 资源中心加载内置示例、导入单类或主题 ZIP，或从 HTTPS 目录下载。
4. SDK 在 IO 线程校验资源并准备不可变配置，宿主一次发布给视频处理管线。
5. 下次启动恢复已保存资源。预设生效时先明确清除再手动调整，避免滑杆被资源配置覆盖。

## 网络目录

Web npm run build 生成 dist/catalog.json，与 samples 一起部署。目录地址默认 https://beautystickerworkshop.pages.dev/catalog.json；本轮本地构建并不等于已发布。

目录结构为 catalogVersion:1 与 resources 数组；每项严格包含 id/name/version/type/url/bytes/sha256。type 为 STICKER/BEAUTY/BACKGROUND/MOTION/THEME，URL 必须 HTTPS，SHA-256 为 64 位小写十六进制，单包 ≤32 MiB，目录 ≤256 KiB/100 项。主题也可通过此格式托管，不需要改变 SDK。

BeautyResourceStore 接受宿主提供的私有缓存目录和可选 BeautyResourceTransport，可复用业务 HTTP 客户端。目录与缓存均只在显式调用时访问；默认连接/读取超时为 10/15 秒，不自动跟随重定向。下载逐块限量，校验大小与摘要，执行真实包解析后原子写入缓存。缓存身份以摘要为准，离线可复用同一已校验版本；失败保持旧的已应用效果。发布新版本必须重新生成目录摘要与大小。

SDK 不上传相机帧，核心 AAR 不声明联网权限；Demo 为主动资源下载声明 INTERNET。当前缓存由宿主目录管理，正式应用应按业务存储预算定期清理未使用版本。

## 性能指标

BeautyEnginePipeline.performance 可读取公共 SDK 统计：成功 process 次数、窗口处理 FPS、最近/平均 CPU 提交时间。redraw 不计新帧，首个一秒窗口前 FPS 为空；gpuExecutionNs 当前为空。Demo 每 250ms 更新透明浮层。本地处理 FPS 不是编码推流 FPS，CPU 提交时间不是 GPU 完成时间，不为统计调用 glFinish。
