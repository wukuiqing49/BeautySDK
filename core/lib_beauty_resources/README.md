# lib_beauty_resources

独立Android资源管理库，api依赖lib_beauty，不依赖Demo、CameraX、直播厂商或WKQ。namespace为com.beautysdk.resources，minSdk 24。

- ResourceArchive：统一V1单项/套装严格解析，旧V1～V4执行内容复用校验。
- BeautyResourceManager：内置/本地/网络同一安装器、SHA-256固定版本、目录/封面离线缓存、取消进度、引用保护。
- BeautyResourceDefaults / StateStore：JSON默认值、语言、分类排序、参数和选择持久化。
- BeautyEffectComposer：作用域覆盖、槽位互斥、套装拆分、配置快照。

接入、格式及限制见[统一资源手册](../../resources/unified/v1/README.md)。资源IO仅在后台工作线程；宿主把prepared.settings发布给公共BeautySession或Texture处理器。旧GPU使用完成后关闭旧快照；每帧不读包、不下载、不解压。

网络HTTP可注入ResourceTransport，也可使用内置HTTPS连接；宿主决定网络权限与排队策略。当前不提供HTTP断点续传、共享下载请求或后台系统任务。


## Web 完整资源库导入

后台线程调用 `manager.installDelivery(input)`。接收 `catalog.json + defaults.json + ZIP + PNG` 的扁平资源库，最多99资源、200文件、32MiB。先校验所有资源、封面、摘要和默认槽位引用，再安装；失败撤销本批新增安装，重复导入复用。返回 `ResourceDeliveryResult`，包含资源、解析后的默认值及原 defaults JSON。

此API不启用效果、不改用户状态、不下载网络内容。宿主在用户明确采用后，保存返回的 defaults JSON，通过 `BeautyEffectComposer.prepare(result.defaults.state)` 准备，再将配置交给视频管线。Demo提供独立“应用导入资源库的默认配置”按钮。原单项/套装仍使用 install，原解析限额不放宽。

Android当前内置目录与Web27份资源一致，同步命令：`node scripts/sync_web_resources.mjs <Web资源目录>`。旧修订保留；Demo按摘要每个内置版本仅自动安装一次，不会在删除后强制装回。网站发布独立于APK安装。
