# 静态人脸贴纸制作与调试（资源包 v1）

当前交付是单人脸2D静态贴纸。相机测试主线为MediaPipe＋基础OpenGL；ZIP导入不会增加账号、联网或人脸图像存储。

## 从素材到手机

1. 复制本目录的 [template](template/manifest.json)，保留文件名与目录结构，替换 `sticker.png` 和 `preview.png`。
2. 按下面的锚点与画布规则修改 `manifest.json`。不要修改schemaVersion，不要加入脚本或其他格式。
3. 将三个文件直接压缩为ZIP，ZIP打开后第一层必须能看到 `manifest.json`，不能再套一层目录。
4. 把ZIP放到手机可选择的位置。打开Demo → 相机测试 → 贴纸 → 素材资源工坊 → 导入ZIP。
5. 默认切到MediaPipe，在效果画面中检查贴合。可点击小窗交换主副预览，在工坊调整对应图层。
6. 点击“导出资源包”保存完整ZIP；其他设备或宿主可以直接重新加载。导出配置只生成JSON，需替换原包中的 `manifest.json` 后再打包。

现有6个可导入样例见 [samples](samples)：圆框眼镜、爱心眼镜、卷胡子、礼帽、皇冠、猫耳。工坊中的“载入样例”读取完全相同的标准格式。可用 `python -B scripts/build_sticker_samples.py` 从原素材重新生成，脚本不改动原PNG。


## 保存后通过资源包加载

采用“制作/调参 → 保留完整ZIP → 加载ZIP → 转为StickerPack → 合成”的流程。原始模板和六份样例继续保留在本目录，Demo assets/sticker_packs包含同格式样例。自定义资源在工坊调参后自动保存到应用私有sticker_workshop/draft.zip，包含最新manifest、原PNG和预览；当前只保留最后一份工坊草稿。进入相机页时在IO线程通过StickerResourcePack.loadZip重新校验、解码，再toStickerPack交给渲染器，并恢复上次开关。视频帧回调不读取ZIP、不重复解码PNG。

调好后可在工坊“导出”分区点击“保存并加载”：先原子写入当前完整ZIP，再从该文件重新校验和解码，加载成功后替换预览并启用；清除期间不会重新启用，失败保留当前效果并报告错误。自动草稿仍作为退出后的兜底。

需要长期保留多套或用于其他宿主时，点击“导出资源包”保存各自ZIP；下一次使用“导入ZIP”加载。只导出JSON不包含图片，不能独立加载。清除贴纸只关闭显示，不删除已保存草稿或导出的ZIP。当前没有多资源收藏库；重新导入/载入其他样例会替换当前草稿。

```kotlin
// 在IO线程加载一次；assets.open也可以换成私有文件输入流。
val resource = context.assets.open("sticker_packs/round_glasses.zip").use {
    StickerResourcePack.loadZip(it)
}
val pack = resource.toStickerPack()
// 将不可变pack交给宿主，再在处理线程采用settings快照。
pipeline.settings = BeautySettings(stickerPack = pack)
```

## 素材制作规则

- PNG，透明背景，sRGB，导出时保留正常透明通道；不要把黑白底烘焙到图像里。SDK按非预乘ARGB取得像素，再做source-over合成。
- 建议单层从512×256或512×512画布开始，保持设计所需留白，不强制拉伸到正方形。图片宽高上限2048。
- 眼镜：沿水平眼线制作，两眼中点作为默认中心。胡子：嘴角连线为方向，上唇为中心。帽子、皇冠、猫耳：绑定双眼，通过画布中心点参数上移到头顶。
- 预览图建议最长边256；可以与素材相同。它只用于选材，不参与视频合成。
- 锯齿、脏边、阴影和半透明边缘应在设计时检查。v1当前使用最近邻采样，细线/小字缩小时可能抖动，不承诺矢量或高质量缩小效果。
- 素材正面图不等于3D模型：转头时只更新2D位置、跨度和画面内角度；没有耳后遮挡、头发遮挡、侧脸透视或深度遮挡。

## 包结构与字段

```text
round_glasses.zip
├── manifest.json
├── preview.png
└── sticker.png
```

```json
{
  "schemaVersion": 1,
  "id": "round_glasses",
  "name": "Round glasses",
  "preview": "preview.png",
  "layers": [{
    "id": "main",
    "image": "sticker.png",
    "anchor": "EYES",
    "widthInAnchorSpans": 2.5,
    "pivot": [0.5, 0.5],
    "offset": [0, 0],
    "rotationDegrees": 0
  }]
}
```

| 字段 | 含义与范围 |
| --- | --- |
| schemaVersion | 当前必须为数值1 |
| id / 图层id | 小写字母开头，后续小写字母、数字或下划线，最长48字符；图层id不可重复 |
| name | 非空显示名称，最多80字符 |
| preview / image | ZIP根目录的PNG文件名，不接受路径或URL；所有PNG必须被配置引用 |
| layers | 1～3层，列表第一层在下，最后一层在上；合计最多三层，不额外叠加内置选择 |
| anchor | `EYES`：双眼中点＋双眼跨度；`UPPER_LIP`：上唇＋嘴角跨度 |
| widthInAnchorSpans | 整张图片宽度÷锚点跨度，0.1～10；不是像素或脸框宽度 |
| pivot | 图片内与锚点对齐的位置，左上[0,0]、右下[1,1]，允许每项-4～4；帽子样例使用[0.5,1.45]，让帽子位于眼线上方 |
| offset | 在锚点局部坐标中移动图片中心，单位为锚点跨度，每项-4～4；X向显示右侧、Y向显示下侧，随锚点一起旋转 |
| rotationDegrees | 在锚点角度上附加旋转，-180～180度；正常显示空间中顺时针为正，镜像时内部补偿手性 |

所有字段都必须提供。未知字段、未知锚点和无效数字会报错，不会静默忽略。需要多个图层时，在 `layers` 中增加不同id并加入对应PNG即可，工坊可逐层编辑；v1不提供在手机上增删图层或重新绘制PNG。

ZIP输入及解压后内容各最多16MiB，单PNG最多4MiB、配置最多64KiB；预览与去重图层PNG合计最多4,194,304像素。读取器不向文件系统解压，不接受目录、重复条目或未引用文件。请勿把OS隐藏文件、`__MACOSX` 或制作源文件一并打包。

## Android SDK接入

资源加载和导出必须在宿主IO线程执行；以下 `InputStream` / `OutputStream` 由方法关闭。只会在加载素材时解码PNG，不在视频帧处理中创建Bitmap。

```kotlin
val resource = StickerResourcePack.loadZip(inputStream)
val stickers = resource.toStickerPack()
// 回到会话的配置/处理调度中设置；不要在每一视频帧重新加载资源。
session.settings = session.settings.copy(stickerPack = stickers)

// 对单层做布局调整，原图片对象继续复用。
val manifest = resource.manifest
val changedLayer = manifest.layers[0].copy(offsetY = -0.1f)
val edited = resource.withManifest(StickerResourceManifest(
    manifest.id, manifest.name, listOf(changedLayer), manifest.preview
)) // 此示例为单层；多层时保留其余层及顺序。
edited.writeZip(outputStream)
```

宿主负责保管资源包和选择何时应用；`StickerResourcePack` 不绑定Activity、相机、MediaPipe或直播厂商。渲染仍需要相应的人脸锚点；只有人脸框、没有锚点的后端不能显示这些贴纸。

## 手机工坊的行为

- 导入、导出、预览缩略图处理在IO线程；导入失败保留上一份有效资源。
- 调参立即生成小型不可变配置，复用已解码图片和GL纹理，不重新读取ZIP。可以编辑锚点、跨度、偏移、角度和图片中心点。
- “使用此资源包”启用时替换原内置贴纸选择；关闭时恢复原选择。点击内置素材卡片或“清除贴纸”会退出工坊资源应用。
- 重置只恢复当前图层载入时的参数。编辑状态保存在ViewModel中，支持Activity重建；应用进程结束后不保证恢复，离开前请导出ZIP。
- 导出时冻结一份配置快照，通过系统文件选择器由用户决定保存位置。未导出前不自动写入相机、人脸帧或公共存储。
- 本功能不等于已接入直播：Zego/声网具体版本适配、编码前交回和远端展示仍需独立完成。

## 制作验收

先用正面静止人脸检查锚点和尺度，再检查转头、前后摄、横竖屏、不同分辨率、出入画面和遮挡。导出后重新导入，确认参数和图片一致。效果图仅供制作判断，测试不能代替持续性能和真实直播远端验收。

## 工坊草稿与选材更新（2026-10-26）

- 工坊分为素材、布局、导出；每次只显示一个参数滑杆。打开时不自动载入样例。
- 最近编辑资源包保存到应用私有目录一份草稿，600ms防抖并原子提交，退后台安排立即写入。进程异常退出时最多恢复最后一次成功写入，不保证最后一个拖动动作已落盘。
- 恢复草稿默认不启用效果；启用复选框后才替换内置贴纸。导入期间清除效果，不会在导入完成后被重新开启。
- 草稿仅包含素材PNG与布局参数，不保存相机画面。内置素材只加载当前选择，最多三层；资源导出仍保留原PNG，纯渲染调用方只需保留StickerPack。
