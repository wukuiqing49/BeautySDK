# 完整资源包发行签名 v1（新增实现，尚未运行验证）

CMS `scripts/sign-release.mjs` 先校验不可变发布目录，再用发行方提供的 RSA 2048～4096 位私钥签名。
采用 SHA256withRSA / PKCS#1 v1.5，固定域前缀 `BeautySDK/resource-delivery/v1\n` 加 payload 原始 UTF-8 字节。
envelope 字段为 version=1、keyId、payload（标准 Base64）、signature（标准 Base64）。
payload 为 releaseId、bytes、sha256，绑定整个 ZIP 原始字节；验签后仍经过现有 ZIP/JSON/路径/预算/能力校验。

```text
node scripts/sign-release.mjs RELEASE_DIR PRIVATE_KEY_PEM KEY_ID OUTPUT_JSON
```

此命令目前只提供源码，未运行；不自动生成正式密钥。私钥应保存在仓库之外，不能提交、放进CMS网页、APK、示例或发布目录。
输出文件禁止覆盖，签名文件可以与ZIP一同分发；公钥必须由宿主预置或通过独立可信渠道更新，绝不能信任资源包携带的公钥。

```kotlin
// 放在后台IO线程；公钥为X.509 SubjectPublicKeyInfo DER字节。
val verifier = SignedResourceDelivery(mapOf("publisher-2026" to trustedPublicKeyDer))
val receipt = verifier.install(manager, zipInput, signatureInput, expectedReleaseId)
// 成功只代表已验证并安装，宿主仍需显式启用效果。
```

旧无签名本地导入接口保持兼容。要求签名的产品必须只从此入口接受远端完整包，失败不得降级到普通 installDelivery。
同一ZIP中的目录引用受ZIP摘要保护；后续单项在线下载并未自动加入签名策略，不可宣称所有远端入口都已验签。
新入口不提供版本新鲜度、可信时间、撤销或防回滚；宿主可固定 expectedReleaseId，或自行维护经验证的允许版本集合。
keyId 只用于选择预置信任公钥，不是客户授权。签名不等于加密、不防止复制，也不替代素材版权审核。

待验证：Node/Android交叉验签、错误密钥、修改payload/ZIP、超长输入、未知字段、错误类型、取消、流关闭，以及验证失败不产生安装副作用。
# Demo发行源接入（源码待验证）

资源中心新增“下载已签名发行资源”。宿主在 `app/src/main/assets/official-resources.json` 提供对象，字段固定为 `version`（整数1）、`packageUrl`（HTTPS完整包地址）、`envelopeUrl`（同源HTTPS签名文件地址）、`releaseId`（固定发行ID）和 `trustedKeys`（keyId到Base64编码X509 SPKI DER公钥的映射）。不放私钥，不从线上目录接受新信任公钥。仓库不附带假生产配置；缺少配置明确报 `OFFICIAL_SOURCE_NOT_CONFIGURED`。

公共入口 `SignedResourceSource.install(manager, cancel)` 在IO线程下载，禁止HTTP跳转，连接/读取超时分别10/15秒，复用签名、大小、摘要和资源包校验；成功只安装，默认配置由用户另行采用。取消在检查点生效，阻塞网络读取最多等待读取超时。普通目录下载不因此获得发行方认证。尚未运行网络与设备回归。
