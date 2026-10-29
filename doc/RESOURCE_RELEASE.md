# SDK / CMS 资源发布与回滚

适用：个人维护的BeautyStickerWorkshop和Android BeautySDK。资源本地制作、校验和发布，不增加服务端账号，不接直播厂商。本轮不自动push或部署网站。

## 唯一来源

- 参数ID/分类/范围/步长/中性值/中英文名称：CMS `protocol/parameter-contract.json`，生成Web模块及SDK ParameterContract.kt。算法执行仍在SDK白名单映射中，修改契约不等于新增算法。
- 展示顺序：交付defaults.categoryOrder；导出端写入对应library.order。导入不覆盖用户配置，明确应用后更新顺序、固定语言及参数。
- 展示语言：defaults.localeMode/defaultLocale；缺失翻译按library.defaultLocale回退。当前完整文字表是zh-CN/en；脚本语言优先于基础语言，不将繁体宿主隐式降为不适用的基础zh翻译。
- 手势：catalog V2 gestures描述与library.gestureResources一致，包/封面均固定摘要。V1继续可读，旧catalog API只返回普通资源；完整目录使用unifiedCatalog。

## 一次生成

在CMS目录运行（SDK路径根据实际位置指定）：

```powershell
node scripts/generate-contract.mjs --check --sdk ../BeautySDK
npm test
npm run release -- --sdk ../BeautySDK
npm run build
```

输出build/releases/r-<内容摘要>/：resource_delivery.zip、beauty_resources目录、capabilities.json、release.json。相同输入及部署base产生同一版本；已存在快照必须通过摘要检查，不能覆盖篡改版本。`--base https://你的站点/`修改发布根地址。

Android同步三个内容文件后最后写resource-release.json；中途被终止会由preBuild的verifyBuiltinResources拒绝不一致APK。发布前不要同时运行资源同步和Gradle构建。旧资源保存在SDK build/release-backups，不自动清除。

之后构建APK并按测试计划验证。网站dist仅是本地产物，仍须发布到配置的站点；网络URL在网站部署前不保证可访问。线上release.json与APK marker应显示同一release ID。

## 回滚

```powershell
npm run release -- --restore build/releases/<历史版本> --sdk ../BeautySDK --dist
```

命令先完整核对摘要和ZIP结构，再恢复对应资源到Android与dist。随后直接部署当前dist并重新构建/安装APK；不要再运行默认npm run build，否则会从当前素材重新生成最新版本。用户自己导入的资源、参数和选择保持独立，回退内置资源并不自动覆盖用户状态。

浏览器“更多→版本备份”适用于自己的导出方案：最多5版本/64MiB，保存失败保留原记录，重复摘要仅重命名；下载历史ZIP后，在工坊或Android重新导入并明确应用。浏览器清理会移除本机备份，长期版本应下载到电脑保存。

## 能力与验收

每个发布附capabilities.json，列出资源要求与平台状态：Android已实现但真人质量未全面验收、Web为插画/模拟触发、iOS完整库未验收。能力描述不是效果质量保证。

跨端16组有效/无效清单夹具必须一致。联网手势可用注入ResourceTransport进行确定性校验；真实HTTPS部署属于独立验收。完整库上限99普通资源/8手势组/32MiB/2000文件保持不变。

三组稳定性工具：CameraStabilitySoakDeviceTest，原图 / 人脸及手势检测 / 检测加基础美颜，`-e soakSeconds 600`可扩展每组10分钟。记录预览帧率、每秒采样CPU提交P50/P95、PSS及Android热状态；不记录图像，不代表GPU耗时/端到端延迟。场景不受控时必须记录无人脸等限制，不将短测作为热稳态或真人画质验收。
