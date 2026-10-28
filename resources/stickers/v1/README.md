# BeautySDK 静态贴纸资源协议 V1

这是本项目协议，不是商业SDK通用bundle。Android现有格式保持兼容；同一个ZIP由网页导出、Android/iOS加载。JSON UTF-8；只允许根目录manifest.json、预览PNG和各层引用PNG，无目录/额外文件/脚本。最多5个ZIP条目。

```json
{"schemaVersion":1,"id":"my_glasses","name":"我的眼镜","preview":"preview.png","layers":[{"id":"main","image":"sticker.png","anchor":"EYES","widthInAnchorSpans":2.5,"pivot":[0.5,0.5],"offset":[0,0],"rotationDegrees":0}]}
```

字段必须齐全，不接受额外字段。id与层id匹配`[a-z][a-z0-9_]{0,47}`，层id唯一；名称非空、最多80个UTF-16单位且无控制字符。图片名匹配`[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\.png`，不含`..`。层1至3个，列表从前向后覆盖。

- anchor：EYES（双眼中心、双眼间距）或UPPER_LIP（真实上唇中心、嘴角间距）。帽子使用EYES配合pivot/offset，不新增检测器编号。
- widthInAnchorSpans：整张PNG宽度 / 锚点跨度，0.1至10。
- pivot：素材归一化定位点，左上(0,0)、右下(1,1)，可在图片外；每项-4至4。
- offset：沿锚点局部x/y方向移动多少个锚点跨度，每项-4至4；未旋转正立画面x右y下。
- rotationDegrees：相对眼线/嘴线附加顺时针角度，-180至180。所有数值有限，不接受数字字符串或布尔值。

## 坐标与镜像

SDK统一以**原始帧像素**计算，非屏幕点。检测器先在正立显示语义空间取得左/右眼、左右嘴角及上唇，再将点映射回原始帧；LEFT代表正立显示空间左侧，不直接采用检测器解剖命名。EYES中心为眼睛均值；UPPER_LIP中心必须真实检测，不能用嘴角均值替代。角度`atan2(right.y-left.y,right.x-left.x)`，跨度像素hypot，<2px或缺点隐藏该层。

令基准中心C、跨度S、角度a、镜像手性k=(mirrored?-1:1)：

```
C' = C + S * R(a) * (offset.x, offset.y*k)
b = a + rotationDegrees*pi/180*k
W = S*widthInAnchorSpans; H = W*imageHeight/imageWidth
P(u,v) = C' + R(b)*((u-pivot.x)*W, (v-pivot.y)*H*k)
```

`geometry-vectors.json`为跨端确定性夹具。旋转/裁剪/镜像只映射一次，输入与输出保持宿主元数据约定。工坊在正立无镜像坐标编辑；SDK负责适配原始坐标，不需同事为前置摄像头另做一套素材。

## 图片、混合与预算

静态8位sRGB RGBA PNG推荐；保留透明区域，不使用白底。v1拒绝APNG、CRC损坏、不完整PNG。每张1..2048px、最大4MiB；所有唯一图片（含preview）总像素<=4194304；manifest<=64KiB；压缩ZIP及解压内容各<=16MiB。只接受STORE/DEFLATE，不支持加密、分卷、ZIP64。preview只供列表展示，不参与合成。

素材纹理按非预乘RGBA线性采样、CLAMP_TO_EDGE、无mipmap；合成使用source-over。iOS Metal实现fragment先预乘再blend，要求宿主输出预乘alpha（直播通常不透明）；Android现有渲染器保留其帧alpha契约。两端颜色/透明边缘仍须真机对照，不宣称逐像素完全一致。

## 接入

Android（IO线程加载，切换时整体发布；不得逐帧加载）：
```kotlin
val resource = assets.open("sticker_packs/my_glasses.zip").use(StickerResourcePack::loadZip)
val settings = BeautySettings(stickerPack = resource.toStickerPack())
// 将settings交给现有BeautySession/引擎公共管线，相机和直播共用。
```

iOS完整资源组件见`ios/BeautyResources`。算法模型不包含在贴纸包；本轮不实现iOS人脸检测、相机、直播厂商适配或完整美颜SDK。后续动画、背景、LUT应定义独立类型与新版本，不在V1增加未知字段。
