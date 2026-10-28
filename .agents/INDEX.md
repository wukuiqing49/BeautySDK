# BeautySDK Agent 能力索引

入口与任务范围以 `AGENTS.md` 为准。当前为 initialized / scaffold-lib-beauty，按 app/core/feature 技能建立工程，美颜库为 core/lib_beauty；仅实现帧契约和旁路，尚无检测、渲染与引擎适配。

## 项目上下文

- `config/project-profile.yml`：当前事实、已确认方向及未验证项。
- `config/project-scaffold.yml`：应用基础模块的脚手架配置，美颜库扩展路径由项目档案和架构规则维护。
- `rules/execution.md`：执行与证据要求。
- `rules/beauty-sdk.md`：帧契约、引擎适配、MediaPipe、OpenGL、性能验证。
- `rules/architecture.md`：SDK、检测、适配器和 Demo 依赖边界。
- `rules/android.md`、`rules/build.md`：生命周期与 AAR/宿主构建。

## 主流程与技能

- `workflows/beauty-sdk.md` / `prompts/beauty-sdk.md` / `$android-beauty-sdk-workflow`：帧处理和特效链路。
- `workflows/create-project.md` / `$android-project-scaffold-workflow`：初始化 SDK 工程。
- `workflows/change-architecture.md` / `$android-project-architecture-workflow`：公共 API、模块和依赖。
- `workflows/change-build.md` / `$android-build-workflow`：构建、打包与兼容。
- `workflows/fix-bug.md`：缺陷修复。
- `$android-functional-test-workflow`：测试计划和证据；SDK 性能矩阵以项目规则为准。

## 仅按 Demo/宿主需求加载

- UI：`workflows/implement-page.md`、`rules/ui.md`、`skills/android-ui-workflow/`。
- 文案：`workflows/localize-content.md`、`rules/i18n.md`、`skills/android-i18n-workflow/`。
- Figma：`workflows/figma-process.md`、`workflows/figma-code.md`、`rules/figma.md`。
- Zeplin：`skills/zeplin-android-layer-implementation/`。
- 商店素材：`workflows/prepare-play-assets.md`、`rules/play-assets.md`；不是 AAR 发布要求。
- 宿主按当前 app/core/feature 路径与 WKQ 基类实现；SDK 库独立于 UI 框架。

## 工具适用范围

- `scripts/validate_agent_structure.py`：代理配置结构与引用校验。
- `scripts/validate_android_workflows.py --config-only`：仅代理配置校验。
- 生成器负责五个基础模块；core/lib_beauty 为独立扩展。聚合检查验证应用骨架，库还需单独构建、单元测试和 AAR 检查。
- 项目记录：实现后更新事实；功能测试维护 `doc/testing/TEST_PLAN.md` 与 `doc/testing/TEST_REPORT.md`，不能预填通过结果。
