# 限定试用交付验证 · 2026-10-29

本报告随候选包交付，只列本轮范围。SDK版本0.1.0，商用资格pending。

- Demo assembleDebug、assembleDebugAndroidTest和lintDebug通过（5m46s）；新增完整包摘要显示与持久化、失败导入保留旧记录的设备测试已编译。
- UI与国际化门禁均0警告；架构门禁通过，独立sample未加入根settings的2项提示符合隔离消费者设计。
- 试用范围/证据门禁8项、Maven发布契约4项通过；JVM API基线2033条签名通过。初建基线不代表历史升级兼容认证。
- `trial_contract.py --require-qualified`按预期拒绝，5项真实资格仍pending。
- CMS全量84项、静态构建通过；隔离浏览器实际下载ZIP摘要与界面一致，390px弹框不超视口、无pageerror。
- 独立样例verifyBeautySdkEntry、assembleDebug、assembleRelease/R8最终通过（1m14s），资源准备/调参源码已编译。Release为未正式签名验证制品。首次内存不足、一次参数解析错误和一次进程未正常完成均不计通过，记录保留。
- Pixel8（39061FDJH00D3J）offline，未安装/运行本轮APK，未操作其他设备。Android摘要界面未完成真机与字体/横屏验收。
- 未运行真人默认预设评审、8～12台多设备长测、陌生开发者接入评估或正式签名发行；未接直播厂商，未部署CMS或远程Maven。

完整历史记录在源码仓库doc/testing/TEST_REPORT.md；不把历史22项设备回归直接作为当前新增功能通过证据。
