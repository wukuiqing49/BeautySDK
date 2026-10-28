# BeautySDK 帧处理与引擎接入

## 加载

- `.agents/rules/execution.md`
- `.agents/rules/beauty-sdk.md`
- `.agents/rules/architecture.md`
- `.agents/config/project-profile.yml`
- `$android-beauty-sdk-workflow`
- 构建变更按需使用 `$android-build-workflow`。

## 流程

1. 区分方案讨论、工程创建、实现和测量；先确认现有文件与本次交付范围。
2. 针对选定引擎版本核实帧接口，画清所有权、线程、坐标和返回路径。
3. 先验证原帧旁路交回，再验证处理纹理交回；接入有界异步检测和挂件合成。
4. 检查失败旁路、过期结果、旋转镜像和生命周期，按改动执行测试。
5. 有设备时测三组性能并检查远端画面，缺少条件时记录未验证项。
6. 更新真实项目事实，报告实现范围、证据和剩余问题。
