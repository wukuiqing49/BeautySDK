---
name: android-project-scaffold-workflow
description: 按项目配置创建或调整 Android app、core/feature 工程结构，使用现有生成器、WKQ 基础封装、版本目录、签名与资源模板。BeautySDK 的美颜库固定放在 core/lib_beauty。
---

# Android 项目脚手架

读取 `.agents/config/project-profile.yml`、`.agents/config/project-scaffold.yml`、`.agents/rules/architecture.md` 与 `.agents/rules/build.md`。

## 项目约定

- 应用骨架使用 app、core/core_base、core/core_utils、feature/feature_app、feature/feature_res。
- app 使用 AndroidCoreBase 基类，Application 初始化 AndroidCoreUtils；UI 与业务分别按已有模块职责放置。
- 美颜 SDK 为独立 Android Library：core/lib_beauty，对应 :core:lib_beauty；不依赖宿主、feature 或 WKQ 应用框架。
- 核心库对外发布 AAR；app 是集成与测试宿主，不将 SDK 实现写进应用层。

## 生成和合并

1. 从项目配置核实包名、版本、SDK 和模块路径。引入 WKQ 前查询对应仓库 tag，并固定验证过的版本。
2. 空工程使用 scripts/render_project_scaffold.py 与 assets/scaffold 模板；目标是项目根目录，不在 .agents 内生成业务工程。
3. 已有无生成标记工程在临时目录生成后检查差异并最小合并；禁止直接 --force 覆盖。已有标记也需保护本地修改和用户配置。
4. 通用生成器负责五个基础模块；额外添加 core/lib_beauty 并注册 settings、宿主依赖及发布规则，不假设通用生成器会自动创建美颜库。
5. 保留签名秘密与公开 app-config.properties 的分离；库不设置 applicationId 或 APK 签名。
6. 维护生成器/配置时，在临时目录运行 scripts/test_render_project_scaffold.py；核对模块图后构建 :core:lib_beauty:assembleRelease、单元测试及 :app:assembleDebug。
7. 更新实际模块、构建来源和报告；工程可编译不等于检测、渲染或推流效果完成。
