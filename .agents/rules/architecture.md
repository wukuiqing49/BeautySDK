# BeautySDK 架构规则

## 工程结构

- 按用户脚手架约定使用 app、core/core_base、core/core_utils、feature/feature_app、feature/feature_res。
- app 负责应用壳、Application、入口与宿主平台配置；页面和演示编排属于 feature/feature_app，共享资源属于 feature/feature_res。
- core/core_base 用 api 包装 AndroidCoreBase，core/core_utils 用 api 包装 AndroidCoreUtils。宿主页面采用对应基类，CoreUtils 在 Application 初始化一次。
- core、feature 是目录容器，不给容器添加 Android 插件或独立业务代码。

## 美颜 SDK 边界

- `core/lib_beauty_gpupixel` 是可选渲染后端，api 依赖 lib_beauty，固定导入上游 GPUPixel 的美颜 Shader/LUT，并由本项目 C++/JNI 执行；不引入相机、WKQ、算法后端或直播厂商。feature_app 组合检测、渲染和贴纸；核心共享 API 不依赖此后端。

- core/lib_camera 是用户新增的自定义相机测试库，namespace 为 com.beautysdk.camera；已基于 CameraX 实现采集、预览、前后摄和 RGBA 输出；feature_app 负责相机到 BeautySession 的测试适配，app 提供相机测试页。相机权限和测试页面由宿主管理；相机实现不反向依赖美颜算法或厂商引擎。

- 共享美颜 API、调度和渲染放在 core/lib_beauty；算法后端独立放在 core/lib_beauty_mediapipe。均为 Android Library/AAR。
- lib_beauty 定义帧、参数、生命周期、错误与检测抽象，承载后续 OpenGL 美颜/合成，不依赖 app、feature、WKQ 应用框架或直播厂商。
- MediaPipe 后端位于 core/lib_beauty_mediapipe，api 依赖 lib_beauty。厂商适配仍待选定版本，不泄漏算法/厂商类型到核心 API。
- 当前依赖为 app → feature_app → MediaPipe 算法后端 → lib_beauty；页面可以使用 core_base/core_utils，禁止 SDK 反向依赖宿主。
- 公共 API 考虑 Kotlin/Java 调用、线程、资源所有权和兼容性；第三方类型不暴露时优先 implementation。
- 宿主负责引擎凭据、采集/推流控制与权限；SDK 不持有 Activity，不擅自启动摄像头或服务。
- 引入或更新基础库先检查仓库 tag、实际 API 与传递依赖；不以项目框架约定为理由向美颜 AAR 注入 UI/账号/统计依赖。
