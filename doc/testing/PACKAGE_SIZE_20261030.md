# Android 包体实测（2026-10-30）

基于当前工作区源码实际构建，单位MiB（1MiB=1,048,576字节）。这里是完整Demo APK，不是SDK在任意宿主中的净增量，也不是解压后的安装占用或运行内存。

| 包体 | arm64-v8a（64位） | armeabi-v7a（32位） | 两种ARM ABI通用包 |
| --- | ---: | ---: | ---: |
| Debug APK | 35.55 | 32.19 | 43.19 |
| Release APK（R8＋资源压缩，本地未签名） | 26.48 | 23.15 | 34.09 |
| Release内原生SO总和（已包含在APK中） | 10.88 | 7.56 | 18.44 |

精确字节数、每包SHA256与构成见[evidence/package-size-20261030/sizes.json](evidence/package-size-20261030/sizes.json)。分ABI APK留在本机build/size-analysis-20261030；正常双ABI APK已恢复生成到app/build/outputs/apk。没有修改正式Gradle ABI配置，没有安装此次测量包。

## Release构成

| 内容 | arm64-v8a | armeabi-v7a |
| --- | ---: | ---: |
| 原生SO | 10.88 | 7.56 |
| assets模型/内置资源（ZIP内压缩体积） | 12.87 | 12.87 |
| DEX代码（ZIP内压缩体积） | 1.22 | 1.22 |
| 其他条目（资源表、Android资源、Manifest等） | 1.34 | 1.34 |

条目体积之外还有ZIP目录、页对齐与签名相关开销，不能直接用条目和当最终APK大小。Debug和Release压缩策略也不同，不能用文件原始体积推断下载体积。

主要原生库：

| SO | arm64-v8a | armeabi-v7a |
| --- | ---: | ---: |
| libmediapipe_tasks_vision_jni.so | 9.85 | 6.90 |
| libbeauty_gpupixel.so | 0.354 | 0.222 |
| libbeauty_rgb_input.so | 0.079 | 0.059 |
| libmmkv.so（Demo宿主依赖） | 0.569 | 0.354 |
| CameraX相关两份SO | 0.032 | 0.023 |

Release中主要共用资源的APK内压缩体积：手势模型6.07MiB、人脸模型3.18MiB、分割模型0.24MiB、完整示例资源包2.20MiB、GPUPixel LUT 0.44MiB。模型不因ABI分包减半。模型原始总量约11.81MiB，以上三模型APK内合计约9.48MiB。

## 优化判断

1. 保持全功能的直接收益来自按ABI交付和Release/R8：单ABI Release相比通用Release分别少约7.61MiB/10.94MiB，主要去掉另一ABI的SO。
2. 可选能力的模型按需交付最值得评估：手势模型本身占约6.07MiB的Release包体。运行路径必须先完成模型准备，不能直接删除仍在使用的文件；人脸/手势共用MediaPipe原生依赖，移走手势模型不等于能减掉整份SO。
3. Demo完整示例库占2.20MiB，存在旧资源包与完整交付包并存，可在核对真实引用后制定精简方案。本轮仅测量，未删除资源或关闭功能。
4. SDK接入大小需要同一最小宿主的接入前/接入后同ABI Release对照；单个AAR或SO体积不等于传递依赖、模型及最终R8后的APK增量。现有旧AAR未作为本次最新源码发行体积依据。

## 测量与验证范围

- 使用临时init脚本build/size-analysis-20261030.init.gradle生成分ABI Debug/Release；Release显式传入-PrequireReleaseSigning=false，启用项目原有R8与资源压缩。
- 临时universalApk=true因上游预编译库包含x86/x86_64会生成额外模拟器ABI的包，本报告不使用该临时通用产物。分ABI APK逐一确认只有目标ABI；随后不用init脚本重建正常双ARM ABI产物，确认只有arm64-v8a/armeabi-v7a，且其中SO字节与相应分包一致。
- 恢复正常配置的第一次Release构建遇到Windows dex文件占用；重试Debug/Release成功。保留失败和重试日志，不将失败尝试计为构建通过。
- 静态检查分ABI Release ELF：arm64全部PT_LOAD对齐至少16KiB；v7a的libmmkv.so为4KiB、其余为16KiB。这不等于完整16KiB打包/运行验收，本轮未执行zipalign专项或设备兼容测试。
- 本轮无业务源码/依赖/Manifest改动，未新增功能测试或推进功能通过基线。构建通过仅说明可打包，不代表正式签名、授权、发布或长期运行验收。
