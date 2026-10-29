# 发布与回退流程

当前仅准备限定试用包，不自动发布网站或远程 Maven。

1. 核对 `PRODUCT_STATUS.md` 与 `resources/commercial/trial-release.json`；执行 `python scripts/trial_contract.py`。商业资格检查另用 `--require-qualified`，待验收项必须阻止通过。
2. 执行 `gradlew.bat publishBeautySdkToLocalBuildRepository`，运行独立消费者 Debug/Release/R8 和 `verifyBeautySdkEntry`。运行发布契约测试；检查构建来源与当前源码一致。
3. 使用 `scripts/api_surface.py check` 对已发布 AAR 与冻结的 JVM 签名基线比较。删除或修改原签名必须审阅、版本升级和迁移说明；不能自动重写基线掩盖差异。该检查不是 Kotlin 元数据、运行语义、native ABI 或资源协议的完整兼容认证。
4. 运行资源协议与工坊测试，生成不可变资源快照。保留资源ZIP/清单摘要及对应SDK版本；对照导入手机，不把旧APK测试结果附给新包。
5. 0.2.0 起须按 [授权说明](LICENSING.md) 使用正式发行公钥配置重建 Maven 制品，再执行 `python scripts/package_candidate.py --license-config C:/issuer/license-public.json --output build/beautysdk-trial-唯一编号.zip`。未配置或配置不匹配时拒绝打包。候选包附状态、验收任务卡和资源加载示例；相同输出文件拒绝覆盖。打包通过不代表商用资格通过。
6. 对外发行前人工审核模型/素材/依赖来源、正式签名、客户条款及支持范围。私钥不进入网页、仓库、样例或交付包。资源验签与客户商业授权分开。
7. 发布后同版本制品不可覆盖。回退使用完整旧版Maven仓库与其资源快照，先验证旧SDK能消费目标资源；恢复用户配置前备份，不自动降级覆盖数据。

## 首次试用交付内容

完整 Maven repo、一个依赖入口、独立 sample、接入说明、当前能力表、验收任务卡、第三方通知。效果资源包由工坊另行导出并核对摘要，不把未审核素材自动捆绑成商业授权内容。

维护记录应包含候选ZIP SHA-256、AAR来源摘要、兼容结果、测试报告、资源包摘要、已知问题。文档用“本地验证”“设备验证”“真人评审”明确区分证据类型。
