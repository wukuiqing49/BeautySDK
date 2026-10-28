# 完整主题资源包 V1

主题制作入口：独立 Web 项目的 `theme.html`。单类素材仍沿用贴纸V1、场景/美颜V2，主题不复制这些配置字段。

## 制作与交付

新建主题 → 编辑或导入各类效果 → 勾选启用类别 → 组合预演 → 检查能力要求 → 导出完整主题ZIP。支持导回编辑与本机IndexedDB草稿恢复。子编辑器中点击“采用此效果”才替换主题内该类资源，“返回主题”放弃本次子编辑。

```text
my_theme.zip
  bundle.json
  preview.png
  sticker.zip       # 可选
  background.zip    # 可选
  motion.zip        # 可选
  beauty.zip        # 可选
```

```json
{
  "bundleVersion": 1,
  "id": "my_theme",
  "name": "我的主题",
  "preview": "preview.png",
  "resources": {
    "sticker": "sticker.zip",
    "background": "background.zip",
    "motion": "motion.zip",
    "beauty": "beauty.zip"
  }
}
```

字段严格匹配，禁止未知字段。id 为小写字母开头的48位以内英文、数字或下划线；name 非空、最多80个UTF-16代码单元、禁止控制字符。未包含类别的值必须显式为null，不能缺字段。至少启用一类，不接受空主题。preview固定为preview.png；子文件名固定与类别一致。不支持目录、脚本、外部URL、额外文件、嵌套主题或多层递归ZIP。子包schemaVersion与类别必须匹配。

## 预算与行为

- 主题ZIP输入和外层解压内容均不超过32MiB，最多6个根目录文件；bundle.json最多64KiB。
- 封面PNG最多4MiB、最长边2048。所有封面与子资源PNG累计最多8,388,608像素；相同PNG在不同子包中重复计数。
- 子包仍分别执行16MiB输入/展开预算、4MiB单PNG、4,194,304子包总像素、静态PNG/CRC和严格引用检查。
- 不把内容解压到文件系统；整体累计像素检查先于子图像解码。PNG加载与GPU纹理上传不得逐帧执行。
- **主题为整套替换**：未包含类别关闭，不继承上一主题/手动参数；单类资源applyTo仍只覆盖对应类别。
- 能力由实际内容推导：贴纸、人脸跟随与美型要求人脸关键点；任意背景要求分割；非中性扩展调色/美型要求扩展后端。
- 缺能力应在发布新配置前明确失败，保留旧主题。能力声明不是初始化检测器的替代品，宿主必须真实提供对应组件。
- 绘制顺序：背景与美颜 → 贴纸 → 运镜。预演仅是示意人像，不能证明真人分割、磨皮、美型或跟随性能。

## Android

```kotlin
// IO线程加载；不在视频帧回调解析ZIP。
val theme = BeautyThemePack.loadZip(inputStream)
val capabilities = ThemeCapabilities(
    faceTracking = true,     // 宿主已提供有效人脸关键点
    segmentation = true,     // 已配置有效ForegroundMaskProvider
    extendedBeauty = true,   // 已选择支持所需参数的渲染后端
)
val settings = theme.prepare(capabilities)
// 所有校验成功后，一次发布，未包含类别恢复默认。
beautySession.settings = settings
// 或 theme.applyTo(beautySession, capabilities)
```

图片数据在加载时解码，prepare重复调用复用同一个不可变配置，不重新解码。Session与其他通用帧入口均使用BeautySettings，因此不依赖相机或特定直播厂商。

Camera Demo 的“导入效果 / 主题”接受同一主题ZIP，ViewModel在IO队列加载，AtomicFile保存成功后整体发布，下次进入恢复。Demo依据主题配置选择MediaPipe/GPUPixel，分割仍由现有公共场景链路提供。主题优先于手动和单独贴纸，清除主题返回手动控制。总美颜关闭时临时停用主题中的美颜，背景、贴纸、运镜不受影响。

## iOS

```swift
let theme = try BeautyThemePack.load(url: url) // 工作队列
let capabilities = ThemeCapabilities(faceTracking: true, segmentation: true, extendedBeauty: true)
try theme.validateCapabilities(capabilities)
// 渲染队列准备所有新纹理后整体切换，失败保留旧渲染器。
try renderer.setTheme(theme, capabilities: capabilities) // MetalThemeRenderer
```

MetalThemeRenderer 接收正向RGBA8纹理、同方向蒙版与贴纸锚点，以可复用临时纹理完成场景→贴纸→运镜。调用者管理串行队列、输入输出纹理生命周期与commandBuffer提交。编码抛错时宿主应弃用该commandBuffer并旁路原帧。当前Metal后端拒绝非零瘦脸/大眼；需要这些能力的宿主可消费主题内效果资源交给其他后端。Windows环境未编译Swift/Metal，不能视为iOS已验收。

## 示例与测试

`samples/full_theme.zip`由独立Web打包器生成，Android/iOS使用同一二进制夹具。协议JSON Schema仅约束manifest结构，不能替代ZIP、PNG和累计预算校验。
