# 统一资源协议 V1（2026-10-27）

实现入口：独立Web的resource-studio.html；Android core/lib_beauty_resources；iOS UnifiedResources / UnifiedResourceManager / MetalResourceRenderer。iOS源代码当前Windows未编译验证，Metal仍明确拒绝未实现的美型/口红/腮红及无图片程序化睫毛。

## 包结构

单项：manifest.json（本协议元数据）、preview.png（列表封面）、effect.json（effectVersion/effectType/payload）、所需PNG。第一版固定根目录文件，最多40项；禁止目录、脚本、绝对/上级路径与大小写重复名。总体32MiB，单JSON64KiB，单PNG4MiB；旧执行payload仍受原16MiB/像素等预算限制。

- PARAMETERS：payload.parameters仅写所属分类/部位的参数，集合必须等于scope。
- LEGACY：payload是原V1～V4 manifest，其他文件原样保留；先重建并执行原严格解析器，再只应用scope。图片睫毛归MAKEUP/EYELASH，scope仅makeup.eyelashes。
- THEME：payload.resources是固定一层resource_N.zip及SHA-256列表；子包槽位不得冲突，不允许嵌套主题，累计PNG像素含封面最多8,388,608。包声明能力必须等于子包能力并集加theme.v1。

单项/主题外层均packageVersion=1；新增协议与旧schemaVersion不同。槽位为分类小写，美妆和贴纸追加子分类小写，如makeup.eyelash、sticker.hat。BEAUTY/LEGACY表示旧综合预设，不等同单部位单项；自动导入旧包使用legacy_前缀避免与新版内置ID冲突。

## 文案、目录与默认值

元数据含ID、revision、分类/子分类、slot、默认语言、名称/说明、翻译、封面、作用域和能力。目录resourceCatalogVersion=1，每项含完整manifest、包URL、独立封面URL、bytes、sha256；URL只允许HTTPS。目录可离线缓存，联网不自动应用。revision固定内容，不同摘要的同ID同revision拒绝覆盖。

Defaults和用户状态使用独立JSON，选择值固定内容SHA-256，null明确关闭。合并：中性值→默认配置→用户保存→宿主覆盖。列表加载不会开启资源。分类排序包含全部8类。资源文案按宿主语言/父语言→defaultLocale→name回退，不跨简繁脚本替换。实验室按localeMode选择跟随宿主或固定defaultLocale，并按categoryOrder调整八类入口。

## Android使用

```kotlin
// 工作线程；所有来源使用同一安装API。
val manager = BeautyResourceManager(File(filesDir, "beauty_resources"))
val installed = manager.install(assets.open("resource_center/lash_natural_1.zip"))
val composer = BeautyEffectComposer(manager, actualCapabilities)
val state = composer.select(previousState, installed)
val prepared = composer.prepare(state)
// 准备和持久化均成功后，宿主把settings发布给同一Session。
stateStore.write(state)
session.settings = prepared.settings
// 旧GPU工作完成后关闭旧ComposedBeauty，释放其资源使用引用。
```

manager仅缓存活动句柄的已解码资源；最后一个句柄释放时移除缓存，安装全目录不会常驻全部图片。manager支持下载取消/进度、校验、临时展开、原子目录提交、重复复用、活动引用保护和离线目录。参数/选择保存不在帧回调执行。下载队列由宿主提供，本管理器安装串行化；同一内容安装复用，首版网络重复请求不承诺共享HTTP连接或断点续传；取消最多受单次HTTP读取超时约束。安装目录即权威索引，按摘要扫描，无第二份易失配索引。删除活动资源受到引用保护；清临时目录不删除已安装资源。

套装prepare从中性值开始，编辑某个槽位时先materializeTheme安装并固定子包，再替换目标槽位。手动参数覆盖单项scope；重新选资源时清其scope旧手动覆盖。实验室保存新选择后一次发布，失败保留旧画面。

## Web制作与发布

资源交付中心支持8分类、名称/说明、默认语言、英文及任意语言标签翻译、替换PNG封面、参数预设；图片素材通过原编辑器制作后点击“应用到主题”交回。可直接添加ja、zh-Hant等翻译行，无需手写JSON。可保存/恢复本机草稿，导出单项、当前搭配套装、默认JSON、以及catalog/defaults/全部包和封面的交付ZIP。

发布：填写最终HTTPS根地址→导出发布包→解压上传全部文件→最后发布目录。npm run build复制resource-studio及resources/内置示例，线上需单独部署。不会自动创建服务器、上传人脸帧或发布。

生成17个示例：node scripts/generate-resources.mjs。Web/Android/iOS夹具更新须一起同步。包内示例封面部分为素材图，正式商业资源应由美术提供统一佩戴效果封面。

## 验证范围

Schema验证结构，运行时额外验证路径、引用、PNG、CRC、累计预算、能力一致性和scope关系。源代码存在不等于设备支持；当前未实现的动画/视频背景/眼影等不会作为可用执行器列出。

资源系统不接管采集/推流；相机和已选定引擎版本仍需各自帧适配。厂商远端推流、持续温升/性能及Apple端编译与真机验收不由资源回归测试替代。

## 默认配置与恢复细节

`defaults.merge(saved, host, installed = manager.list())`在资源已安装后调用：用户保存的选择先移除该资源scope内继承的默认参数，再应用用户显式参数；避免重新进入时默认磨皮覆盖所选预设。保存套装会替换默认单项，保存单项会移除默认套装槽位。`resolveInstalled`只关闭缺失或槽位不符的资源并返回missingSlots，保留其他参数和选择，不自动改写旧文件。

眼镜、胡子、帽子使用sticker.glasses、sticker.moustache、sticker.hat独立槽位，可同时佩戴；同槽位互斥。套装占theme槽位，编辑前materialize。旧综合预设仍按整体作用域处理，不冒充单一部位。

## 同事的制作步骤

1. 进入独立Web的“资源交付中心”，选择分类、子分类，填写稳定ID、名称、修订号、默认语言与翻译；替换列表封面。
2. 参数效果在右侧配置；图片睫毛、贴纸、背景和运镜点“制作效果素材”，使用现有编辑器，再点“应用到主题”回传。
3. 生成单项并选择搭配；可导出单项或当前套装。默认JSON同时固定当前选择的摘要；导入默认JSON前先导入其引用资源。
4. 填写最终HTTPS资源根地址，导出发布目录与全部资源。交付ZIP含catalog.json/defaults.json/各包/各封面。
5. 将全部文件部署到该目录后，首页独立资源中心点“网络资源目录”输入catalog.json地址。下载完成仅安装，点击已安装卡片才应用；断网可使用已安装资源及缓存目录。内置与SAF本地导入同样走公共管理器。
6. 新版本必须递增revision；不可在同ID同revision下更换内容。不同内容摘要并存，使用中的旧版本不能被删除。

入口：实验室底部设置面板→美颜/美型/美妆/滤镜/贴纸/背景/运镜/套装。长按单项可停用或删除，“恢复默认”读取JSON，“重置分类”仅恢复当前类（套装恢复整体默认）。相机页清空贴纸走同一状态管理。
