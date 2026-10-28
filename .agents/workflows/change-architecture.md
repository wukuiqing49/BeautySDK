# 调整 BeautySDK 架构

## 加载

- `.agents/rules/execution.md`
- `.agents/rules/android.md`
- `.agents/rules/architecture.md`
- `.agents/rules/beauty-sdk.md`
- `$android-project-architecture-workflow`

## 流程

1. 读取实际模块、依赖和公共 API；尚无工程时只记录规划。
2. 划分 SDK、检测实现、引擎适配和 Demo 职责。
3. 检查线程、坐标、生命周期、资源所有权和 API 兼容性。
4. 实施最小变更，验证受影响库与宿主；构建变化按需加载构建技能。
5. 汇报依赖方向、兼容影响、测试证据与未验证项。
