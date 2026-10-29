# 客户接入与兼容契约

当前能力、验证范围与限制见 [产品状态](PRODUCT_STATUS.md)。

## 能力声明

安装资源、支持资源格式、实际装配了组件、通过商业画质验收是四件不同的事。
资源管理器可安装完整库，应用效果前必须用实际可用组件构造ResourceCapabilities：

```kotlin
val capabilities = ResourceCapabilities.configuredGpuPixel(
    faceAnalysis = true,   // 已装配并能启用478点人脸分析，不是只有人脸框
    sceneRenderer = false,
    segmentation = false,
)
val missing = capabilities.missingFeatures(descriptor)
// missing非空时，UI显示不可用原因；不要悄悄丢掉不支持的参数再宣称应用成功。
```

这些布尔值是宿主声明，不会实例化模型或检测硬件。运行期间模型初始化失败、相机权限问题等仍需处理。
旧androidGpuPixel()保留源码兼容，表示完整组件集合，不适合直接当自动探测结果。
`resources/commercial/capabilities.json`只描述候选/实验功能及未验收状态，不改变实际资源协议，也不授权宿主使用收费内容。

## 错误反馈

UnsupportedResourceException保持IllegalArgumentException继承关系，提供code、resourceId、missingFeatures，不要求客户解析英文message。
使用BeautyEnginePipeline时接入onError；回调可能来自分析worker或处理线程，更新UI需切主线程。回调内不得同步阻塞渲染。
独立示例的createTexturePipeline要求提供onError，避免默认空回调掩盖模型失败；构造阶段异常仍由调用方捕获。
资源失败保留当前已启用效果；无有效人脸时按现有时效规则旁路依赖人脸的效果，不能冒用旧定位。
本轮没有把所有历史异常统一成错误枚举，调用方仍需处理普通IO/参数/生命周期错误。

## 资源与版本

SDK版本、资源格式版本、资源revision和发布ID各司其职。相同资源ID/revision不能换字节；新素材需提升revision。
候选打包器检查POM/module坐标、同版核心依赖、直接依赖一致性、变体摘要以及AAR/sources完整性。
新增依赖需更新publication_contract.py的审查白名单；不通过忽略依赖差异绕过门禁。
这些结构检查不证明二进制API兼容，也不证明构建制品与当前未提交源码一致。交付时需保留实际构建来源、源码摘要和验证记录。

## 客户信息与诊断

默认不记录图像、纹理内容、授权密钥、完整网络地址或客户文件路径。客户可自行收集SDK版本、设备档位、错误码、资源ID及匿名性能数值。
不得把GPU提交时间标成GPU执行时间；公开性能指标必须包含设备/分辨率/效果/测试时长及人脸覆盖率。
纯离线资源处理不等于整个第三方依赖链没有网络行为；固定版本网络审计仍待完成。

## 升级要求

新增API尽量保持兼容；删改公开类型、方法签名、参数语义和资源默认行为必须提供迁移说明。
已加入发布AAR的JVM公开签名基线检查，独立消费者Release/R8已有构建证据；Kotlin元数据、native ABI、行为兼容和回滚演练仍需单独验证。
