# 第三方接入SDK与资源包的APK增量（2026-10-30）

当前工作区实测：使用同一个平台Activity最小宿主，分别构建空宿主、引用统一SDK入口、引用SDK并内置当前完整资源ZIP三个Release变体。均开启R8与资源压缩，为未签名本地测量包。单位MiB（1MiB=1,048,576字节）。

| 接入内容产生的APK增量 | arm64-v8a（64位） | armeabi-v7a（32位） | 同时包含两种ARM ABI |
| --- | ---: | ---: | ---: |
| 完整SDK及其传递依赖/内置模型 | **20.72** | **17.62** | **27.93** |
| 另加当前完整资源ZIP | **2.20** | **2.20** | **2.20** |
| 合计增加 | **22.92** | **19.82** | **30.13** |

以上均减去同ABI空宿主的2,232字节，不是直接把整个Demo大小当接入增量。当前完整资源ZIP为app/src/main/assets/resource_delivery.zip，原文件3,429,590字节（3.27MiB），进入此Release APK后额外约2.20MiB。并不意味着所有用户自制资源包都只有2.20MiB；资源增量跟随其实际图片、动画、数量与压缩结果变化。

## 测量口径与限制

- 从最新源码发布五个同版Maven制品至隔离本地目录build/sdk-size-probe/repo，宿主仅声明com.beautysdk:beautysdk:0.2.0。未远程发布，也未覆盖原build/repo。五个AAR内源码记录逐文件与当前工作区哈希核对一致。
- 三个变体共用同一Activity/Manifest/编译参数，不引用Demo、WKQ或相机。最终APK确认没有Demo的MMKV/CameraX原生库；对照依赖树见dependencies.txt。
- 因最小Activity本身不调用SDK，为避免R8将未用SDK代码剔除、低估完整功能体积，测量项目专用规则保留全部com.beautysdk代码并允许优化。这是完整SDK的保守体积口径，不是要求客户添加全量keep规则。真实宿主的已有依赖、调用范围、R8规则和压缩方式会影响结果；不要承诺任意App的精确增量都一致。
- SDK当前统一入口会带入MediaPipe、GPUPixel以及人脸、手势、背景三个模型。仅少调用几个接口不会自动移除assets中的手势模型。模型与能力拆分/按需交付尚未在本轮实施。
- 资源若通过显式下载后安装，ZIP本身不进入初始APK；下载缓存、解压与运行内存另算。当前模型已在SDK AAR里，资源包走网络并不会同时去掉SDK内置模型。
- 通用测量包显式排除上游附带的x86/x86_64 SO，只包含两种ARM ABI。按ABI分发时应参考对应单ABI增量，不应把通用包数字当成每台设备的实际下载增量。
- 本轮只验证发布、依赖解析、Release/R8构建、APK内容与字节数，没有安装运行测量宿主，不能描述为授权、模型推理、渲染、长期运行或16KiB兼容的设备验收。没有修改正式SDK、Demo、独立示例的源码与打包配置。

## 证据与重建

精确字节数、APK/AAR摘要、源文件哈希、模型列表及增量计算见[结果数据](evidence/sdk-increment-20261030/results.json)。[测量工程](evidence/sdk-increment-20261030/probe/app/build.gradle)与隔离发布init脚本均随证据保存；实际测量APK在build/sdk-size-probe/host/app/build/outputs/apk下，仅保留本地，不纳入Git。

发布命令：`gradlew.bat -I build/sdk-size-publish.init.gradle publishBeautySdkToLocalBuildRepository`。

宿主构建命令：`gradlew.bat -p build/sdk-size-probe/host :app:assembleRelease`。

发布和最终宿主构建通过，精确依赖树与日志见同一证据目录。本轮不推进运行功能基线，不自动提交或push。
