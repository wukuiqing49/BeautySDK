# 创建或调整 BeautySDK 工程

## 加载

- `.agents/rules/execution.md`
- `.agents/rules/architecture.md`
- `.agents/rules/build.md`
- `.agents/rules/beauty-sdk.md`
- `.agents/config/project-profile.yml`
- `.agents/config/project-scaffold.yml`
- `$android-project-scaffold-workflow`

## 流程

1. 按用户技能创建 app、core/core_base、core/core_utils、feature/feature_app、feature/feature_res。
2. 现有工程在临时目录运行脚手架并最小合并，不覆盖用户配置或密钥。
3. 创建 core/lib_beauty Android Library，把美颜 SDK 接口与实现放在该模块，宿主通过 feature_app 引用。
4. 检查 WKQ 基类使用、CoreUtils 初始化、资源、版本目录、签名分离与公共 API 边界。
5. 运行生成器自测、结构和架构门禁，以及真实库和宿主构建；仅根据实际产物更新事实和报告。
6. 不将工程初始化视为检测、美颜、挂件或直播引擎适配完成。
