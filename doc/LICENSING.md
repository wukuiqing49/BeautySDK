# Android 商业授权（0.2.0）

本轮实现的是应用级授权：包名、当前 APK 签名证书 SHA-256、SDK 主版本及功能集合。不是用户账号、设备限额或支付系统；同一合法签名应用的所有安装共用该授权。CMS 新增受登录保护的授权管理，可在服务端签发、登记和下发；私钥不进入浏览器。CMS 的合同登记存于服务端数据库。

## 请求时机与离线行为

| 场景 | SDK 行为 |
| --- | --- |
| 第一次初始化，没有可验证缓存 | 后台联网激活；成功前不启用效果 |
| 已有有效签名缓存 | 本地验签后启用；刷新未到期不请求 |
| 正常刷新 | 默认每 24 小时一次，进程内定时检查及宿主 `refreshIfDue()` 均遵守同一计划 |
| 网络错误、超时、HTTP 非 200、无效响应 | 保留尚未过期的缓存；1 分钟起指数退避，最多 6 小时，加最多 15 秒抖动 |
| 重启或重复初始化 | 复用持久化刷新/退避计划；请求合并，不每次启动访问接口 |
| 有效缓存到期 | 停用效果、输出原帧；再次取得有效租约后恢复 |
| 收到已验签的撤销/过期/未登记结果 | 立即停用并保存拒绝状态；不能回落到旧 ACTIVE 缓存 |

默认租约最长 7 天；服务端可缩短，不得超过协议上限。撤销需要客户端取得新响应才能生效：正常联网通常在下一次刷新时发现，持续离线最坏到当前租约到期。不能同时保证长期离线使用与即时撤销。进程退出后 SDK 不创建系统后台服务；下次初始化执行到期检查。首次激活失败也会遵守已保存的退避。

渲染、分析及手势线程只检查内存权限和单调时钟，不做网络、磁盘或签名运算。授权 worker 每分钟检查一次计划，检查不等于发请求。有效期边界由内存检查即时判断，不依赖定时器及时唤醒。

## 客户集成

```kotlin
implementation("com.beautysdk:beautysdk:0.2.0")
```

将发行方提供的原始 `beautysdk.license` 放到宿主 `app/src/main/assets/`。不要重新格式化或改写文件：服务登记还绑定完整文件 SHA-256。宿主显式声明 `android.permission.INTERNET`，发行 SDK 本身不自动声明权限。

在 Application 或其他进程初始化入口调用，勿逐帧调用：

```kotlin
import com.beautysdk.BeautySdk
import com.beautysdk.license.BeautyLicensing
import com.beautysdk.license.LicensedFeature

BeautySdk.initialize(applicationContext, "beautysdk.license") // 异步，不阻塞 UI
val state = BeautyLicensing.state()
val canUseBeauty = BeautyLicensing.allows(LicensedFeature.BEAUTY)
// 可在网络恢复或回到前台时调用；不会绕过刷新/退避计划。
BeautyLicensing.refreshIfDue()
```

`BeautyLicensing.addListener(listener)` 仅通知后续状态变化，注册后自行读一次 `state()`；回调在 worker 上，UI 必须切主线程。页面销毁时 `removeListener`。状态包含 DEVELOPMENT、NOT_INITIALIZED、ACTIVATION_REQUIRED、ACTIVE、EXPIRED、REVOKED、CLOCK_ERROR、CONFIGURATION_ERROR，以及功能集合、到期时间和可用的错误码。

客户不能从运行时注入可信公钥或关闭校验。公钥与 HTTPS 地址在发行方构建时固化。核心的 `LicenseTransport` 可接宿主网络实现，但所有成功响应仍必须通过 SDK 验签和请求 nonce 校验。

不要仅靠 UI 隐藏按钮。SDK 已在 Session、内置渲染器、分析运行时、手势播放器及资源应用处检查权限。设置中任一需要的功能未获授权时，内置渲染整帧旁路；资源应用会抛出 `LicenseException`，`code=LICENSE_FEATURE_UNAVAILABLE`，`missingFeatures` 给出缺少的权限。安装、浏览资源不等于启用效果，也不需要授权。自定义 `BeautyRenderer` 中宿主自己实现的算法不受本 SDK 自动控制。

## 功能权限

| 权限 | 内容 |
| --- | --- |
| BEAUTY | 磨皮、美白、红润 |
| FILTER | 滤镜、亮度、对比度、饱和度、锐化 |
| FACE | 人脸分析；美型、美妆、贴纸还需要此权限 |
| RESHAPE | 美型 |
| MAKEUP | 美妆及图片睫毛 |
| STICKER | 人脸贴纸 |
| BACKGROUND | 分割、虚拟背景 |
| MOTION | 运镜（依赖人脸的配置另需 FACE） |
| GESTURE | 手势分析和动画 |

功能授权不代表功能已通过商业质量验收；实验能力的限制仍见 [产品状态](PRODUCT_STATUS.md)。

## 发行和运维

见 [服务端及签发工具说明](../services/license/README.md)。发行方在自己的机器/服务器保管 RSA 私钥；APK 仅含公钥。发行构建：

```powershell
.\gradlew.bat publishBeautySdkToLocalBuildRepository '-PbeautyLicenseConfig=C:/issuer/license-public.json'
python scripts/package_candidate.py --license-config C:/issuer/license-public.json --output build/beautysdk-commercial-candidate.zip
```

打包参数另见 `python scripts/package_candidate.py --help`。每个 AAR 的 provenance 记录相同公钥配置，打包器拒绝未配置或与指定配置不一致的制品。未配置时可以完成本地编译，但 Release 必须拒绝授权，不能作为可用商业包发出。不得拿测试临时公钥当正式发行公钥。

私钥轮换先发布同时信任旧/新公钥的 SDK，再切服务签发密钥；服务的额外公钥配置允许验证旧授权文件。只信任旧公钥的已发布 SDK 无法接受新密钥签名的租约，必须安排迁移。最多 8 个公钥。不提供远程随意替换信任根。

Google Play App Signing 场景绑定应用签名证书，不是上传证书。证书轮换时把预计使用的签名证书加入授权文件；校验使用当前 APK 签名集合，不靠历史旧证书放行。本方案不限制安装数量，服务收到的包名/签名字段也不是硬件远程证明。

## 签名与缓存边界

授权文件和在线租约分别使用 `BeautySDK/license/grant/v1\n`、`BeautySDK/license/lease/v1\n` 域，签署其后原始 UTF-8 payload 字符串；RSA 2048～4096、SHA-256/PKCS#1 v1.5。外层字段为 version/keyId/payload/signature，签名为小写十六进制。租约绑定完整授权文件摘要、随机 nonce、功能集合及有效期，不能跨应用授权替换或把资源包签名当客户授权。

缓存位于应用私有 noBackupFilesDir，以 AtomicFile 保存。每次恢复重新验签；运行中按单调时钟计算剩余有效期，持久化时间高水位用于检测明显回拨。缓存写失败会尝试清除旧缓存，拒绝结果在当前进程立即生效。若底层存储同时无法写入和删除，无法保证下次进程看不到旧文件。

客户端不是可信硬件：Root、hook、修改 SDK、回滚整个私有目录、重编译公开源码等不在此防护保证内。本地时间高水位也不能彻底防止离线回滚。需要更强控制时再接设备证明和更短租约，不能宣称不可破解。当前不采集人脸、视频、硬件设备 ID；请求仅含授权文件、应用身份和随机 nonce。服务默认不记录请求正文，但 HTTPS 代理日志仍需发行方管理。

## 迁移与验证范围

0.1.0 → 0.2.0 是授权行为变化：本地源码 Debug 处于 DEVELOPMENT；发布 Maven 使用 Release，即使宿主是 Debug，也必须初始化并持有合法授权。没有运行时开发模式开关。独立示例会展示授权状态，收到有效权限后再处理样例帧。

当前实现及 JVM/Node 验证不能代替正式 HTTPS 部署、正式密钥签发、客户签名 APK 真机激活、断网跨重启、Release GPU/R8 及持续性能验收。最新执行证据见 [测试报告](testing/TEST_REPORT.md)。本轮不部署服务，不生成正式密钥，不宣称已能对外售卖。


## CMS 管理入口（2026-10-29 增量）

独立项目 `BeautyStickerWorkshop` 的 `/admin#licenses` 新增生成、下载、一次性领取链接、续期、撤销与恢复。服务端 `/v1/lease` 使用同一数据库登记，协议保持不变。签名私钥保存在 CMS 服务端运行时密钥，本地验证配置在其忽略的 `.data` 目录；页面只见签发器状态和公钥配置，生成授权不改写 SDK 的受信公钥。

管理员需填写实际应用包名与发行证书 SHA-256。后台导出的公共配置作为 `-PbeautyLicenseConfig=...` 输入；必须先部署真实 HTTPS 地址并使用匹配信任配置构建发行 SDK。默认本地签发器仅供验证，未部署、未写入正式 SDK。原 CLI 的 JSON 登记源与 CMS 数据库独立，不会自动同步；应选定一套作为客户实际激活服务。

本轮验证为 CMS/API/workerd 与现有 Node 协议兼容，不涉及 Android 算法或授权运行时修改，也未执行客户真机联网激活。完整操作步骤见相邻 CMS 项目的 `ADMIN_DEPLOYMENT.md`。
