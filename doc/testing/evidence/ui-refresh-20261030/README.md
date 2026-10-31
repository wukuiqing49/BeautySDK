# Demo UI refresh — 2026-10-30

范围和结论见 ../../TEST_REPORT.md 最新章节；results.json记录工作区源码摘要、APK摘要和实际配置恢复结果。未提交工作区包含上一轮3D源码，本轮只修改Demo UI与相关设备用例。

构建：`gradlew.bat --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。

常规设备类：ResourceExperienceDeviceTest、EffectPanelStabilityDeviceTest、Sticker3DUiDeviceTest、ResourceCenterUiDeviceTest、ResourceLibraryDeviceTest、DemoBuiltinLibraryDeviceTest，共14项。矩阵仅ResourceExperienceDeviceTest三项。运行器为androidx.test.runner.AndroidJUnitRunner，测试包com.beautysdk.demo.test。Pixel矩阵实际设置系统font_scale=1.3，rotation由UiAutomation设置，结束恢复原值；OPPO只验收默认1.0字体。

OPPO常规14/14、横屏3/3；Pixel常规12/14，其布局采样超时和异步列表视图断言两项原断言隔离2/2通过，大字体竖/横屏各3/3。失败批次原文保留，不能推断整批稳定。图片为UI View绘制，资源截图滚至末尾用于末卡可达验证，首行局部裁切是滚动位置。未归档私有资源/偏好备份或摄像头帧。
