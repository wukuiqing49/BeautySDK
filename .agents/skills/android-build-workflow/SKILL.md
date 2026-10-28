---
name: android-build-workflow
description: "用于修改和验证 Android 构建系统：Gradle、版本目录、依赖、插件、KSP/KAPT、SDK、JitPack、applicationId/version、manifest、签名、R8、打包、native、16KB page size 和高 targetSdk 兼容。"
---

# Android 构建能力

## 项目上下文

在项目仓库中调用时读取：

- `.agents/config/project-profile.yml`
- `.agents/rules/execution.md`
- `.agents/rules/build.md`
- 涉及依赖归属时读取 `.agents/rules/architecture.md`

## 检查顺序

1. 读取 settings、根构建、Wrapper、版本目录、相关模块构建文件和 manifest。
2. 从实际文件确认 AGP、Gradle、Kotlin、SDK、Java/Kotlin target、插件、仓库和构建变体。
3. 判断任务影响依赖解析、公开类型、SDK 行为、签名、R8、资源压缩、native 或发布产物的范围。
4. 在集中事实源和正确模块中实施最小修改。
5. 检查传递依赖、JitPack/pluginManagement、敏感信息和高版本行为。
6. 运行与真实库/宿主匹配的 Gradle 任务；静态检查器先核实是否适配当前模块结构。

## 常见能力

- 维护版本目录和插件 alias，协调 AGP/Gradle/Kotlin/KSP/SDK 兼容性。
- 配置普通依赖、KSP/KAPT processor、JitPack 仓库和 Gradle 插件仓库。
- 维护动态 applicationId、语义化 versionName/versionCode 和 flavor/buildType。
- 处理 release 签名、R8/ProGuard、资源压缩、merged manifest 和 APK 验证。
- 检查 network security config、FileProvider、exported 和 URI grant。
- 检查 ABI 交集、native 打包和 16KB page size 风险。

## SDK 验证

优先实际库的 assemble/test 任务，检查 AAR、模型、native ABI、consumer rules 和传递依赖；需要时验证最小宿主 release/R8。APK 签名和系统栏要求仅针对宿主。

`scripts/validate_build_output.py` 与聚合门禁检查 app/core/feature 宿主；core/lib_beauty 另外执行真实库构建和测试，不给 AAR 添加 applicationId、应用签名或无关权限。
