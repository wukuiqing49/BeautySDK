# 手势识别与宿主事件接入

Android 可选本地手势分析。继续使用固定 MediaPipe Tasks Vision 0.10.32 和 GPUPixel，不引入 ONNX、新的原生库或动画素材。SDK 只输出观测/事件，动画由宿主实现；不自动修改 BeautySettings、主题或视频内容。iOS/Web 未实现此手势模块。

## 能力范围

| 类别 | GestureKind | 实现与限制 |
| --- | --- | --- |
| 官方静态分类 | CLOSED_FIST、OPEN_PALM、POINTING_UP、THUMB_DOWN、THUMB_UP、VICTORY、I_LOVE_YOU | MediaPipe 模型分类；I_LOVE_YOU 指伸出拇指/食指/小指，不是双手比心 |
| 自定义静态 | OK、NUMBER_0..NUMBER_5、FINGER_HEART | 实验性二维关键点规则；数字表示伸指数，不是区域数字手语，未实现6..10 |
| 双手静态 | TWO_HAND_HEART、PRAYER | 实验性指尖/掌心几何规则，最多双手；遮挡、侧掌、相似手型可能误识别 |
| 连续动作 | WAVE、CLAP、CIRCLE | 实验性时序规则；挥手要求开掌横向往返，拍手要求双掌先分开再接近，画圈要求食指轨迹闭合、方向一致 |
| 交互 | PINCH、TWO_HAND_SCALE | 捏合 START/UPDATE/END；丢手/过大采样间隔 CANCEL。单手 position/delta 可用于拖动；双手捏合的 scale 可用于缩放 |

模型输出每只手21个二维原始帧关键点、左右手分类及其置信度、手势分类及其置信度。不暴露第三方类型；本接口不提供3D世界关键点。未识别手型的 modelGesture=null，无手返回空列表，推理失败单独报告 ERROR。规则不是训练完成的新模型，不能把合成坐标测试当作真人识别准确率；真实单手捏心、双手比心、合十和动态手势需进一步真人验收。

## SDK 接入

```kotlin
val gestures = GestureAnalysisRuntime(
    factory = MediaPipeGestureAnalyzer.factory(context),
    config = GestureConfig(experimentalRules = true),
    allowScaledFrames = true,
    onError = { error -> /* 收集诊断；不在此等待渲染线程 */ },
)
gestures.setEnabled(true) // 默认false；仅需要手势时开启

// 采集回调：同视场的小尺寸RGBA；返回后宿主即可回收输入。
val generation = gestures.generationToken()
gestures.submit(smallRgbaFrame, generation)

// 输出帧处理：与分析帧共用真实单调采样时间线和方向/镜像。
val snapshot = gestures.snapshot(outputFrame.info)
val events = gestures.pollEvents(outputFrame.info) // 单消费者，一次性交付
events.forEach { event ->
    when (event.phase) {
        GestureEventPhase.TRIGGER -> { /* 由宿主按kind触发自己的特效 */ }
        GestureEventPhase.START -> { /* 开始捏合/拖动；记录trackIds */ }
        GestureEventPhase.UPDATE -> { /* 使用原始坐标position/delta及相对scale */ }
        GestureEventPhase.END, GestureEventPhase.CANCEL -> { /* 结束交互 */ }
    }
}
if (snapshot.state != GestureAnalysisState.READY) {
    // NO_HANDS/STALE/ERROR/DISABLED等状态需取消宿主活动交互，不能等待END。
}

// 换源/切摄像头，即使尺寸未变也必须显式重置，并取消宿主交互。
gestures.reset()
// 停止功能可setEnabled(false)，最终由创建者释放。
gestures.close()
```

`allowScaledFrames=true` 只适用于同视场、等宽高比、同旋转镜像的小图/输出组合；不自动处理不同crop或留边，不借用人脸 AnalysisFrameMapping。x/y始终在原始未旋转帧的归一化空间，左上原点，x向右/y向下。宿主显示前自行映射到旋转镜像后的视口；规则内部转正并按像素宽高比判形，不把非正方形图上的归一化距离当实际几何。

左右手是转正分析图的模型分类，不把画面左/右当身体身份。临时trackIds由最近掌心和高置信度左右手关联，检测列表换序不会直接换ID；不是多人身份跟踪，交叉/遮挡后可能更换ID。宿主不能无限绑定旧ID。

默认最长分析边384、最小采样间隔100ms（限频不是推理FPS保证），单worker/单在途，忙时在复制前丢帧；提交仅批量复制到复用DirectByteBuffer，缩图/旋转/镜像/RGB打包及同步VIDEO推理在worker完成。最多8个结果历史与32个事件，事件过载丢最旧项。pollEvents不交付未来、超过300ms或错误几何的事件；相机与输出必须同采样时钟。宿主暂停消费、长时间无新帧或结果过期时须以自己的交互超时取消拖动。

默认静态稳定180ms、冷却800ms；持续保持同一姿势只触发一次，短暂单帧抖动不会重新触发。连续采样超过300ms间断会取消交互并重新稳定。PINCH有张开/闭合滞回；单手scale是指尖间距相对起始值，双手scale是两捏合中心像素距离相对起始值。规则的score=null；不伪造置信度。交互事件有序且ID递增，重置不把历史事件复活。重置/关闭不等待模型，旧代次结果不发布；模型在所属worker最终释放。

### 避免重复调用宿主动画

- 静态手势按“稳定识别→TRIGGER一次→保持锁存→松开稳定180ms→重新识别”处理；800ms冷却到期本身不会让保持的姿势重复触发。短暂丢手在300ms跟踪预算内保留静态锁存，单帧分类抖动不算松开；真正离开画面较久、换源或reset后视为新一轮识别。
- `snapshot` / Demo `onGestures`会持续更新，不能用“当前识别为VICTORY”每次启动动画。只消费事件：TRIGGER启动一次性效果，START启动交互，UPDATE仅更新已有交互，END/CANCEL清理。pollEvents取走即删除，同一event.id不会再次交付；宿主自行转发或缓存事件时也应以id去重，运行时实例之间不要混用id。
- 同一手型可能有不同语义事件，例如VICTORY与NUMBER_2，或OK与PINCH。它们不是重复事件；宿主应仅订阅需要的kind，并按业务决定互斥/优先级。动画正在播放时是否忽略新手势或排队由宿主管理，SDK不知道宿主动画的播放状态。
- 连续挥手/拍手/画圈可在完成下一轮动作且满足冷却后再次TRIGGER；UPDATE连续输出也是设计行为，不应重复创建播放器或重播动画。

纯纹理宿主须提供显式RGBA分析小图，本接口不隐式读回或接管采集。Demo继续使用CameraX独立RGBA；勾选页面“手势识别”后才运行，原图模式不运行，与是否开启人脸贴纸互相独立。`GpuBeautyPreview.onGestures`在主线程约250ms交付快照和事件，仅作演示诊断，SDK实时集成应直接轮询runtime。页面不保存手势开关，不保存或上传手部图像，不自动播放动画。

## 模型与验证

模型：[Google float16/1 gesture_recognizer.task](https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task)，8,373,440字节，SHA256 `97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482`。包含hand_landmarker.task与hand_gesture_recognizer.task。`scripts/verify_models.py`固定大小/摘要，发布通知与模型商业再分发条款需与现有人脸模型一样单独核对，不能仅以源代码许可推断权重许可。

接口参考：[官方Android接入](https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer/android)、[手势模型说明](https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer)。四张公开手势测试图来自[官方Python示例](https://github.com/google-ai-edge/mediapipe-samples/blob/main/examples/gesture_recognizer/python/gesture_recognizer.ipynb)，仅由scripts/prepare_test_fixture.py校验后写入测试构建目录，不进产品APK。测试证据及未验证范围见[固定测试报告](testing/TEST_REPORT.md)。
