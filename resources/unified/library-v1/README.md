# 分类资源库与展示配置 V1

完整 ZIP 下载到电脑本地；根目录含 library.json、catalog.json、defaults.json。目录为“分类中文名/资源名称__稳定ID/”，每个资源含 manifest.json、effect.json、preview.png、执行图片及 package.zip。文件夹名不参与效果映射；资源ID、版本、参数ID稳定。

## 三份 JSON

- library.json：libraryVersion=1；defaultLocale为文字回退语言；localeMode跟随默认配置；localizations是语言→文字键→文字。categories按order排序，含id/nameKey/effects；效果含id/nameKey/parameters。resources提供id/revision/category/subcategory/name/localizations/directory/package/preview/manifest相对路径。
- catalog.json：保留网络目录V1协议，URL更新为分类路径，摘要/字节数校验package.zip。
- defaults.json：默认搭配、默认参数覆盖、语言模式与开关；安装不自动启用，用户显式应用。

## 属性与多语言

当前15个已支持的美颜/美型/美妆/调色参数使用float+slider，参数id直接绑定BeautyParameters白名单键，包含nameKey/min/max/step/default。新控件类型须先扩展SDK，不执行任意字段或反射。范围必须在SDK范围内，步长大于0且最多10000步；默认程度必须在声明范围内。默认程度取交付默认覆盖，否则取SDK中性值；单项素材本身的效果值仍在其effect.json。

分类、效果和属性文字由根localizations提供，导出提供zh-CN和en。资源名称翻译沿用单项localizations（name/description），可在总清单覆盖展示名。完整语言→基础语言→默认语言回退；默认语言缺键拒绝，其他语言允许逐键回退。切换语言不改变参数ID和数值。

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
  "directory": "手势动画/比耶星光__victory_sparkles/",
  "package": "手势动画/比耶星光__victory_sparkles/package.zip",
  "preview": "手势动画/比耶星光__victory_sparkles/preview.png",
  "manifest": "手势动画/比耶星光__victory_sparkles/gestures.json",
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
