# 首次接入任务卡

目标：未参与项目开发的人，依靠交付包完成依赖解析、资源加载、应用默认配置、调参和释放。此任务卡已提供，不表示外部接入评估已经执行。

## 准备

准备 JDK17、Android SDK36、Gradle Wrapper 所需网络或缓存，以及 API24+ 设备；纹理路径需要宿主已有当前 ES3 上下文。领取候选 ZIP 和工坊导出的试用资源 ZIP，记录 SDK 版本和资源包 SHA-256。

## 操作顺序

1. 解压候选包，在 `sample/` 执行 `gradlew.bat -PbeautyRepo=../repo :app:verifyBeautySdkEntry :app:assembleDebug`。只声明一个 SDK 依赖。失败先检查仓库路径、JDK、SDK及第三方下载，不复制 AAR 绕过依赖元数据。
2. 安装生成的 APK。页面显示生成色卡输入/输出和“本地帧处理完成”；这一步只验 RGBA 生命周期，不代表 GPUPixel 效果。
3. 将工坊导出的完整 ZIP 放到宿主 `assets/resource_delivery.zip`。参考 `ResourceIntegration.kt` 在 IO 线程安装并准备默认搭配。保留原 `beauty_resources/` 外层。
4. 在宿主当前 ES3 线程创建 `createTexturePipeline(context, onError)`，执行 `start()`，赋予准备结果的 `settings`。模型错误要显示可理解的信息，回调更新 UI 必须切主线程。
5. 提交正确时间戳、旋转和镜像的 `TextureFrame`。同帧消费借用输出；不能跨线程直接使用纹理 ID。详见 [接入说明](ANDROID_SDK_USAGE.md)和[契约](SDK_INTEGRATION_CONTRACT.md)。
6. 用 `BeautyParameters.apply` 修改一个强度，保留其他字段；在 IO 线程准备资源，不能在视频回调中读 ZIP。
7. 同 GL 线程停止并关闭 pipeline，再释放仍被它引用的准备资源。更换资源时，新快照成功后才替换旧快照并释放旧资源。
8. 验证缺失资源、模型失败、重复退出、后台恢复；保存诊断数值，默认不录制人脸。

## 外部接入记录

创建独立记录填写：日期、匿名评估者编号、SDK版本、资源摘要、工程环境、开始/结束时间、每一步结果、求助次数、错误信息与解决方式。不得填推测的耗时。

首轮目标：60分钟内完成最小 RGBA 接入；有现成 ES3 宿主的纹理与资源接入另计，不把 EGL/相机开发时间混入 SDK 初始化耗时。记录超过目标的原因；一次成功不能代表所有客户。

验收要求：评估者未参与实现；安装、调参、释放均有可核对结果；文档之外的指导逐项记录。未执行前 `external-integration` 保持 pending。
