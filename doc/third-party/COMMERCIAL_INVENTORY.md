# 商业交付来源与许可清单

此表是待完成的证据台账，不是法律授权意见。禁止把“开源代码可用”自动推广为全部模型与素材均已获授权。

| 项目 | 现有来源 | 已有材料 | 交付前待办 |
| --- | --- | --- | --- |
| GPUPixel Shader/LUT/贴妆贴图 | core/lib_beauty_gpupixel/third_party/gpupixel/SOURCE.json 固定提交 | LICENSE、打包 NOTICE、来源摘要 | 逐项核对文件是否存在独立条款、修改标记及分发通知 |
| MediaPipe Tasks Vision 0.10.32 | Google Maven，版本目录固定 | doc/third-party/MediaPipe-LICENSE | 审核完整传递依赖及固定产物网络/指标行为 |
| Face Landmarker | MODELS.md 的具体制品与摘要 | 模型校验记录 | 具体权重商业再分发和通知证据 |
| Gesture Recognizer | MODELS.md 的具体制品与摘要 | 模型校验记录 | 具体权重商业再分发和通知证据 |
| Selfie Segmenter float16/1 | core/lib_beauty_mediapipe/src/main/assets/selfie_segmenter.SOURCE.json | 249537字节，摘要191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b；现有记录引用官方model card许可 | 留存对应条款证据并核对实际打包通知 |
| CMS内置与用户素材 | 每一来源文件/创作者 | 现有资源包身份、修订、摘要 | 记录作者、来源、授权范围、期限、地域、署名、可再分发证明 |
| 测试人像与手势夹具 | Google公开测试资源 | MODELS.md，测试APK限定 | 不进入商业素材包；真人评审另取得必要同意 |
| SDK自身与演示宿主 | 本仓库；宿主使用WKQ | 模块依赖边界 | 确认发布主体/商标/自有代码权属；不把宿主依赖混入SDK交付 |

每项审核结论应关联实际文件摘要、条款来源/日期、审核人及证据文件，缺失时保持 pending。
签名私钥和客户个人信息不得放进此台账。新增模型、贴图、第三方库必须重新审核对应条目。
