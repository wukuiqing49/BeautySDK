# 独立消费者示例

2026-10-29：已从本地Maven发布制品完成Debug构建，Pixel8运行显示“本地帧处理完成”。这是RGBA生成色卡接入验证，GPU示例执行、正式签名及真人质量仍单独验收；完整构建证据见主工程doc/testing/TEST_REPORT.md。

本工程不引用根工程的 project 模块、WKQ、CameraX或直播厂商。
首页使用生成色卡演示RGBA生命周期；不是美颜效果展示或性能测试。
TextureIntegration.kt 展示MediaPipe/GPUPixel组合，要求宿主已有当前ES3上下文。

本示例仅声明`com.beautysdk:beautysdk:${beautySdkVersion}`，四个模块通过Maven传递解析。先在SDK工程发布入口及实现模块到build/repo：

```text
gradlew publishBeautySdkToLocalBuildRepository
```

之后用Gradle 8.13/JDK17在本目录构建，或在仓库根目录执行 `gradlew -p samples/standalone :app:assembleDebug`。
执行`:app:verifyBeautySdkEntry`检查编译/运行依赖图包含入口和四个同版本模块；Release/R8使用`:app:assembleRelease`。这是一个Maven入口，不是独立无依赖AAR，交付整个repo目录。
`-PbeautyRepo=绝对仓库路径` 可替换默认的 ../../build/repo，版本在gradle.properties。
Google/Maven Central传递依赖首次解析需要联网；本地SDK仓库不等于完全离线依赖镜像。
发布脚本会携带Gradle Wrapper，但首次下载Gradle/Android SDK仍需预先准备。

纹理路径：在同一EGL线程创建 → start → 设置settings → process(TextureFrame) → 同帧消费输出 → stop/close。
输出纹理借用至下一次process/close；异步消费需显式复制/同步。纹理分析桥会缩小读回，并非零拷贝。
context丢失的恢复必须按SDK契约另行处理，不可将旧纹理ID用于新context。
模型初始化错误通过pipeline的错误回调处理；createTexturePipeline(context, onError)要求调用方显式传入错误回调。回调可能来自worker，UI更新必须切主线程；不要在日志中包含图像或授权秘密。

资源用BeautyResourceManager在后台安装完整ZIP；要求发行签名时使用SignedResourceDelivery，不允许验签失败后退回无签名入口。
构建与设备证据按主工程测试报告逐轮记录。最新单入口版本的设备运行、独立GPU实际画面和真人资格仍待验证。


ResourceIntegration.kt 提供后台加载 assets 完整包、按实际能力准备默认搭配和单项美白调整。素材ZIP不随本样例自动附带，从工坊导出放入app/src/main/assets/resource_delivery.zip。返回ComposedBeauty须保留到GL管线不再引用，随后close；失败不替换旧快照。手势默认启用时明确拒绝，需另外装配手势运行时与播放器。

外部接入按候选包doc/FIRST_INTEGRATION.md记录，不能把开发者自测算作陌生接入者验收。

## 0.2.0 授权接入

Maven 制品使用 Release 策略，即使本示例构建为 Debug 也需要发行方公钥配置和绑定本示例包名/当前签名的 assets/beautysdk.license。示例已调用 BeautySdk.initialize，显示授权状态，并在 BEAUTY 获准后处理样例。默认本地未配置公钥的制品只能验证构建，会显示配置错误；这不表示美颜处理通过。详见 ../../doc/LICENSING.md。
