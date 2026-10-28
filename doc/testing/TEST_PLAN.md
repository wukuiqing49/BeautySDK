# 2026-10-28：分类资源库与JSON参数配置

- LIBRARY-01：Web全量50资源分类ZIP往返、展开文件与package.zip一致、目录摘要；关联delivery/library/pack/workspace-model。
- LIBRARY-02：Android ResourceLibraryDeviceTest：分类导入、15属性、范围/步长/默认值、语言回退、篡改/穿越/缺翻译拒绝、旧包兼容。
- LIBRARY-03：清单驱动控件调参、默认恢复、保存重开；真实Camera页自定义分类/效果名、参数提交到预览设置、背景专用控件保留。
- 共享消费者：ResourceDeliveryDeviceTest、ResourceCenterUiDeviceTest、GpuPixelControlsDeviceTest、SceneControlsDeviceTest；资源模块8项JVM，Web49项、app编译/lint和UI/i18n/架构门禁。
- 保护设备已有资源状态和场景文件；LabSceneBackup补充library.json。仅保存无相机图像的合成控件截图，不做真人画质、持续推流、iOS或线上发布结论。新控件横屏/字体缩放未验收。

---

# 2026-10-28：配置记忆专项

变更宿主ResourceCenterModel的场景存档、跨页面IO顺序及退出时的已接受任务处理。新增ScenePersistenceDeviceTest，验证图片/焦点/虚化、运镜倍率周期/关键帧、参数和开关在清除ViewModel后重新创建恢复；不等待保存就退出、显式关闭持久化、新资源替换旧覆盖，最后检查真实相机渲染选项。LabSceneBackup在串行队列排空后恢复资源状态、场景JSON与图片，既有写状态的用例同步使用该Rule。

相关消费者选择SceneControls、LabHierarchy、LabInteraction、GpuPixelControls、ResourceCenterUi、ResourceDelivery、ThemeDemo和贴纸持久化；不把恢复后的最终设置断言当作真人画质或持续性能测试。无SDK渲染和Web变更，不重跑像素/全量Web；完整结果、次数及限制记录在固定报告。

---

# 2026-10-28：虚拟背景边缘专项

修改公共SceneGlPass shader，选择核心117项JVM及SceneDeviceTest/SceneControlsDeviceTest共6项设备回归。新增BG-EDGE-01：低分辨率蒙版与硬色轮廓，验证重建轮廓、背景虚化前景污染、alpha及8种方向/镜像元数据；配合现有OES、真实模型、纹理桥、生命周期与场景控件验证。无额外模型、资源协议或UI变化；不重跑Web/UI全量。人工真人头发、动态遮挡、GPU成本和持续推流另行验收，不从合成输入断言外推。测试保护与恢复宿主用户文件，禁止保存相机帧。

---

# 2026-10-28：面板视觉专项

更改面板布局、样式、参数图标和相机素材卡；回归LabHierarchy、LabInteraction、EffectPanel、SheetScroll、DemoLayout、SceneControls、GpuPixelControls共10项；大字体1.3复跑LabHierarchy、EffectPanel、SheetScroll共4项。沿用既有操作断言，纯视觉变化不新增镜像实现的测试。源码指纹包含新增drawable/color，测试备份恢复用户配置；横屏与SDK算法未复测。证据见固定报告最新段落。

---

# 2026-10-28：实验室层级整理增量（最新）

本轮修改相机Activity/XML、资源Controller/Model和三语言文案；沿用前轮32项资源/交互消费者，增加2项回调展示和2项层级用例，共36项。默认模式更新为参数类微调、素材类预设，旧用例同步明确切换预设；背景/运镜参数测试显式切换微调。

- LAB-CLEAN-01：LabHierarchyDeviceTest.singleEntryKeepsCallbacksAndOptionalInsetAccessible，验证单入口、摘要打开详情、最多12条事件、更多菜单的原图小窗和调试入口。
- LAB-CLEAN-02：LabHierarchyDeviceTest.categoryOffPreservesOtherEffectsAndBeautyUsesOneSlider，验证单滑杆、美颜/美妆关闭保留其他参数、背景和运镜独立关闭。
- LAB-CLEAN-03：1.3字体竖/横屏各9项（上述2项、回调2项、EffectPanel1项、滚动1项、DemoLayout3项），查看仅包含控件的面板截图。

测试前备份用户文件和显示设置，结束恢复并逐字节核验5份配置。核心算法与Web没有变更，不重跑其全量测试；旧相机停止计数竞争保持未解决状态。完整语言、日夜、其他设备、重建和真人持续效果不在本轮通过范围。构建、门禁、执行命令和证据见固定报告最新段落。

---

# 2026-10-28：回调展示增量回归

新增CALLBACK-UI-01：CallbackDisplayDeviceTest.faceTransitionsDoNotRepeatOrTreatStaleAsLeave，输入初始无人脸、有人脸、重复快照、过期/错误、离开、再次进入及重启，检查状态变化事件和计数。新增CALLBACK-UI-02：gestureCallbackStaysVisibleUntilNextEventAndResetsWithSession，检查主页面可见、事件持续展示、批次末事件、旧ID拒绝、关闭开关及会话重置。仅验证回调绑定/展示，不当作真人检测测试。

修改页面布局及回调消费者，因此回归LabInteraction、EffectPanel、SheetScroll、DemoLayout与GestureDeviceTest，共13项。新增行占用预览高度，1.3字体竖屏及横屏各复跑上述两项和DemoLayout三项。核心算法、资源逻辑与Web未修改，不重跑其全量用例。相机原有间歇停止计数失败不纳入通过范围。测试前备份用户文件和显示设置，结束核对恢复，不保存人脸帧。结果与证据见固定报告。

---

# 2026-10-28：实验室面板增量回归

选择原因：CameraTestActivity/layout影响统一效果入口、BottomSheet与分类；ResourceCenterController/Model影响资源选择/取消、美化开关及主题组合，因此回归资源、场景、主题与持久化消费者，保留首页和手势冒烟。SDK算法未变更，不重跑其全量像素/JVM测试；Web没有改动。

| 用例 | 直接验证 | 设备方法 |
| --- | --- | --- |
| LAB-UX-01 | 默认预设、选中启用/再次取消、主题取消无残留 | LabInteractionDeviceTest.presetsAdjustCancelAndReopenUseOneClearPanel |
| LAB-UX-02 | 微调隔离、高亮及参数View复用、关闭重开、重置取消保留状态 | 同上；GpuPixelControlsDeviceTest与SceneControlsDeviceTest验证确认重置 |
| LAB-UX-03 | 固定分类/模式、八分类内容与资源管理隔离 | EffectPanelDeviceTest |
| LAB-UX-04 | 真实窗口滑动、内容滚动且面板不拖动 | SheetScrollDeviceTest；等待入场动画后使用可见窗口交集坐标 |
| LAB-UX-05 | 资源、主题、场景、持久化及核心导航消费者 | 本轮汇总中的其他10类；合计32项 |
| LAB-UX-06 | 1.3字体竖屏、1.3字体横屏 | LAB-UX-01/03/04各跑1轮，并查看纯控件截图 |

所有设备操作指定Pixel 8，不清库、不卸载。测试前用run-as备份files/shared_prefs；测试后核验5份偏好/默认/草稿及恢复字体、方向、常亮。只绘制面板View，不保存实时人脸画面。构建、lint、UI/i18n门禁及证据位置见固定报告。旋转重建、进程死亡、完整语言/日夜/多机视觉矩阵未执行，关闭重开通过不外推为这些场景通过。

---

# 2026-10-28：六类效果真机回归

用户要求亲自测试美颜、美型、美妆、贴纸、运镜和背景，故扩大到全部81个已有设备用例及五模块155项JVM；与上一轮21项UI/资源增量区别记录。现有设备测试只保存公开fixture/纯UI，不录制或保存实时相机图像。

| ID | 实测方法/断言 | 不覆盖 |
| --- | --- | --- |
| EFFECT-BEAUTY | GpuPixelDeviceTest真实像素/alpha/GL状态、GpuPixelControlsDeviceTest实际相机输出 | 真人肤质审美 |
| EFFECT-RESHAPE | extendedColorAndReshapeChangePixelsAndRespectMissingFace、customShapesHaveSignedDirectionAndRotateWithRawFrames、actualPortraitMakeupUsesDetectedLipAndCheekLandmarks | 多人/大侧脸画质 |
| EFFECT-MAKEUP | localMakeupPreservesMouthInteriorAlphaAndHostState、实际人像与3套PNG、EyeMakeupResourceDeviceTest | 真人闭眼/遮挡视频 |
| EFFECT-STICKER | BeautyDeviceTest、FaceMotionDeviceTest、StickerResource/WebStickerPack/ResourceCenter测试 | 大幅快速运动/出入画面观感 |
| EFFECT-MOTION | SceneDeviceTest、SceneControlsDeviceTest、BeautySceneTest全模式方向镜像裁剪 | 真实跟拍观感 |
| EFFECT-BACKGROUND | SceneDeviceTest真实分割/合成、SceneControlsDeviceTest相机蒙版就绪 | 发丝及动态拖影 |
| CAMERA-STOP-STABILITY | cameraPageProducesProcessedImageAndStopClearsIt，GPU启动用例配对重复5轮 | 用计数推断实际编码或远端行为 |

相机测试前先备份偏好、资源状态和草稿，确认唤醒/权限/前台；临时常亮结束恢复原值。旧相机用例改为等待统一资源模型并显式选择眼镜，避免隐藏控件不再驱动资源而导致假超时，输出和停止断言保留。发现业务异常仅记录，本轮不自动修改业务实现。失败的启停用例不推进自动通过基线，其余用例和JVM以本轮工作区指纹记录。

---

# 2026-10-28：资源调参效率增量

范围：ResourceCenterModel资源快照与释放队列、ResourceCenterController封面缓存；未修改Web或渲染/检测算法。

| ID | 预期 | 验证 |
| --- | --- | --- |
| DEMO-PARAMETER-CARDS | 连续40次调参及跨字段输入保存末值；封面和卡片对象复用；调用方修改Map不影响已提交输入 | ResourceCenterUiDeviceTest.rapidParametersKeepResourceCardsAndCallerInputStable |
| DEMO-RESOURCE-REFRESH | 另一管理器删除/重新安装后refresh更新列表和封面；主题拆分仍刷新子资源 | 同上、ResourceCenterDeviceTest、ThemeDemoDeviceTest |
| DEMO-RESOURCE-INTEGRATION | 内置删除后重开、完整包导入/显式默认、背景运镜与美颜配置、底部面板、首页路由 | ResourceCenterUiDeviceTest、ResourceDeliveryDeviceTest、SceneControlsDeviceTest、GpuPixelControlsDeviceTest、EffectPanelDeviceTest、HomeNavigationDeviceTest |

采用增量设备回归；先构建Debug/测试APK、lint和UI门禁，再在Pixel 8 / API36覆盖安装执行。已有用户配置先备份，测试后核对恢复。未改布局/文案，不重复完整语言与视觉矩阵；真人动态画质、持续帧率/温升、厂商和iOS不以本次UI调参结果替代。Web及核心算法沿用各自历史基线，不冒充本次重测。

---

# 2026-10-27 Web / APK 全功能回归补充

本轮用户要求分析两端页面/功能、真机测试并修复，采用现有全部可执行用例，而非只按单文件增量。主测 Pixel 8 / Android 16 / API36，覆盖安装 Debug，不清应用数据。原相机权限已授予并保持，设置/草稿先备份后恢复；仅保存首页、独立资源页、纯控件面板，不保存相机图像。

| 用例 ID | 源码 / 操作 | 预期 |
|---|---|---|
| WEB-DOWNLOAD-REAL | bundle.mjs；浏览器点击两种导出 | 必须拿到实际文件路径，回读 ZIP 验证，而非仅看成功文案 |
| WEB-DELIVERY-DRAFT | resource-studio.mjs；交付语言 en/FIXED → 无修改导航往返 | 设置保持；真正修改资源/搭配才更新来源版本 |
| SDK-DELIVERY-IMPORT | ResourceDeliveryDeviceTest | Web buildDelivery 的27资源/5默认夹具经公共库安装/组合，重复导入复用 |
| SDK-DELIVERY-DEFAULTS | ResourceDeliveryDeviceTest；独立资源页导入→显式应用→重开 | 导入不启用，按钮应用语言/搭配，重开保持 |
| SDK-DELIVERY-REJECT | ResourceDeliveryDeviceTest | 坏摘要/缺默认引用不得部分安装；不放宽单包预算 |
| DEMO-BUILTIN-DELETE | ResourceCenterUiDeviceTest | 删除内置后重开不会自动装回，测试后原样重装测试项 |
| DEVICE-PUBLISHED | PublishedCatalogDeviceTest | Pixel8真实HTTPS目录、封面和整包下载，摘要与内容加载正确 |
| DEVICE-ALL | app/src/androidTest 现有全部类 | 帧/检测/GL/美妆/贴纸/背景/运镜/手势/资源/页面全回归 |
| JVM-ALL | 五个业务库 testDebugUnitTest | Test outputs.upToDateWhen=false，真实重新执行155项 |
| UI-FONT-ORIENTATION | 资源页横屏1.3字体、首页/相机控件竖屏1.3字体 | 可进入/操作，查看截图；测试后恢复字体和方向 |

详细功能边界与结果见 WEB_APK_REVIEW.md；仍未执行真人动态画质、30分钟温升、直播厂商远端、iOS及Release/R8，不因自动化全绿扩展为这些项目通过。

---

# BeautySDK 功能验证计划

自定义相机增量：CAMERA-BUILD-01 覆盖库 Debug/Release、宿主 Debug/测试/未签名Release、lint及 native 对齐；CAMERA-PACK-01 覆盖 RgbaPackingTest 的 position/crop/stride/通道/边界与配置；CAMERA-DEVICE-01 覆盖前后摄、独立RGBA内存、会话切换、停止和重启；CAMERA-DEVICE-02 覆盖真实相机到CPU美颜测试页与停止清图。相机权限由宿主声明与请求，设备自动测试前用 pm grant 临时授权，测试后恢复原状态，不清数据、不保存相机画面。

当前阶段：MediaPipe + GPUPixel 美颜与挂件原型。模块：共享 lib_beauty、MediaPipe 后端及 app/core/feature 宿主。没有具体直播引擎适配。
环境：JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.10、compile/target 36、minSdk 24。

| ID | 源码/依赖 | 方法与预期 |
| --- | --- | --- |
| SDK-01..07 | BeautyProcessorTest、JavaApiTest、VideoFrame | 原有生命周期、时间戳、线程、RGBA stride/position、纹理描述边界及 Java API JVM 回归 |
| PIPE-01 | BeautyPipelineTest / RgbaBeautyRenderer | CPU 输出独占、不修改输入、保留 alpha/元数据、紧凑 stride |
| PIPE-02 | AnalysisImage / AnalysisOrientation | RGB 复制处理 position/padding；四种旋转乘两种镜像的像素中心逆变换正确 |
| PIPE-03 | StickerGeometry | 使用原图宽高计算角度和长度；缺少上唇时不伪造胡子 |
| PIPE-04 | AsyncFaceAnalysis | 忙时丢帧、reset/close 丢弃晚到结果、超时/未来结果/几何不符不可用、后端释放 |
| PIPE-05 | BeautySession | 重新启动可重置时间戳、渲染异常诊断与旁路 |
| DEVICE-MP | BeautyDeviceTest | 官方公开人像检出单脸、478 点和语义锚点，资源释放 |
| DEVICE-GL | BeautyDeviceTest | ES3 pbuffer 16x16 RGBA；GL/CPU 美颜挂件逐通道误差 <=2；viewport、纹理单元、颜色掩码、scissor 状态恢复 |
| DEVICE-UI | BeautyDeviceTest | 启动真实 MainActivity，旁路检查通过、点击默认方案处理合成图片并得到输出 |
| BUILD-01 | 全部库及宿主 | 现有库 Release AAR、Debug/测试 APK、未签名 Release/R8；许可文本包含于后端 assets |
| STATIC-01 | 结构、资源、构建 | 四模块 lint、代理配置、架构、UI/i18n 静态检查 |
| NATIVE-01 | 最终 APK 的 .so | 逐 ABI ELF PT_LOAD 对齐、Release zipalign -c -P 16 4；不等于 16KB 设备运行 |
| FUTURE-01 | 厂商适配 | 选定 Zego/声网版本，核实编码前可回写接口、预览/远端输出一致、纹理生命周期 |
| FUTURE-02 | 设备/性能 | OES、真实纹理裁剪矩阵、EGL context 丢失、YUV、快速转头/遮挡、实际五点权重、30分钟持续性能 |
| FUTURE-UI | Demo | 图片选择器完整往返、多语言、日夜、大字体、小屏、横竖屏和 Insets 视觉验收 |

测试只使用合成帧和公开官方 fixture，不读取设备用户照片，不清数据、不卸载；多设备操作必须明确 serial。图片来源及校验见 doc/third-party/MODELS.md。

命令：

```powershell
python scripts/verify_models.py
python scripts/prepare_test_fixture.py
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty_rfb:testDebugUnitTest :core:lib_beauty:assembleRelease :core:lib_beauty_mediapipe:assembleRelease :core:lib_beauty_rfb:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :core:lib_beauty:lintDebug :core:lib_beauty_mediapipe:lintDebug :core:lib_beauty_rfb:lintDebug -PrequireReleaseSigning=false --console=plain
# 对选定的设备安装两个 APK 后执行：
adb -s <serial> shell am instrument -w com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B .agents/scripts/validate_android_workflows.py --project-root . --config-only
python .agents/skills/android-project-architecture-workflow/scripts/validate_architecture.py --project-root .
python .agents/skills/android-ui-workflow/scripts/validate_ui_output.py --module-src app/src/main/java --module-src feature/feature_app/src/main/java --module-res app/src/main/res --module-res feature/feature_app/src/main/res --module-res feature/feature_res/src/main/res
python .agents/skills/android-i18n-workflow/scripts/validate_i18n_resources.py --res-dir app/src/main/res --strict-missing
```

核心/算法/Gradle 改动覆盖对应 JVM、后端设备测试、库与消费者构建；只改许可文字时重新打包检查，无须重复推理。旧的本地 Maven 发布记录仅属历史通过未复测；新增后端未配置发布。

相机本轮命令：

```powershell
.\gradlew.bat :core:lib_camera:testDebugUnitTest :core:lib_camera:assembleDebug :core:lib_camera:assembleRelease :core:lib_camera:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease -PrequireReleaseSigning=false
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell pm grant com.beautysdk.demo android.permission.CAMERA
adb -s <serial> shell am instrument -w com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner
```

权限拒绝/永久拒绝UI、镜头不存在、旋转/后台返回/配置重建的完整视觉矩阵、真实人脸动态佩戴、长时间运行与GL纹理输出仍需后续设备验收。原有相机骨架的“未依赖宿主”条件已不适用。

## 两库增量回归

- BEAUTY-SAMPLING：BeautyPipelineTest.analysisSamplingAndTextureGeometryShareOneTimeline；分析频率独立于渲染，方向变化不清除新分析结果，重启允许时间戳重新开始。
- RFB-LIFETIME：AnalyzerLifetimeTest 两用例；关闭幂等、关闭后拒绝推理、非所属线程不能释放。
- 共享 API 或 RFB 模型契约变更：执行两库 JVM/Release/Lint、Demo Debug 构建，并在可用 arm64 设备跑 BeautyDeviceTest 的模型与离屏 GL 三项；无 UI 修改时不重复相机采集测试。

## 礼帽挂件回归

- STICKER-HAT：BeautyPipelineTest.hatTracksEveryOrientationAndMirrorAndHidesWithoutEyes；四旋转×两镜像，帽身朝向额头、下巴不覆盖，无眼睛隐藏，CPU 颜色和输入不可变。
- GL-STICKER：BeautyDeviceTest.glMatchesCpuAndRestoresHostState 增加 hat=true 并断言确实命中礼帽像素，再比较 CPU/GL 全通道误差及宿主状态恢复。
- UI：静态图与相机页新增独立礼帽开关，默认/英文/简体中文资源一致；页面视觉、小屏、字体放大及相机实时佩戴效果待设备交互验收。

## PNG 素材接入增量回归

- PNG-ORIENTATION（ImageStickerTest）：非对称四象限素材，0/90/180/270 × 前摄镜像；确认左右和上下都跟随显示坐标，输入不可变。
- PNG-ALPHA（ImageStickerTest）：透明底两层半透明 source-over，确认 RGB/alpha 数值、无脸与缺上唇隐藏、画面外保持透明。
- PNG-OWNERSHIP（ImageStickerTest）：像素/层列表防御性复制，超过三层拒绝。
- PNG-DEMO（BeautyDeviceTest.demoLoadsAllSixPngAssetsAndCompositesActualPortrait）：通过公开静态人像和 Demo 真实 manifest 加载链路跑三组预设，覆盖六素材，只有 PNG 且关闭美颜时仍有像素变化。
- PNG-GL（BeautyDeviceTest.glMatchesCpuAndRestoresHostState）：六素材三组组合、原程序化效果和空包切换；保持同一渲染器以覆盖缓存替换/清除；逐通道 CPU/GL 误差≤2；额外验证纹理单元1—3和像素解包状态恢复。
- PNG-CAMERA（CameraDeviceTest.cameraPageProducesProcessedImageAndStopClearsIt）：MediaPipe 后端、爱心眼镜和猫耳配置下，出现输出并可停止清空；不把输出存在等价为真实人脸动态试戴视觉合格。
- 静态检查：UI/i18n/架构。构建两后端与共享 AAR、Demo Debug/Test APK、Lint，宿主 unsigned Release/R8。

真实人脸快速转头、遮挡、远端推流与温升/FPS仍为未验证，不从离屏 GL 测试推导持续实时能力。


## GPU 实时相机与演示页回归（2026-10-23）

- GPU-MATRIX：CameraTextureTransformTest；ST/crop乘法顺序、非对称裁剪、四旋转×两镜像逆映射、输入不可变与非法crop。
- GPU-RESULT：PreviewTrackingTest；同ViewPort跨分辨率归一化锚点、150ms边界、过期/未来/异会话/方案/旋转/镜像/比例拒绝、无脸与错误清空。
- 受影响消费者：共享SDK20项、相机打包与坐标8项、RFB4项、feature跟踪3项JVM；宿主Debug/Test APK与Lint、UI/i18n/代理配置静态检查。
- GPU-DEVICE（待执行）：CameraDeviceTest更新为GPU出帧计数和停止清空；DemoLayoutDeviceTest覆盖分类面板、6素材互斥/取消及主副画面交换。设备脚本已更新但新版未通过验收。
- GPU-VISUAL/PERF（待执行）：前后摄横竖屏、四旋转与镜像、裁剪视场、无脸/遮挡/快速移动、字体1.6/日夜主题/小屏、重启/后台恢复/context丢失、30分钟持续运行。性能固定设备/分辨率/后端，比较旁路、检测、检测加合成、美颜叠加，记录平均/p95与FPS、内存温升；仍需独立验收直播远端输出。
- 用户明确要求先分析处理、不做真机测试：本轮不推进任何GPU设备通过基线，旧CPU或离屏GL历史结果不得替代新相机管线验收。
# GPUPixel 增量验证（2026-10-23）

范围：core/lib_beauty_gpupixel、feature_app/GpuBeautyPreview、CameraDemoOptions、CameraTestActivity 和美颜后端选择资源。用户本轮继续排除真机测试；不查询设备、不安装、不运行 connected 测试。

| ID | 方法 | 预期 | 本轮范围 |
|---|---|---|---|
| GP-SOURCE-01 | `python scripts/verify_gpupixel_sources.py` | 固定 SHA256、两段原始 Shader、打包许可证匹配 | 执行 |
| GP-BUILD-01 | 新模块 assembleDebug/assembleRelease，app assembleDebug | 两个 ARM ABI 编译与打包通过 | 执行 |
| GP-R8-01 | app assembleRelease，显式 `-PrequireReleaseSigning=false` | 未签名 release/R8 保留 JNI 方法 | 执行，不发布 |
| GP-ABI-01 | llvm-readelf LOAD 和 zipalign -P16 检查 | 新增 .so 16KB ELF 对齐及 APK zip 对齐 | 执行，不等于16KB设备运行 |
| GP-REG-01 | 既有 lib_beauty、lib_camera、lib_beauty_rfb、feature_app JVM | 共享逻辑35项不回归 | 执行 |
| GP-GL-01 | GpuPixelDeviceTest | 2D效果、alpha、矩阵、纹理复用、美白单独分支和宿主GL状态 | 只编译，未执行 |
| GP-UI-01 | XML/i18n静态及app Lint | 后端选择与中英文资源可编译 | 执行；小屏/字体/视觉未验收 |
| GP-CAM-01 | 相机页两种美颜后端，三种检测后端，贴纸/启停/切摄/后台 | OES坐标正确、无崩溃、异常显式旁路原图 | 未执行 |
| GP-PERF-01 | 固定设备/分辨率/参数对比GPU执行/结果年龄/尾延迟/内存温升 | 得到真实效果与性能结论 | 未执行 |

设备用例不能使用编译成功作为通过基线；JNI 源码与 Shader 编译属于不同验证阶段。旧静态图片页仍是 CPU 参考，不作为 GPUPixel 生效证据。



## 效率优先增量用例（2026-10-23）

- EFF-01：RgbaPackingTest 的直接只读 buffer、行 padding、裁剪、position/limit、连续行及非4 pixel stride；修改批量复制后执行 lib_camera JVM。
- EFF-02：PreviewRequestTest 验证无挂件、各类有效挂件、style=0、原图及 BEAUTY_ONLY 的检测门控；执行 feature_app JVM。
- EFF-03：共享美颜和预览结果有效期现有 JVM 回归；app Debug 构建检查新配置与资源。
- EFF-04：GL uniform 缓存、快速关闭/开启挂件和原图、切相机及后台恢复的视觉/性能回归；本轮按用户要求排除设备测试，不推进设备基线。


## 默认实时贴纸主线

用户确定 MediaPipe + BASIC_GL 为默认实时验收组合；CameraTestActivity 和 GpuBeautyPreview 的当前默认值一致。静态 CPU 页仅作算法/素材参考，RFB/GPUPixel 为可选对照。

贴纸用例必须实际开启至少一层素材，不能仅选择 MediaPipe 或素材款式就声称已覆盖模型推理。无真实人脸时，只能验证出帧和生命周期，不能声称完成贴合或跟随验收。


## 贴纸时间戳闪烁增量（2026-10-23）

- FLICKER-01：FaceAnalysisHistoryTest，分析先到/预览后到时选取最近非未来结果；未来无脸/错误不能提前隐藏，生效后不能回退有脸；严格过期、容量、乱序、方向/镜像/尺寸和clear。
- FLICKER-02：PreviewTrackingTest，跨分辨率共享ViewPort、配置代次/相机会话/后端隔离及未来结果回退。主线150ms有效期不变。
- FLICKER-03：OPPO设备执行CameraDeviceTest#cameraPageProducesProcessedImageAndStopClearsIt，确实开启MediaPipe贴纸并等待分析诊断；无脸也可验收出帧/模型执行，不能据此验收真人跟随。
- FLICKER-04：BeautyDeviceTest#glMatchesCpuAndRestoresHostState 与 #asyncCameraCadenceKeepsStickersThenHidesExpiredResults；不以CPU参考时延代表GPU主线。
- FLICKER-05：真人快速运动、遮挡、进出画面、持续闪烁观察；需实际人脸场景，本轮未执行，不推进视觉基线。


## 2026-10-23 直接缓冲输入增量

- DBUF-01：ReusableRgbInputTest，验证带 position/padding 的 RGBA 缩小及四旋转×两镜像与原路径逐字节一致，源不变、原生缓冲复用。
- DBUF-02：BorrowedCameraFrameTest，验证借用回调生命周期、stride 与旧独占帧契约。
- DBUF-03：BeautyDeviceTest.mediaPipeDetectsActualPortraitAndReleases，真实模型交替接收直接 RGBA 和旧 RGB；反复关闭 MPImage/复用内存仍输出478点和挂件锚点。
- DBUF-04：CameraDeviceTest.cameraPageProducesProcessedImageAndStopClearsIt，实际 CameraX 借用输入、MediaPipe、基础 GL 与停止回归；frontBackFramesRestartAndStop 覆盖默认独占输入兼容。
- 模块：lib_camera、lib_beauty、lib_beauty_mediapipe、feature_app、app；没有改动 RFB/GPUPixel 算法或引擎适配。
- 不以这些断言代表快速转头、实时跟随、持续性能或远端推流通过。

- DBUF-05：BeautyDeviceTest.nativeRgbaMatchesReferenceForAllOrientationsAndStride，C++ 与 Kotlin 像素参考逐字节比对四旋转×两镜像，验证 position、padding、缩小、复用以及目标容量不足拒绝写入。新增 native 后追加 AAR、ABI/ELF对齐与宿主 unsigned release/R8 检查。


## 2026-10-26 统一SDK输入

- UNIFIED-01：UnifiedInputTest.engineCanReleaseSourceImmediatelyAndWorkerUsesDirectRgba。回调返回即可覆盖源缓冲；SDK worker使用独占直接缓冲，忙时不覆盖，旧代次提交被拒绝。
- UNIFIED-02：UnifiedInputTest.publicSessionSharesSmallAnalysisWithLargeTextureWithoutInventingDimensions。公共Session支持同视场小图/高清纹理，stride复制正确，不伪造尺寸，不匹配方向/宽高比拒绝复用。
- 回归共享历史、异步reset/close、Java API、全部相机与feature JVM；设备验证相机Demo走公共worker、真实模型直接RGBA、GL状态、启停/前后摄、异步贴纸超时。
- ZEGO/声网尚未固定版本，真实厂商回调和远端推流未执行；模拟源缓冲覆盖不能替代这些集成测试。


## 最新候选调度回归（2026-10-26）
- PENDING-01：UnifiedInputTest.latestPendingReplacesIntermediateFramesWithoutOverwritingActiveInput。阻塞第一帧并连续提交第二、第三帧；只分析第一、第三帧；宿主修改第三帧源缓冲不能影响分析数据。
- PENDING-02：UnifiedInputTest.resetDropsPendingAndOldCompletion。分析期间有候选，重置并关闭后不执行候选、不返回旧结果。
- 影响范围：AsyncFaceAnalysis、GpuBeautyPreview；执行共享库与 feature JVM 测试和 app debug 构建。设备持续性能、GPU delegate 对照与帧间跟踪未覆盖，不据此判定闪烁已修复。


## GPU delegate 与全量窗口计时（2026-10-26）
- DELEGATE-01：MediaPipeDelegateDeviceTest.compareCpuGpuOnSameDirectInput：固定人像240x300 Direct RGBA，CPU/GPU/GPU/CPU，5次预热+40计时/轮；断言每帧单脸478点与上唇，最后鼻点偏差<0.03。记录初始化及total/prepare/infer的mean/p50/p95/max；不验证运动和实时美颜争用，不用耗时硬阈值断言。
- TIMING-01：AnalysisTimingWindowTest：慢帧不被逐秒日志抽样遗漏，缺失后端阶段不当成0，clear清空统计。
- 影响：MediaPipeFaceAnalyzer、CameraDemoOptions、GpuBeautyPreview、CameraTestActivity及调试弹框。默认CPU不变；GPU显式失败显示错误，不静默降级。

- DELEGATE-02：cameraGpuOptionProducesAnalysisWhileRendering，程序切换GPU和眼镜，等待相机基础GL主线返回READY/NO_FACE且无错误；仅启动冒烟。


## 显式分辨率/裁剪映射（2026-10-26）
- MAPPING-01：AnalysisFrameMappingTest五项，覆盖明确裁剪/留边、四方向和镜像、裁剪外人脸/锚点、公共session采样年龄及映射缓存/切换失效、非法区域。
- RESOLUTION-GL-01：ResolutionDeviceTest.glReallocatesOutputAcrossResolutions，纯色输入640x480→1280x720→720x1280→1920x1080→640x480，同一GL renderer连续处理，验证输出元数据、FBO完整性、新右下角像素与GL错误；不采集人脸。
- 回归共享库和feature JVM，app debug/androidTest构建；真人运动与跨分辨率磨皮观感、厂商实际回调和远端输出不在该断言范围。


## 实验帧间图像跟踪（2026-10-26）
- MOTION-CORE-01/02：TrackedFaceAnalysisTest，延迟模型时新图像跟踪可补位；不能超过原检测300ms校正上限，模型无脸/重置失效；公共BeautySession开启跟踪并限频模型时，跟踪仍更新。
- MOTION-NATIVE-01：FaceMotionDeviceTest，公开测试人像真实478点，已知灰度图平移，验证鼻点偏移及空白遮挡拒绝；记录15次native匹配耗时。只测平移，不替代真人快速侧脸/遮挡/缩放/旋转矩阵。
- 原有nativeRgbaMatchesReferenceForAllOrientationsAndStride及cameraGpuOptionProducesAnalysisWhileRendering回归；新增native库两ABI的Release AAR与ELF16KB检查。Release宿主R8和长时间帧率/内存/温升另行验证。

## 跟踪参考交接回归（2026-10-26）

- MOTION-HANDOFF-01：200ms跟踪已完成，100ms模型随后返回，继续使用200ms坐标。
- MOTION-HANDOFF-02：200ms匹配执行中更新100ms参考，匹配完成后仍可发布。
- 两项均验证原始参考300ms截止、新无脸撤销、重新检出隔离及reset清理。
- 真人视觉仍需覆盖静止、转头、遮挡；本轮不承诺修复匹配失败或算法噪声产生的所有跳动。

## 贴纸短暂失败保留与姿态平滑（2026-10-26）

- STICKER-STABLE-01：跟踪成功后匹配失败，150ms帧有效期与300ms参考上限内保留成功位置；不刷新时间戳，新无脸/reset仍清理。
- STICKER-STABLE-02：细小中心/大小/角度变化保持显示并平滑，明显移动直接跟随。
- STICKER-STABLE-03：640/1920分辨率相同比例位移结果一致；不同渲染帧率在相同时间内响应一致。
- STICKER-STABLE-04：缺锚点、几何变化、时间回退、显式clear后不沿用旧姿态，±π角度走最短路径。
- 设备回归：OpenGL与CPU固定输入结果/宿主GL状态恢复，相机GPU检测与GL渲染启动冒烟。真人持续闪烁及平滑延迟须另行视觉验收。

## 标准资源包与工坊（2026-10-26）

- RESOURCE-01：6个标准包读取、修改布局、完整ZIP/JSON往返；图片对象复用。设备夹具只使用内置素材。
- RESOURCE-02：非法路径、缺失/多余PNG、重复条目、未知字段/版本/锚点、非法数字、过大条目、损坏PNG、APNG明确拒绝。
- RESOURCE-03：工坊加载样例，经无障碍滑杆修改宽度，ContentResolver导出ZIP/JSON、重新导入；错误导入保留旧资源，Activity重建保留ViewModel。I/O使用本轮独占cache文件并清理。
- RESOURCE-04：偏移/旋转跨方向、镜像和分辨率，默认布局不变，缺锚点隐藏；公开参数校验及配置不可变。
- RESOURCE-05：自定义资源驱动按需检测，空包关闭检测，原图/基础后端跳过分析。
- 核心冒烟：GL固定图像CPU对照（新增偏移/旋转资源案例）、状态恢复，相机GPU检测+GL启动。
- 静态门禁：UI、i18n、架构；Gradle库单测、消费者单测、Release AAR、Debug APK/androidTest和Lint。
- 手动剩余矩阵：系统文件选择器实际选文件与保存位置、小屏幕/大字体视觉、长名称显示、真人贴合与持续性能；基础静态检查不能替代上述验收。

## 导航与 Demo UI（2026-10-26）

- NAV-01：首页实时美颜/工坊/图片实验室/开发工具各自导航；首次进入无相机权限提示，只有进入相机才请求。当前导航视觉及完整逐入口验证待验收。
- UI-IMAGE：原图片页面迁移后，图片处理、帧检查与原图切换/滚动预览保持通过现有设备用例。
- UI-CAMERA：设置打开关闭不改变预览区域，贴纸选中态、大小画面切换保持通过现有设备用例。
- RESOURCE-03：工坊从独立工具栏入口进入，沿用导入/调参/导出/重建设备回归。
- 未执行：日夜/横竖屏/大字体/小屏完整视觉矩阵；不以构建或 UI 静态扫描代替。

## 工坊工具分区（2026-10-26）

- RESOURCE-03更新：进入工坊不自动加载；主动载入样例后选择布局区，确认素材区隐藏、默认仅大小滑杆显示；继续原有导出导入和重建回归。
- 手动待验收：六参数切换、三个分区在小屏/横屏/字体放大下的完整视觉；不以设备夹具通过代替视觉验收。

## 公共SDK收敛（2026-10-26）

- SDK-CORE-01：BeautyRuntimeTest，基础美颜不运行模型；贴纸需求切换作废旧结果，跟踪组合传递analysisMaxSide。
- SDK-CORE-02：同采样帧redraw不改时间戳且不能重绘其他时间/几何；借用分析运行时不随渲染会话关闭。
- SDK-CORE-03：有界P95/P99、缺失阶段unavailable、重绘与视频帧分离、单帧窗口不报告FPS。
- SDK-RESOURCE-06：独立草稿命名空间验证参数原子保存、新ViewModel恢复默认关闭；载入后清除不能被晚到完成动作撤销。
- SDK-PIXEL-01：CPU/GL双线性采样对照原有严格2通道差断言，覆盖偏移/旋转/镜像及宿主GL状态；不得降低断言绕过错误。
- 受影响消费者：全量核心/feature JVM，Release AAR，Debug宿主/androidTest，Lint；设备执行资源包4项、CPU/GL及过期2项、Camera2项、分辨率1项、GPUPixel1项、Native运动1项、相机GPU delegate1项。
- 本轮排除：用户明确暂不接厂商。无实际引擎/远端结论；持续帧率、热稳态、真人快转/遮挡、完整UI屏幕矩阵仍待验收。
- 数值采集脚本只读固定SDK日志与PSS/电池温度，空日志冒烟不推进性能基线。固定环境三组长时间性能需要独立执行。

## ENGINE-INPUT-01 / ENGINE-OUTPUT-01（2026-10-26）

- JVM：Yuv420InputTest覆盖限量黑白、601/709矩阵、NV21偏移/pixelStride、奇数尺寸/行padding、缩小和借用缓冲复用、容量校验、跨线程幂等归还。
- GL：BeautyDeviceTest#glMatchesCpuAndRestoresHostState增加池满返回null、未归还禁止close、覆盖输入不改变租约像素、归还可复用；保留原CPU/GL一致性和宿主状态回归。
- 本轮不接厂商；未覆盖跨context GPU同步、OES规范化、context丢失或持续编码性能，不将同context夹具当作直播引擎验收。

## ENGINE-TEXTURE-01（2026-10-26）

TextureBridgeDeviceTest：以生成的固定纹理和Surface画布验证GPU缩小/PBO轮询→共享运行时→Session；覆盖2D左右/上下非对称图案及采样矩阵、方向镜像元数据、OES输入、同帧跳过/限频/过期丢弃/方向重置/需求关闭隔离、宿主viewport/scissor/activeTexture/pixelPack恢复。glFinish仅在测试内制造确定完成条件，不存在于生产桥接器。

不读取摄像头或保存用户图像。真人快速运动、MediaPipe联合持续性能、GPU繁忙时尾延迟、跨context同步/丢失恢复、真实引擎远端输出仍不在本用例通过范围内。Demo独立RGBA路径不改动。

## ENGINE-CONNECTOR-01（2026-10-26）

- BeautyEngineConnectorTest：真实CPU美白取帧到写回、原图仍交回、读/处理失败旁路、写回失败不重试、引擎拒绝/旁路失败、换源时间线、错误线程/启停/重复关闭、重入防双提交。
- JavaApiTest：Java宿主无需Kotlin helper使用泛型适配器和统一入口。
- TextureBridgeDeviceTest#unifiedEngineCallbackProcessesAndWritesTexture：生成纹理通过统一入口实际GPU美白，管线内部小图分析进入共享Session，再按适配器write输出并校验像素，换源可从新时间线开始。
- 所有设备图案均生成，不采集或保存人脸。检测为固定结果夹具，不替代MediaPipe联合性能。仍不含厂商SDK/账号/远端推流；不改变相机Demo独立RGBA默认路径。


## CAMERA-CONNECTOR-01：相机复用公共引擎入口

关联BeautyEngineConnector/Pipeline/Session、GpuBeautyPreview及其消费者。新增JVM验证同帧重绘实际应用美白、不同采样拒绝重绘且不消费新时间线、不支持重绘的处理器可旁路、借用分析跨管线重启/释放仍存活。构建core Release、app Debug/test APK及app lint；Pixel 8执行TextureBridgeDeviceTest四项和CameraDeviceTest两项，覆盖公共纹理入口、实际CameraX双镜头启停和MediaPipe相机页。没有UI布局修改，其他静态图片/资源工坊及全套布局回归保留历史基线。禁止保存相机图像，不清除应用数据；持续真人跟随、热稳态、跨EGL上下文、真实直播厂商与远端画面另行验收。


## STICKER-PERSISTENCE-01：贴纸跨页面进入恢复

相机内置眼镜/胡子/帽子开关与款式持久化；工坊私有草稿保存启用状态，在页面进入时自动恢复。验证CameraStickerPersistenceDeviceTest#reopenRestoresStylesAndClearRemainsCleared（三次独立页面进入），StickerResourceDeviceTest#privateDraftRestoresDisabledAndLateLoadCannotUndoClear（独立草稿跨ViewModel恢复开/关、在途恢复不撤销清除）。测试恢复用户原偏好、删除专用测试草稿，不清应用数据、不保存相机帧。界面布局未改，字号/横竖屏视觉矩阵不在本轮范围；工坊缺失/损坏草稿仍沿用错误展示。强杀/重启设备尚未单独验收。


## STICKER-SAVE-LOAD-01：工坊保存并加载

新按钮在导出分区，已有资源且非busy时可用。冻结素材快照，在单独IO队列通过AtomicFile写入完整ZIP，再通过SDK loadZip重新校验解码，成功才替换预览并启用。saveReloadUsesPersistedImagesAndEditedManifest验证图片和修改参数落盘、新资源对象重载、新ViewModel恢复、busy期间flush不覆盖和清除取消在途启用。回归privateDraftRestoresDisabledAndLateLoadCannotUndoClear、reopenRestoresStylesAndClearRemainsCleared；app构建/测试APK和i18n校验。布局仅在滚动工坊导出分区新增全宽按钮，中英文沿用CameraAction样式；字体缩放/小屏实际视觉矩阵未覆盖。


## FAST-MOTION-01：输入来源无关的快速跟随

关联公共StickerPoseSmoother、BeautyEnginePipeline及mediapipe NativeFaceMotion。JVM：核心全套，包含旧跟踪交接/过期/换源、跨分辨率平滑、平滑阈值连续性、无相机类型的标准帧Pipeline启用跟踪。Pixel8：FaceMotionDeviceTest两项（公开固定人像、已知小平移和超出旧12px窗口的18/8px位移、空白拒绝），TextureBridgeDeviceTest四项（2D/OES和统一Connector/Pipeline）。不录制用户相机帧，不以合成人像位移推断真人快速侧脸通过。构建核心Release及app/test APK。未选工坊/全套布局因无资源/UI修改；持续真人运动、热稳态、真实直播引擎和远端另行验收。


FAST-MOTION-01后续：用户报告真人退化，已撤回两级搜索和连续平滑增益，恢复原位移用例；执行75项核心JVM及5项Pixel8设备测试。公共Pipeline跟踪接口保留。下一次视觉优化必须同设备、同delegate、同分辨率记录有脸运动段并做前后比较，固定平移测试不能作为真人跟随验收。

## GPUPIXEL-EXTENDED-01（2026-10-26）

公共参数中性值/范围/非有限值、调色与美型分析需求；GPU 基础和7项扩展效果、alpha、无脸旁路、2D/OES、FrameInfo与GL状态；Demo后端/参数/重置/美型自动选MediaPipe；回归公共纹理Connector。使用生成纹理和合成关键点，不保存用户人脸。排除真人画质、热稳态、厂商SDK和远端输出，另行验收。

## SCENE-EFFECTS-01（2026-10-26）

影响：公共BeautySettings/GlTextureAnalysisBridge、基础及GPUPixel调用边界、MediaPipe新分割器、相机Demo与XML文案。选择核心/feature JVM、SceneDeviceTest 4项、TextureBridgeDeviceTest 4项、GpuPixelDeviceTest 3项、SceneControlsDeviceTest 1项；这些覆盖原桥与新消费者、2D/OES、真实模型、背景像素和场景参数联动。生成纹理与固定公开portrait.jpg，不保存用户相机画面，不清数据、不操作OPPO。

预期：mask只接受同几何且未过期结果；关闭/重置清除结果；分割忙时不等待；背景全零mask采用替换色/图片、全前景保留源像素与alpha；模糊采样邻域、运镜均匀裁切不越界；纯纹理桥保留原始FrameInfo；UI背景/运镜/倍率参数进入公共SDK且重置可关闭；原有GL状态和GPUPixel继续通过。启动相机UI前须设备已唤醒；不绕过锁屏认证。

执行：与既有单worker低内存Gradle参数一致，testDebugUnitTest、核心/MediaPipe assembleRelease、app assembleDebug/assembleDebugAndroidTest/lintDebug；adb指定Pixel 8安装及上述类instrument。新增SceneControls测试依赖已有相机权限，临时设置不落盘。排除真人视觉、侧脸发丝/遮挡/快速运动边缘、字体/小屏/横竖屏人工矩阵、持续性能、16KB运行、厂商及远端输出；静态/夹具通过不能代替这些验收。

## SHEET-SCROLL-01（2026-10-27）

CameraTestActivity固定展开BottomSheet，禁用整块拖拽及向父层嵌套滚动。真实Window触摸注入验证美颜长内容滚动而弹框顶部不变，空工坊内容可完整容纳时不强迫滚动，上下拖动保持展开；回归GPUPixel与场景控件联动。只构建app Debug/测试APK和UI静态检查；未改SDK渲染，无需重跑算法/性能。小屏/字体/横屏人工矩阵及真人手感仍待验收。

## RESOURCE-WORKSHOP-01 / 2026-10-27

范围：tools/sticker-workshop、resources/stickers/v1、ios/BeautyResources、WebStickerPackDeviceTest。
- Node：6样例ZIP读取/编辑/导出往返、坏包/版本/字段/PNG拒绝、16组旋转镜像宽高几何夹具、单文件脚本与内嵌模板。
- 浏览器：加载模板、编辑名称/角度、实际下载ZIP、导回、添加透明PNG并改上唇锚点；检查窄屏与桌面布局。
- Pixel8：正式SDK加载网页实际下载包和6导出包；回归已有2项加载器测试。不启动相机，不改用户草稿。
- Apple环境：swift test包含ZIP/几何/Metal烟测；本机无Swift/Xcode，未执行。
- 排除本轮性能、真人AR、直播厂商、完整iOS SDK、动画/3D；HTML file协议打开需人工验收（自动浏览器策略禁止file导航）。


## 2026-10-27 场景资源V2增量

- EFFECT-V2-01：网页V2三类型导出/回读、类型/数值/文件引用拒绝；Web `test/effects.test.mjs`。
- EFFECT-V2-02：关键帧循环/停留/插值、切换配置重置及旋转镜像裁切；MotionTimelineTest、BeautySceneTest。
- EFFECT-V2-03：网页夹具→Android公共加载器→按类别合并→ZIP回读；EffectResourceDeviceTest。
- EFFECT-V2-04：背景焦点cover采样、GL状态/透明度与分割；SceneDeviceTest。
- EFFECT-V2-05：Demo资源导入、分类记忆、重入/清除、手动与资源优先级；构建/静态检查，本轮完整人工UI和恢复场景未执行。
- EFFECT-V2-06：Swift加载器/Metal分段处理；新增XCTest，Windows无Apple工具链，标记未执行。

选择核心和feature JVM回归因公共参数及请求调度变更；不接触厂商SDK、RFB模型、追踪算法，不重复历史全量设备用例。保留用户草稿/数据；只对Pixel 8安装覆盖，不操作OPPO。

## THEME-V1 / 2026-10-27

- THEME-V1-01：Web 四类别主题 ZIP 往返，保留子包字节与派生能力；test/theme.test.mjs。
- THEME-V1-02：非法版本、空主题、路径、嵌套、跨类别、缺文件、未知文件及总像素预算拒绝；Node + ThemeResourceDeviceTest。
- THEME-V1-03：Android 先检查能力再原子应用，失败保留旧设置；完整主题切换仅运镜时清除旧贴纸/背景/美颜；设备测试。
- THEME-V1-04：PreviewRequest 主题覆盖旧手动/自定义贴纸需求，原图旁路；feature JVM。回归核心 JVM、V2 资源设备用例及 app lint。
- THEME-V1-05：Chrome 示例组合、内嵌编辑采用、刷新恢复、导出反馈与桌面/窄屏；文件选择因扩展权限未执行，记录阻挡。Demo 完整导入/重启/清除人工矩阵待执行。
- THEME-V1-06：Swift 严格加载与 Metal 组合消费者/XCTest，Windows 无 Apple 工具链，未执行。

仅 Pixel 8 覆盖安装和资源夹具测试，保留用户数据，不启动相机，不触及其他设备。无厂商、持续性能或真人视觉结论。

## PX8-FULL-20261027 全功能回归

用户要求 Pixel 8 全功能测试，扩大至全部现有 JVM、17 个 instrumentation 测试类：真实 MediaPipe/RFB、原生转换、基础GL/GPUPixel、纹理桥、分辨率、相机前后摄/启停、UI布局/弹框/参数、贴纸工坊与记忆、V1/V2/主题资源。执行前已检查测试清理逻辑并本地备份现有 shared_prefs 与 sticker_workshop；结束强停后恢复，禁止清数据或卸载。不保存实时相机图像，不操作其他设备。

本次覆盖现有自动化，并检查首页导航。缺乏真人动作、热稳态长测与厂商配置时分别标记未执行，不以夹具或短时相机测试代替。主题 Demo 导入/重启恢复原先缺少自动化，应补有隔离和恢复的数据闭环测试；测试任务只记录业务缺陷，不直接修改业务逻辑。

本轮新增稳定映射：PX8-THEME-DEMO-01 → ThemeDemoDeviceTest（真实主题导入/帧交付/重入/错误/清除与原资源恢复）；PX8-HOME-01 → HomeNavigationDeviceTest（三入口实际页面及仅首页截图）；PX8-RGB-ALIGN-01 → MediaPipeInputContractDeviceTest 两条公开入口（320对照后318边界），关联 BeautyDeviceTest 静态人像失败。初始DemoLayout假设修正为显式设置贴纸初态，避免持久化选择导致串扰，不改业务逻辑。最终选择全部48项，失败保持基线不推进。

## RGB-ALIGN-FIX-20261027

修复 MediaPipe backend 内部RGB行对齐，原公共紧凑RGB API不变；JNI新增目标行步长，Direct RGBA一次写入复用对齐缓冲，堆RGBA保留参考转换。选择原3项失败路径、MediaPipeInputContractDeviceTest逐像素/复用/输入padding/全部旋转镜像/宽度余数/CPU与GPU奇数尺寸真人像，并重跑全部设备用例覆盖JNI旧调用者、模型/纹理桥、分割、相机及消费者。核心/feature JVM显式重跑，MediaPipe Release AAR、app Debug/测试APK与lint构建，检查本次native ELF对齐。禁止清数据/卸载，仅Pixel8覆盖安装，备份后恢复原偏好和草稿；真人视觉、持续性能、厂商及16KB运行不作为通过项。


## GPUPIXEL-MAKEUP-20261027

新增局部口红/腮红，不加入检测器或厂商依赖，默认基础OpenGL主线保持不变。公共BeautyAdjustments追加默认0参数；分析门控区分美型/贴妆，V2资源协议仍保持原七项且不声称Web/iOS支持新贴妆字段。

- GPU-MAKEUP-01：BeautyAdjustmentsTest，范围/非有限数拒绝、中性值、贴妆单独触发分析但不触发美型。
- GPU-MAKEUP-02：PreviewRequestTest，贴妆独立启用、原图与基础后端不分析。
- GPU-MAKEUP-03：GpuPixelDeviceTest.localMakeupPreservesMouthInteriorAlphaAndHostState，非方形96×128纹理，两个效果像素/强度/唇内留空/alpha/无脸和越界旁路/采样镜像/复用/GL状态，合并磨皮与美型。
- GPU-MAKEUP-04：GpuPixelDeviceTest.actualPortraitMakeupUsesDetectedLipAndCheekLandmarks，公开portrait.jpg→真实MediaPipe478点→GPU局部贴妆；对照图仅含公开fixture，允许保存。OES已有夹具增加直接腮红输入。
- GPU-MAKEUP-05：GpuPixelControlsDeviceTest，口红/腮红参数索引7/8和强度调节、MP选择、重置；相机帧不保存。

先GPU专项，再完整设备回归以覆盖公共配置、主题、场景及相机消费者；核心/feature JVM、GPUPixel Release AAR双ABI、app Debug/测试APK/lint、结构与UI/i18n检查，原生ELF静态16KiB检查。Pixel8覆盖安装，先备份并最后恢复偏好与工坊草稿；不清数据、不触及其他设备。未执行真人动态观感、遮挡/侧脸、长时温升、厂商SDK及远端、iOS或Web资源扩展、16KiB设备运行。


## GPUPIXEL-CUSTOM-SHAPES-20261027

用户确认两类都要，在口红/腮红基础上补充BeautySDK自定义瘦鼻、下巴长短、嘴部大小；不以自定义算法冒充上游原生能力。SDK参数默认0，不改变基础主线，合并现有美型pass，未扩展V2资源或Web/iOS。

- GPU-SHAPE-01：BeautyAdjustmentsTest追加范围/非有限数/分析需求/独立能力测试；PreviewRequestTest覆盖三项单独启用、原图与基础后端旁路。
- GPU-SHAPE-02：GpuPixelDeviceTest.customShapesHaveSignedDirectionAndRotateWithRawFrames。96×128生成纹理上断言瘦鼻、正负下巴、正负嘴型的实际像素位移方向、局部边界、alpha、缺点/退化区域独立旁路、非方形帧纹理和关键点真实旋转90°后的逐像素等价（容差2）。
- GPU-SHAPE-03：公开portrait.jpg经真实MediaPipe检测，原图/口红/腮红/瘦鼻/下巴/嘴部6列对照；只保存公开fixture，不保存相机帧。直接OES扩展仍走共享变换契约。
- GPU-SHAPE-04：GpuPixelControlsDeviceTest覆盖参数索引9/10/11，双向滑杆、自动MP选择和中性重置；不增加滚动容器。

验证选择GPU+参数页面7项专项后，全部53项设备回归覆盖公共配置、主题、相机、场景及纹理消费者；核心/feature JVM、双ABI Release AAR、app Debug/测试APK/lint、结构/UI/i18n与ELF静态检查。Pixel8覆盖安装，备份/恢复原偏好草稿，禁止清数据、卸载或操作其他设备。排除真人快速动作/侧脸/遮挡自然度、长时间性能/温升、实际厂商远端、Web/iOS新资源字段和16KiB运行。


## MAKEUP-ENTRY-20261027

修复用户找不到美型/美妆入口：默认基础OpenGL不改，在美颜面板顶部新增显式入口，点击自动选择GPUPixel、选择口红并滚动到参数。复用既有滚动容器与样式，中英文资源同步。GpuPixelControlsDeviceTest改为从默认隐藏状态点击真实入口，断言后端1、参数7、12个参数、区域可见，并继续参数/正负/重置和真实GPUPixel相机帧验证。只执行该项受影响设备用例及Debug/测试APK构建、UI/i18n静态；不重跑未修改的SDK/算法/全部矩阵。备份恢复Pixel8偏好草稿，不操作其他设备，不保存相机图像。


## HIDE-BASIC-20261027

按用户要求隐藏Demo基础OpenGL及整个后端选择控件，默认GPUPixel，扩展参数直接可见。更新AGENTS默认约定，SDK基础实现保留。GpuPixelControlsDeviceTest断言选择器GONE、初始GPUPixel和扩展区VISIBLE，再回归入口、12参数、正负调节、帧交付和重置。仅该设备用例及Debug/测试APK、UI/结构检查；未修改SDK算法，不重复全量/长时/厂商测试。备份恢复Pixel8现有偏好草稿，无清数据或其他设备操作。

## 2026-10-27：资源工作台增量

- RESOURCE-V3：EffectResourceDeviceTest / Web beauty-v3.test，覆盖旧 V2 兼容、12 项美颜往返、有符号数、非法版本/范围拒绝及内置完整主题。
- RESOURCE-DOWNLOAD：ResourceStoreDeviceTest，隔离私有缓存，验证摘要/大小/真实包解析后提交、离线目录和 ZIP、坏摘要、解析拒绝保留旧版本、非法目录。
- UI-CATEGORIES：EffectPanelDeviceTest、GpuPixelControlsDeviceTest、SceneControlsDeviceTest、SheetScrollDeviceTest，真实可见分类切换、参数调节、共享面板、资源高级入口与手势；复用 ThemeDemoDeviceTest 覆盖主题导入/恢复/坏包/清除。
- SDK-METRICS：BeautyPerformanceMeterTest，已知时钟下 FPS/CPU 均值/重置与 GPU 未知值；BeautyEnginePipeline 记录 process，不计 redraw。
- 首轮共享管线/Manifest/UI 改动扩大为全量 57 项。修复后复测受影响 UI 7 项，最终加载/主题/面板 11 项；另在 1.3 字体/夜间模式复测 5 项，恢复用户状态。不把集合通过称为最终源码全量一次通过。
- Web 在两种视口和最终 dist 进行可见页面检查，V3 JSON Schema 同步到 protocol。iOS 编译、横屏矩阵、持续性能和厂商远端不在本轮实测结果内。


### 2026-10-27 睫毛与贴妆增量

影响：BeautyAdjustments人脸调度、GPUPixel JNI/Shader/顶点布局、相机美妆参数。选择BeautyAdjustmentsTest、GpuPixelDeviceTest全类（实际公开人像+合成图、2D/OES、旋转镜像、GL状态、alpha、缺点、形变）及GpuPixelControlsDeviceTest（新增睫毛入口、强度和重置）；未改Web/iOS及资源协议，不把其历史通过视为本轮睫毛支持。公开人像测试允许生成对照，不保存相机帧。真人眨眼自然度/遮挡/热稳态/实际引擎远端未验收。


### 睫毛自然度增量回归

新增EyelashGeometryTest三项：非方形原始帧旋转/镜像等变性、闭眼下眼睑水平漂移不误判开眼、复用数组单眼/无人脸清理。JNI上传长度与局部妆面按需构建改变，重跑GpuPixelDeviceTest六项及GpuPixelControlsDeviceTest一项。UI结构/公共参数未改变，不扩大到资源下载/Web/iOS；真人自然度和持续性能仍未验证。


## V4图片睫毛增量

回归透明/空白/不透明PNG、严格字段/曲线、V3切换清图、主题能力、三款实绘、闭眼/单眼无效/旋转镜像几何、视频alpha及设置恢复。Apple环境执行EyeMakeupTests；人工快速转头、侧脸遮挡、持续性能及真实引擎接入仍需另测。证据见eye-resource-20261027。

## 2026-10-27 RFB 移除回归

RFB-01 与 DEVICE-RFB 随模块删除退役，不再是当前能力。历史基线/日志仅作历史证据，不推进本轮通过基线。

- REMOVE-RFB-BUILD：构建 Debug、测试 APK、未签名 Release/R8，运行核心/GPUPixel/feature JVM 和 app lint。
- REMOVE-RFB-PACK：检查 Debug/Release APK 无 RFB 模型与 ONNX so；检查依赖图和当前源码无 RFB/ONNX 引用。
- REMOVE-RFB-DEVICE：Pixel 8 上运行 BeautyDeviceTest、GpuPixelDeviceTest，覆盖保留模型、GL 和静态页；不清应用数据。
- 未涉及算法/Shader 修改，不重复持续性能、直播远端、真人观感和完整 UI 矩阵。

## 2026-10-27 手势识别增量

用户明确只提供识别结果与触发事件，动画由宿主接入；不做动画合成/主题切换。由于新增模型、公共运行时、状态机和相机入口，选择以下回归，并保留BeautyDeviceTest/GpuPixelDeviceTest核心冒烟。

| ID | 源码/依赖 | 方法与预期 |
| --- | --- | --- |
| GESTURE-RULE | GestureRuleEngineTest | 七种模型标签的稳定/冷却/重入；自定义OK、0..5、比心、合十、挥手、拍手、画圈正反例；捏合/缩放START/UPDATE/END/CANCEL；跟踪换序、旋转镜像、间断重置 |
| GESTURE-ASYNC | GestureAnalysisRuntimeTest | 默认关闭、独占复制、忙时丢帧、代次拒绝、时间/几何/有效期、事件一次性交付、错误及worker同线程释放 |
| GESTURE-DEMAND | PreviewRequestTest | 无人脸需求也可开启手势，原图模式关闭全部分析需求 |
| GESTURE-DEVICE | GestureDeviceTest | 官方四张公开手势图实推理、21点、8种旋转镜像回映射、空白无手、运行时触发一次、禁用清空 |
| GESTURE-UI | GestureDeviceTest | 默认关闭，可见开关联动独立相机支路，保留MediaPipe+GPUPixel，不播放动画 |
| GESTURE-PACK | Gradle与APK/AAR扫描 | 固定模型摘要、现有tasks-vision复用、无ONNX/新增so、产品APK不含测试图片、Debug/Release/R8、lint及中英文/UI门禁 |

不以合成坐标测试证明真人自定义手势准确率。真人误识别率、交叉遮挡、多肤色/光照、持续热稳定性、完整横竖屏/字体/小屏矩阵、直播厂商与远端未纳入本轮自动验收。旧基线不可直接作为新增用例通过结果。


## 统一资源完整闭环（2026-10-27）

范围：core/lib_beauty_resources、ResourceCenterModel/Controller、实验室底部面板、resource_center内置资源、独立Web resource-studio/resources.mjs及iOS统一消费源码。

| 用例 | 预期与关联 | 方法 |
| --- | --- | --- |
| RES-001 | 17个真实Web资源安装并经公共组合器准备 | ResourceCenterDeviceTest.allSeventeenWebResourcesInstallAndLoad |
| RES-002 | 睫毛只覆盖自身，口红/磨皮保留；停用显式null | eyelashPreservesOtherMakeupAndParameters；ResourceCompositionTest |
| RES-003 | 套装拆分后换单项保留其他部位；重启不被默认覆盖 | themeMaterializesBeforeSingleSlotEditing；restartPreservesPresetScopeAndMissingPackageOnlyDisablesItsSlot |
| RES-004 | JSON保存回读、宿主优先、默认/用户/theme槽位合并 | defaultAndStateRoundTripRespectExplicitNull；ResourceCompositionTest |
| RES-005 | 重复复用、活动引用保护、取消、错误摘要、路径拒绝 | activeLeasePreventsDeletionAndDuplicateInstallReuses；badDigestCancelledAndPathTraversalNeverInstall |
| RES-006 | HTTPS注入下载、离线目录缓存、元数据/作用域/能力拒绝 | fakeNetworkCatalogCacheAndDownloadUseSameInstaller；wrongScopeCapabilitiesAndRevisionConflictAreRejected |
| RES-007 | 眼镜/胡子/帽子独立槽位，可同时组合 | glassesMoustacheAndHatComposeInIndependentSlots |
| RES-008 | 真实实验室8分类、5个美妆条目、点击保存；结束还原原状态 | ResourceCenterUiDeviceTest；只保存面板截图，不保存相机帧 |
| RES-009 | Web单项/套装/目录/默认/翻译与旧主题迁移严格校验 | npm test；真实浏览器制作、嵌入睫毛、交付ZIP/默认JSON下载与摘要回读；390px无横向溢出 |
| RES-010 | 公共配置不破坏已有GPU绘制、alpha/GL状态和睫毛采样 | GpuPixelDeviceTest、EyeMakeupResourceDeviceTest |

执行命令与结果见TEST_REPORT.md本轮记录。Apple编译/Metal、厂商回调/远端、温升和全UI矩阵保持未执行；注入传输测试不冒充线上目录已发布。


## UI/场景配置收敛增量（2026-10-27）

| ID | 范围 | 自动验证 |
| --- | --- | --- |
| DEMO-RESOURCE-PAGE | 独立资源页无相机View、八分类、安装不启用、选择落盘；恢复原用户配置 | ResourceCenterUiDeviceTest |
| DEMO-EFFECT-PANEL | 同一弹框切换八分类、管理操作隐藏、背景运镜控件可见、默认效果主画面 | EffectPanelDeviceTest |
| DEMO-FINAL-SETTINGS | 可见背景/运镜写入最终themeSettings、快速跨字段更新、分类重置、跨页刷新不丢其他分类、相机蒙版就绪 | SceneControlsDeviceTest |
| SCENE-SCALED-MASK | 显式同视场比例映射、严格默认、真实采样年龄、旋转镜像/不同比例拒绝、映射缓存/reset | BeautySceneTest / SceneDeviceTest |

新增规则选择核心、资源、GPUPixel、feature JVM回归及Scene/GpuPixel/Resource设备夹具；新增布局选择资源页横屏1.3字体和相机面板竖屏1.3字体，并恢复设备设置。线上HTTPS生产目录下载、真人美观与持续温升不以夹具测试替代。

## 2026-10-28 分割延迟增量

目标：快速移动时减少旧蒙版等待，不延长有效期。修改分割输入/调度与Demo delegate选择，回归SceneDeviceTest、SceneControlsDeviceTest、ScenePersistenceDeviceTest、MediaPipeInputContractDeviceTest、TextureBridgeDeviceTest；新增ForegroundSchedulingDeviceTest验证两槽最新帧、reset与奇数行宽全部方向镜像。ForegroundDelegateDeviceTest独立比较固定公开夹具CPU/GPU短耗时，不代表真人持续性能。执行核心/宿主JVM、app Debug/Test构建及lint、架构检查。保护并恢复原有设备配置，不保存实时相机帧。不扩展厂商、iOS/Web、温升与完整机型矩阵；已知停机帧计数竞争不推进基线。

## 2026-10-28 快速横移动画画质诊断

ForegroundMotionDeviceTest.rapidTranslationMeasuresMaskLagAndExportsPublicFixture：公开人像固定横移序列30Hz，GPU公共分割，记录年龄/阶段耗时/旧蒙版与当前位置参考IoU，输出公开夹具图；测试执行成功与动态画质是否达标分开。无需相机权限、用户配置变更或真实摄像头存图。参考不是人工真值，CPU诊断合成不能替代GL/真人验收。发现快速移动质量失败时保留证据，不推进该效果通过基线。

## 2026-10-28 原生蒙版后处理增量

NativeMaskDeviceTest三项覆盖Kotlin参考逐像素等价、0/90/180/270旋转与镜像、奇数宽高、NaN/无穷值、非对齐Direct切片、堆缓冲兼容、边界容量拒绝、复用输出不覆盖已发布快照；BeautySceneTest新增ByteBuffer防御复制与游标契约。回归原18项分割/场景/输入/纹理桥用例，重跑公开横移测量，不把画质诊断执行通过当作画质通过。原生变更执行AAR Release、app Debug/lint、显式无签名Release/R8、4 ABI ELF PT_LOAD及APK16KB ZIP对齐静态检查；未做16KB设备和正式Release安装验证。设备配置先备份后恢复，摄像头帧不存盘。

## 2026-10-28 轮廓及运动对照增量

SceneDeviceTest新增软置信度、同纹理更新、p-offset移出边界不复制旧前景和公开人像实际GL导出；MaskMotionDeviceTest验证已知公开人像±12/±24px位移、平坦/无前景、相反局部运动及场景切换拒绝；BeautySceneTest验证校正时刻之前拒绝、原始快照回退、缩放与原采样250ms过期。选原相关21项＋新增3个匹配用例＋2个场景用例，共26项设备功能；核心119、宿主13项JVM。横移测量在采样完成后计算IoU/CPU诊断图，减少测试自身干扰；同版本分别显式开/关补偿，测量执行通过与动态画质验收分开。GPU推理、温度/负载未严格控制，默认关闭未稳定获益的补偿。构建/Native/R8/配置恢复范围见报告。


## 2026-10-28 手势效果绑定增量

- GESTURE-EFFECT-PLAYER：GestureEffectPlayerTest，验证一次动画帧推进/到时清理、播放中忽略与冷却、重复ID、过期/未来事件、非TRIGGER、停用/reset/倒退时间线、非法配置。
- GESTURE-EFFECT-ARCHIVE：GestureEffectsDeviceTest，使用官网实际导出的共同ZIP，读取动画与定点PNG、严格拒绝未知字段/伪数字/路径/重复绑定/图集不匹配。
- GESTURE-EFFECT-GL：同设备类，真实ES3离屏蓝底+半透明红/绿图集，验证alpha、帧切换、结束旁路和4旋转×2镜像的显示位置。仅测试读回，生产管线不额外读回。
- GESTURE-EFFECT-IMPORT：资源页导入仅暂存、明确应用、持久化重开及清除；备份恢复手势两个ZIP与原场景状态。设备唤醒解锁后执行，不清空用户数据。
- 共享SceneGlPass选择SceneDeviceTest；公共导入口选择ResourceDeliveryDeviceTest；手势分支与默认开关选择GestureDeviceTest。默认开关断言与2026-10-28现有Demo要求一致（默认开启，支持显式关闭再启用）；SDK分析默认关闭保持不变。
- Web：Node严格包往返、实际7绑定/4图集导出回读、原始图集保留、390px弹窗无横向溢出；复测原50素材与14参数预演。
- 未覆盖真人手势准确率、持续运行温升、厂商远端推流、iOS和小屏Android横屏/大字体视觉。本轮不是发布性能验收。


## GLIB：手势动画完整资源库 V2（2026-10-28）

- GLIB-01 Web完整库：delivery/gesture-delivery/library/workspace-model及UI导出变更；4内置+保存绑定、9类、预览与规则往返、默认冲突/篡改拒绝，旧V1兼容。npm test及本地浏览器真实PNG封面回读。
- GLIB-02 Android安装：BeautyResourceLibrary/LibraryGestureResource/ResourceDeliveryPack/Manager；真实Web交付夹具、4组组合、预览、多语言、篡改前置拒绝无安装。GestureResourceLibraryDeviceTest前2项。
- GLIB-03 宿主配置：ResourceCenterModel/Controller/Activity；资源卡片点击启用两组、重开恢复、采用默认仅1组、删除取消；导入不启用。该类第3项；LabSceneBackup及额外配置/新增安装恢复，不清空应用。
- GLIB-04 兼容回归：旧独立手势包导入/应用/重开、真实ES3动画、常规清单参数/默认、多资源安装和独立资源页。GestureEffects/ResourceLibrary/ResourceDelivery/ResourceCenterUiDeviceTest，并构建/lint、资源JVM8项及UI/i18n/架构门禁。

排除：识别器/GL合成算法本轮未修改，未重跑模型准确率或持续性能；未实现iOS完整库、网络手势单项目录下载。字体缩放和横屏视觉仍待专门矩阵，设备功能通过不替代视觉验收。
