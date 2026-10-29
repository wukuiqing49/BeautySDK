# BeautySDK 授权服务与发行工具

Node.js 内置 crypto/http/fs 实现，无 npm 运行时依赖。此独立服务提供租约交换 API；管理员通过发行方机器上的 CLI 签发、撤销、恢复、续期。另可使用相邻 BeautyStickerWorkshop 项目的登录后台签发与下发，私钥仅保存在其服务端运行时，不进入浏览器。两种服务的登记源独立，应选择一套作为客户激活源。

## 1. 创建发行方目录和密钥

下面的路径是示例，部署前换成自己的路径和域名。目录预先创建并限制系统 ACL；Windows 的文件 mode 不能替代 NTFS ACL。不得把目录放在网站静态根目录或 Git 仓库。

```powershell
node services/license/admin.mjs keygen C:/issuer/private.pem C:/issuer/public.txt
node services/license/admin.mjs config C:/issuer/private.pem issuer-2026 https://license.example.com/v1/lease C:/issuer/license-public.json
```

keygen 生成 RSA3072；输出文件禁止覆盖。`license-public.json` 只有 HTTPS 端点和公钥，可以用于发行构建。private.pem 永远不进入 APK、客户包、CMS 浏览器或源码；CMS 签发模式仅允许服务端私密存储。

## 2. 签发客户文件

准备 claims.json，字段必须完整：

```json
{
  "licenseId": "customer-app-001",
  "packageName": "com.customer.app",
  "certificates": ["实际应用签名证书的64位小写SHA256十六进制"],
  "sdkMajor": 0,
  "features": ["BEAUTY", "FILTER", "FACE", "STICKER"],
  "issuedAt": 1793232000000,
  "expiresAt": 0
}
```

签名证书可用 Android build-tools 的 `apksigner verify --print-certs customer.apk` 查看，去掉冒号并转小写。时间使用实际 UTC epoch 毫秒；上面的时间仅示例，填写当前时间。`expiresAt=0` 表示授权文件本身无固定到期，但仍必须在线激活、定期换租约，服务登记的合同到期时间照常生效。固定到期的授权文件不能通过只改服务登记延长超过文件期限。

```powershell
node services/license/admin.mjs issue C:/issuer/registry.json C:/issuer/claims.json C:/issuer/private.pem issuer-2026 C:/issuer/beautysdk.license
node services/license/admin.mjs renew C:/issuer/registry.json customer-app-001 1824768000000
node services/license/admin.mjs revoke C:/issuer/registry.json customer-app-001
node services/license/admin.mjs resume C:/issuer/registry.json customer-app-001
```

renew 的时间也必须替换为实际未来合同截止时间，操作同时恢复 active；resume 仅恢复状态，不改变期限。签发、续期后保留登记表备份。签发以文件锁串行化，登记表通过临时文件替换；意外中断可能留下 lock 或已经写出的授权文件，先检查登记表和输出是否一致再人工恢复，不能盲目覆盖。重复 licenseId 拒绝签发；需要换包名、签名或授权文件能力上限时签发新 ID 并撤销旧 ID。

登记表默认 leaseMs=604800000（7 天）、refreshMs=86400000（24 小时）。可在受控维护时修改为更短值，范围均不少于 60 秒；刷新不得晚于租约到期。登记的 features 只能收窄授权文件中的能力。更新登记表应原子替换，避免服务读到半份 JSON；读入失败返回 503，客户端按网络失败保留有效缓存，不伪造撤销。

## 3. 启动与 HTTPS

```powershell
$env:BEAUTY_LICENSE_PRIVATE_KEY='C:/issuer/private.pem'
$env:BEAUTY_LICENSE_REGISTRY='C:/issuer/registry.json'
$env:BEAUTY_LICENSE_KEY_ID='issuer-2026'
$env:BEAUTY_LICENSE_PORT='8091'
node services/license/server.mjs
```

服务只监听 `127.0.0.1`，由发行方配置可信 HTTPS 反向代理转发 `/v1/lease`。SDK 拒绝 HTTP 及重定向，校验证书使用 Android 系统信任链。服务需要进程守护、正确系统时间、密钥备份和代理流量控制；这些部署操作本轮未执行。

POST application/json 请求成功含已签名租约；撤销等业务拒绝也是 HTTP200 的签名 DENIED。400/503/网络异常不是授权撤销。请求上限48KiB、并发32、每 socket IP 每分钟120次。反向代理后默认所有请求共用代理 IP 限额，规模化前需在可信代理配置真实客户端限流并调整服务策略；不直接信任任意 X-Forwarded-For。无设备额度、远程设备证明、收费或 SLA 承诺。

轮换时可设 `BEAUTY_LICENSE_EXTRA_KEYS` 指向 JSON 公钥映射 `{ "old-key-id": "SPKI-DER-BASE64" }`，用于验证旧授权文件。新租约总用当前私钥签署；客户 SDK 必须预先信任此 keyId。

## 4. 验证

```powershell
node --test services/license/*.test.mjs
```

测试密钥仅临时生成，不是生产发行配置。Android 的跨语言夹具由 `test-fixtures.mjs` 在 build 目录生成，签名私钥不落盘。集成协议、缓存策略及限制见 [授权文档](../../doc/LICENSING.md)。
