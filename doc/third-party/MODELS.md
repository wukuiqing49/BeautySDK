# 模型与运行库记录

2026-10-23 引入。版本固定用于本次实现，不代表自动追随最新版本。

| 内容 | 来源/版本 | SHA256 / 大小 |
| --- | --- | --- |
| Face Landmarker | [Google float16/1](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task) | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` / 3,758,596 字节 |
| Gesture Recognizer | [Google float16/1](https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task) | `97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482` / 8,373,440字节 |
| MediaPipe Tasks Vision | Google Maven `0.10.32` | 代码 Apache-2.0，见 MediaPipe-LICENSE；保留依赖 AAR 自带通知和规则 |

Face Landmarker 任务包是独立下载的模型制品，不能仅以 MediaPipe 源码的 Apache-2.0 许可证认定所有权重的再分发条款；商业发布前还需确认该具体任务包的模型授权/通知。本文不等于已完成商业授权审查。


`python scripts/verify_models.py` 校验已纳入工程的模型。

设备测试的公开图片来自 [Google MediaPipe portrait.jpg](https://storage.googleapis.com/mediapipe-assets/portrait.jpg)，SHA256 `a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e`。仅由 `scripts/prepare_test_fixture.py` 下载至构建目录并打入测试 APK，产品 APK 不包含该图片。测试不读取或保存设备用户的人脸图片。

手势模型于2026-10-27加入，复用既有MediaPipe运行库，不新增so。权重许可与通知需按具体制品单独核验。四张手势测试图及SHA256固定于scripts/prepare_test_fixture.py，仅打入测试APK。来源、能力和限制见[手势说明](../SDK_GESTURES.md)。
