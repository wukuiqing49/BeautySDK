# Compact Demo UI — 2026-10-30

结论、失败尝试及限制见../../TEST_REPORT.md最新章节。results.json记录未提交源码摘要与APK摘要。测试类为ResourceExperienceDeviceTest、EffectPanelStabilityDeviceTest、Sticker3DUiDeviceTest、ResourceCenterUiDeviceTest、ResourceLibraryDeviceTest、DemoBuiltinLibraryDeviceTest（OPPO常规14项）；矩阵仅ResourceExperienceDeviceTest三项。保留原断言，参数高度增加上界断言；初始弹框有界等待后仍断言其必须可用。Pixel矩阵真实font_scale=1.3，方向整批锁定；结束恢复用户状态。未归档私有资源备份或相机图像。对比图只缩放、排列真实UI View截图，无重绘产品UI。
