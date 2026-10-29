# lib_beauty_resources

独立Android资源管理库，api依赖lib_beauty，不依赖Demo、CameraX、直播厂商或WKQ。namespace为com.beautysdk.resources，minSdk 24。

- ResourceArchive：统一V1单项/套装严格解析，旧V1～V4执行内容复用校验。
- BeautyResourceManager：内置/本地/网络同一安装器、SHA-256固定版本、目录/封面离线缓存、取消进度、引用保护。
- BeautyResourceDefaults / StateStore：JSON默认值、语言、分类排序、参数和选择持久化。
- BeautyEffectComposer：作用域覆盖、槽位互斥、套装拆分、配置快照。

接入、格式及限制见[统一资源手册](../../resources/unified/v1/README.md)。资源IO仅在后台工作线程；宿主把prepared.settings发布给公共BeautySession或Texture处理器。旧GPU使用完成后关闭旧快照；每帧不读包、不下载、不解压。

网络HTTP可注入ResourceTransport，也可使用内置HTTPS连接；宿主决定网络权限与排队策略。当前不提供HTTP断点续传、共享下载请求或后台系统任务。


## Web 完整资源库导入

后台线程调用 `manager.installDelivery(input)`。接收新beauty_resources外层英文分类目录和旧根目录包，包含library.json、catalog.json、defaults.json及资源文件，最多99常规资源、8手势组、2000文件、32MiB。先校验所有资源、封面、摘要和默认槽位引用，再安装；失败撤销本批新增安装，重复导入复用。返回 `ResourceDeliveryResult`，包含资源、解析后的默认值及原 defaults JSON。

此API不启用效果、不改用户状态、不下载网络内容。宿主在用户明确采用后，保存返回的 defaults JSON，通过 `BeautyEffectComposer.prepare(result.defaults.state)` 准备，再将配置交给视频管线。Demo提供独立“应用导入资源库的默认配置”按钮。原单项/套装仍使用 install，原解析限额不放宽。

Android当前主APK内置50份普通资源与4组手势。统一发布命令在CMS仓库执行：`node scripts/release-library.mjs --sdk <BeautySDK目录>`，同步ZIP、清单、默认值及发布摘要。Android preBuild校验四者一致；旧修订保留，网站上线仍需独立部署。旧sync_web_resources脚本只面向旧平铺目录，不再用于主APK完整库发布。


## APK assets 内置加载

完整ZIP放在 `app/src/main/assets/resource_delivery.zip`，后台调用 `manager.installDeliveryFromAssets(context.assets)`；或把解压后的beauty_resources文件夹放进assets，调用 `manager.installDeliveryDirectoryFromAssets(context.assets)`。两者均支持自定义asset相对路径和ResourceCancellation，复用完整校验/回滚，不联网、不自动启用，返回ResourceDeliveryResult。目录必须保留全部清单、package.zip及展开副本。重复加载按摘要复用。

完整示例见[Android SDK 使用说明](../../doc/ANDROID_SDK_USAGE.md)，包含依赖、两种内置方式、分类/预览/滑杆、默认与持久化、GPUPixel视频纹理接入和生命周期。

## 在线目录 V2

`manager.unifiedCatalog(url)` 返回普通 resources 与 gestures；旧 `catalog(url)` 继续只返回普通资源。`downloadGesture(entry)` 按目录摘要、规则与PNG预算校验，再复用完整ZIP的安装器；仅安装，宿主显式 `prepareGestureSelection(hashes)` 后交给播放器。`gesturePreview(entry)` 提供摘要固定的离线封面缓存。V1仍可读取；新CMS含手势时生成V2。

参数白名单由CMS `protocol/parameter-contract.json` 生成，`scripts/generate-contract.mjs --check --sdk <SDK目录>` 检测两端漂移。分类显示顺序以已应用defaults.categoryOrder为准；library中的order由导出端同一配置生成。运行时语言取defaults的FIXED/FOLLOW_HOST，library.defaultLocale只负责缺失翻译回退。
