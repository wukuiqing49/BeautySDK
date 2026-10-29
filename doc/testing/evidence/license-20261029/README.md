# 0.2.0 授权增量验证 — 2026-10-29

本轮实现应用绑定授权文件、签名租约、本地缓存与退避计划、功能门禁、HTTPS 客户端、个人发行 CLI 和回环 HTTP 服务。未配置或部署正式发行服务。

## 本次结果

| 范围 | 结果 |
| --- | --- |
| Node 协议、HTTP 和签发 CLI | 9 项通过 |
| 核心 Debug JVM | 136 项通过，1 项 Release 专属测试按条件跳过 |
| 核心 Release 授权 JVM | 13 项通过 |
| 资源 JVM / GPUPixel JVM | 12 / 4 项通过 |
| Pixel 8，Android API 36 | GPUPixelDeviceTest 6 项 + 手势实际 GL 动画 1 项通过 |
| 主 Demo Debug、androidTest、lint | 通过 |
| 统一入口 Release、5 个本地 Maven 制品 | 构建发布到本地通过；全部源码摘要与当前工作区一致 |
| 独立 Maven 消费者 | 三类 classpath 同版、Debug、Release/R8、lint 通过；Release 未配置正式签名 |
| JVM API 检查 | 2193 个签名，原基线保留；native 转 JVM 包装不改变方法描述符 |
| 发行契约 / 试用及 API 检查器测试 | 4 / 9 项通过 |
| 公钥构建配置 | 临时测试公钥配置生成通过，随后恢复默认空配置 |
| 商业打包反例 | 默认空配置制品与指定公钥不匹配，按预期拒绝；没有生成 ZIP |
| 代理结构与工作流配置门禁 | 通过 |

新增授权用例覆盖首次激活、多次调用与重启不重复请求、断网保持有效租约、持久化退避、签名撤销不可回落旧缓存、撤销写盘失败清除旧缓存、墙钟回拨、单调时钟到期、错误包名/签名/SDK 主版本、篡改/nonce/权限提升拒绝、Node 签名 JVM 验证、并发合并和在途初始化替换，以及 Release 未授权原帧输出。

## 复现命令

```powershell
node --test services/license/*.test.mjs
.\gradlew.bat :core:lib_beauty:testDebugUnitTest :core:lib_beauty:testReleaseUnitTest --tests '*License*' :core:lib_beauty_resources:testDebugUnitTest :core:lib_beauty_gpupixel:testDebugUnitTest --max-workers=1 '-Pkotlin.incremental=false'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug publishBeautySdkToLocalBuildRepository --max-workers=1 '-Pkotlin.incremental=false'
.\gradlew.bat -p samples/standalone :app:verifyBeautySdkEntry :app:assembleDebug :app:assembleRelease :app:lintDebug --max-workers=1 '-Pkotlin.incremental=false'
python -B scripts/test_trial_contract.py
python -B scripts/test_entry_publication.py
python -B scripts/api_surface.py check
python -B .agents/scripts/validate_agent_structure.py --project-root .
python -B .agents/scripts/validate_android_workflows.py --project-root . --config-only
```

设备通过显式 Pixel 8 序列号安装 Debug 和 androidTest APK（`install -r`，未清除应用数据），运行 `am instrument -w -r -e class 'com.beautysdk.demo.GpuPixelDeviceTest,com.beautysdk.demo.GestureEffectsDeviceTest#realGpuAlphaFrameAdvanceExpiryAndUprightPositions' com.beautysdk.demo.test/androidx.test.runner.AndroidJUnitRunner`。其他在线设备未操作。此处的 GPU 测试是 Debug 开发模式回归，不能冒充商业 Release 正向激活测试。

最初的发行契约检查早于 0.2.0 本地制品生成，因找不到 AAR 失败；发布完成后 4 项复跑通过。独立示例第一次 lint 报新增提示缺少中文翻译，补全后构建和 lint 复跑通过。没有放宽断言或关闭 lint。

## 证据与限制

本目录 `summary.json` 保存逐项 JVM 结果、源码指纹和构建摘要；`node-tests.txt`、`device.txt`、`api.txt`、`package-negative.txt` 保留关键输出。完整构建日志留在本机 `build/license-*.txt`。

当前本地仓库的 Release 有意未配置正式公钥，默认关闭效果，不能直接作为可激活商业发行包。临时测试密钥不用于发行，测试私钥不进入仓库。尚未验证正式 HTTPS 代理、生产密钥轮换、客户签名 APK 激活、跨进程真机离线恢复、Release GL 授权边界及长时性能。核心 Release 正向租约测试使用真实签名与注入传输/时钟，并不是实际 Android HTTPS 请求。服务未部署，商业候选 ZIP 未生成，直播厂商和 iOS 不在本轮范围。
