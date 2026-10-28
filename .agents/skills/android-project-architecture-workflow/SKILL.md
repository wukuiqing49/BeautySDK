---
name: android-project-architecture-workflow
description: 分析 Android app/core/feature 工程的模块与共享能力，按项目约定复用 WKQ 基础层，并保持 core/lib_beauty 美颜 SDK 的独立 API 与依赖边界。
---

# Android 工程与美颜库架构

读取 `.agents/config/project-profile.yml`、`.agents/rules/architecture.md`、`.agents/rules/beauty-sdk.md`；依赖变更读取 `.agents/rules/build.md`。

1. 检查 settings、构建、公开 API 与相邻代码，按 app/core/feature 结构确定模块归属。
2. 宿主先复用 core_base/core_utils；Activity/Fragment 等采用 AndroidCoreBase，CoreUtils 由 Application 初始化。
3. 共享美颜能力放 core/lib_beauty，人脸分析后端放 core/lib_beauty_mediapipe；不要求库依赖宿主基础框架，不把宿主 UI 与引擎业务放进 SDK。
4. 检查线程、帧坐标、时间戳、资源所有权、Kotlin/Java API 与 implementation/api 边界。
5. 新增 WKQ 依赖时查询实际仓库 tag 和 API；版本写入集中目录。
6. 实施最小变更，运行 scripts/validate_architecture.py 及实际库/宿主构建，区分静态检查与运行验证。
