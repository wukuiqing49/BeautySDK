# 分类资源库与展示配置 V1

完整 ZIP 下载到电脑本地；唯一外层目录为 beauty_resources/，其中含 library.json、catalog.json、defaults.json。内部目录为“英文分类/稳定ID/”，每个资源含 manifest.json、effect.json、preview.png、执行图片及 package.zip。资源展示名只存JSON，不用于生成路径；分类路径为 beauty/reshape/makeup/filter/sticker/background/motion/theme/gesture。文件夹名不参与效果映射；资源ID、版本、参数ID稳定。

## 三份 JSON

- library.json：libraryVersion=1；defaultLocale为文字回退语言；localeMode跟随默认配置；localizations是语言→文字键→文字。categories按order排序，含id/nameKey/effects；效果含id/nameKey/parameters。resources提供id/revision/category/subcategory/name/localizations/directory/package/preview/manifest相对路径。
- catalog.json：无手势使用网络目录V1；含手势使用V2，新增gestures数组，普通资源结构不变。URL使用分类路径，摘要/字节数校验package.zip。
- defaults.json：默认搭配、默认参数覆盖、语言模式与开关；安装不自动启用，用户显式应用。

## 属性与多语言

当前15个已支持的美颜/美型/美妆/调色参数使用float+slider，参数id直接绑定BeautyParameters白名单键，包含nameKey/min/max/step/default。新控件类型须先扩展SDK，不执行任意字段或反射。范围必须在SDK范围内，步长大于0且最多10000步；默认程度必须在声明范围内。默认程度取交付默认覆盖，否则取SDK中性值；单项素材本身的效果值仍在其effect.json。

分类、效果和属性文字由根localizations提供，导出提供zh-CN和en。资源名称翻译沿用单项localizations（name/description），可在总清单覆盖展示名。完整语言→脚本语言（如zh-Hant）/适用基础语言→默认语言回退；默认语言缺键拒绝，其他语言允许逐键回退。切换语言不改变参数ID和数值。

## Android消费

ResourceDeliveryPack.read或BeautyResourceManager.installDelivery返回library。BeautyResourceLibrary提供categories/resources、text、resourceName、parameters及updateParameters(state,values)。后者校验JSON声明及SDK范围，只合并指定参数；效果选择和资源准备仍走BeautyEffectComposer。宿主将组合设置提交到原视频管线，不在视频回调解析文件。

Demo保存导入清单，资源页与相机返回刷新后读取；分类排序/名称、卡片名称和顺序来自清单，封面来自校验过的资源；相机“调节”按清单创建滑杆，并支持恢复参数默认值。用户状态独立保存在user-state.json。未导入新清单时保留旧控件，旧平铺交付包继续支持导入；导入旧完整包后切回旧展示协议。

package.zip保留单项原始摘要，是安装源；旁边展开文件用于本地查看。两份内容必须一致，不能只修改展开的图片或effect.json后直接导入；请在制作端重新生成包。导入拒绝路径穿越、重复路径、篡改、额外文件、缺失引用；完整归档仍限制32MiB，最多2000文件，最多99项资源，单项原规则不放宽。

本轮实现与设备验证范围为Android。iOS尚未新增此完整交付清单读取能力；单项资源旧协议不变。无线上发布、直播厂商或持续性能结论。

## V2：手势动画分类（2026-10-28）

带手势素材的完整库使用 libraryVersion=2，兼容读取 V1 与无 library.json 的旧平铺包。根 categories 新增 GESTURE，nameKey=category.GESTURE，effects=[]；常规八类及 BeautySettings 槽位不变。根 gestureResources 描述独立手势包，最多8组，每组1～7条规则：

```json
{
  "id": "victory_sparkles", "revision": 1, "category": "GESTURE",
  "name": "比耶星光", "localizations": {"en":{"name":"Victory Sparkles","description":""}},
  "directory": "gesture/victory_sparkles/",
  "package": "gesture/victory_sparkles/package.zip",
  "preview": "gesture/victory_sparkles/preview.png",
  "manifest": "gesture/victory_sparkles/gestures.json",
  "sha256": "<原始ZIP的64位摘要>", "bytes": 244253,
  "previewSha256": "<预览PNG的64位摘要>", "defaultEnabled": false,
  "rules": ["与gestures.json完全一致的规则对象，详见resources/gestures/v1"]
}
```

文件夹同时含 package.zip、preview.png 和展开的 gestures.json/asset_N.png。顶层规则是展示与默认绑定的依据，必须与执行包一致；包含 gesture/kind/image/frameWidth/frameHeight/columns/frameCount/fps/durationMs/position/width/id。不是美颜程度参数，不写进 defaults.parameters。分类、资源名称支持语言回退；数字、路径和稳定ID不随语言改变。

完整库固定带比耶星光、点赞彩纸、开掌光波、爱意漂浮4组内置动画，默认均关闭。已保存的工作台绑定另作为默认启用组随方案导出；未保存编辑不导出。Web 导入完整库恢复手势素材及默认绑定，导出前等待草稿恢复/保存完成。四组内置分别36/44/32/40帧，192×192透明PNG图集、20FPS。

Android `library.gestureCategory/gestureResources` 与常规 `categories/resources` 分开，保留原八类枚举兼容性。管理器提供 listGestureResources/gesturePreview/prepareGestureSelection/deleteGestureResource。导入先校验所有摘要、规则、展开副本、默认冲突和预算，再安装；失败撤销本批新增项。默认绑定不得重复规则ID或触发手势，最多7条，组合图集预算8Mi像素。

Demo资源中心第9类显示预览、名称、触发手势和启用状态；点击显式选择，再点关闭，长按删除。多组不同触发可共存，重复触发替换冲突整组。选择独立持久化于 gesture-selection.json，重开恢复；采用交付默认配置才启用 defaultEnabled 组。旧独立手势包仍可暂存/应用/清除。选择不自动开启识别；相机检测开关遵循宿主当前设置，播放器收到稳定触发后合成视频。

catalog.json 仍仅描述50份常规资源。新增手势通过完整ZIP本地导入，不支持该目录的独立网络下载；不是声称9类均已上线。完整包本地输出见制作工程 build/resource_delivery.zip、build/library.json，可用 `node scripts/export-library.mjs` 重建。iOS完整库与手势分类消费尚未实现；真人识别准确率、动态观感、持续性能及厂商推流仍需单独验收。


## 英文外层目录与兼容读取（2026-10-28）

新导出完整ZIP的所有条目都以 `beauty_resources/` 开头；`library.json` 中的 directory/package/preview/manifest 仍相对于清单所在文件夹。路径只由英文分类与稳定ID生成，修改展示名或语言不会改变路径。中文等Unicode展示字段保持有效，并非删除中文翻译。

Web `readDelivery` 与 Android `ResourceDeliveryPack.read` / `BeautyResourceManager.installDelivery` 自动去掉固定外层目录；继续接受旧根目录JSON、旧中文分类目录及旧平铺包。拒绝固定外层和根文件混装、未知文件、篡改以及路径穿越。单项ZIP与独立手势ZIP格式不变。Demo导入入口识别两种 catalog.json 位置，不需要用户先解压。

Android接入：在宿主IO线程调用 `val result = manager.installDelivery(inputStream)`，从 `result.library` 获取分类、文字和属性定义，再由宿主UI展示。安装不启用效果；Demo显式采用默认配置后组合 `result.defaults.state`，并将设置交给原BeautySession/渲染管线。库本身不依赖Demo UI。当前已验证Android完整ZIP导入、JSON美颜控件和效果配置；iOS完整库消费仍未实现。

网络部署时，导出设置的 baseURL 对应 `beauty_resources` 内部内容所在地址：把文件夹内的JSON和分类目录原样上传到该地址。若保留外层文件夹上传，则baseURL也应包含 `/beauty_resources/`，不要额外叠加或遗漏一级。catalog.json V2覆盖常规资源和手势；Android使用unifiedCatalog读取，download/downloadGesture分别安装。V1旧目录继续兼容。


Android APK内置资源新增 `BeautyResourceManager.installDeliveryFromAssets(assets, assetPath)` 和 `installDeliveryDirectoryFromAssets(assets, directory)`。默认分别读取resource_delivery.zip和beauty_resources；均只安装并返回完整清单，后台调用、可取消、不联网。目录模式保留package.zip与展开文件，完整说明见[Android SDK使用说明](../../../doc/ANDROID_SDK_USAGE.md)。


## 2026-10-29 契约与发布

分类默认排序以defaults.categoryOrder为权威；制作端将同一顺序写入library.order。导入只安装，明确应用默认配置后更新宿主排序/语言/数值。defaultLocale在library中用于翻译回退，在defaults中用于FIXED显示语言；导出en使用en回退，其他未提供完整文字表的语言回退zh-CN。

Web与Android共用16组library-cases.json案例；拒绝未知字段、非法localeMode、空参数、错误范围和路径。既有V1/V2库仍可读。参数定义从CMS parameter-contract.json生成双方代码，解析器不接受任意新增算法ID。

统一发布与回滚见[资源发布说明](../../../doc/RESOURCE_RELEASE.md)。
