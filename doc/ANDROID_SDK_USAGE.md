# Android SDK 使用说明

> 0.2.0 Release 新增应用授权。接入时必须初始化、提供客户授权文件并声明联网权限；默认 24 小时刷新、最长 7 天离线租约，不逐帧请求。配置、状态与迁移见 [商业授权](LICENSING.md)。未配置发行公钥的本地 Release 只可验证构建，效果默认关闭。


当前版本范围见 [产品状态](PRODUCT_STATUS.md)；首次独立接入按 [任务卡](FIRST_INTEGRATION.md) 执行。

> 推荐通过统一Maven入口接入，独立消费者见 [samples/standalone](../samples/standalone/README.md)。当前仅提供本地Maven仓库，未发布远端仓库。签名完整包接入见 [RESOURCE_SIGNATURE.md](RESOURCE_SIGNATURE.md)。

本文面向接入方。当前主线是 Android API24+、JDK17、MediaPipe Face Landmarker + GPUPixel/ES3。SDK处理宿主视频帧，不接管摄像头、音频、编码或推流；CameraX Demo仅是集成示例。ZEGO/声网具体版本适配和远端推流尚未完成。

## 1. 一个 Maven 依赖入口

接入方在settings.gradle.kts中配置交付仓库（示例假定repo位于工程根目录）：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri(rootDir.resolve("repo")) }
        google()
        mavenCentral()
    }
}
```

app/build.gradle.kts只声明一条SDK依赖：

```kotlin
dependencies {
    implementation("com.beautysdk:beautysdk:0.2.0")
}
```

Gradle自动解析同版本核心、资源、MediaPipe与GPUPixel模块及第三方依赖，现有包名/API无需改变。不要再单独指定其他SDK模块的不同版本。发行方通过`gradlew.bat publishBeautySdkToLocalBuildRepository`生成build/repo；交付必须保留整个仓库及POM/module元数据，不能只复制入口AAR到libs。入口不是合并AAR，第三方依赖首次解析仍需Google/Maven Central或预配置缓存。模型随后端依赖打包，效果素材ZIP独立管理。

### 仓库内源码接入

在当前源码工程的宿主模块中添加（Groovy）：

```groovy
dependencies {
    implementation project(':core:lib_beauty_sdk')
}
```

入口通过api暴露四个实现模块，不包含Demo、WKQ、CameraX或直播厂商。算法模型按各模块assets和许可说明配置，不在效果资源库ZIP里。参见[MediaPipe模块](../core/lib_beauty_mediapipe/README.md)、[GPUPixel模块](../core/lib_beauty_gpupixel/README.md)。

## 2. Android 内置资源：两种放置方式

### 完整 ZIP（推荐）

把工坊导出的完整包放到：

```text
app/src/main/assets/resource_delivery.zip
```

ZIP里面保留统一的beauty_resources/外层，不能只把某个单项package.zip改名当完整包。后台IO线程加载：

```kotlin
import com.beautysdk.resources.*
import java.io.File

val manager = BeautyResourceManager(File(context.filesDir, "beauty_resources"))
val delivery = manager.installDeliveryFromAssets(context.assets)
// 自定义路径：manager.installDeliveryFromAssets(context.assets, "beauty/resource_delivery.zip")
val library = requireNotNull(delivery.library) // 老平铺包可以没有library
```

### 解压目录

也可以把ZIP解压得到的整个beauty_resources文件夹复制到assets：

```text
app/src/main/assets/beauty_resources/
  library.json
  catalog.json
  defaults.json
  beauty/beauty_clear/
    package.zip
    manifest.json
    effect.json
    preview.png
  gesture/victory_sparkles/
    package.zip
    gestures.json
    preview.png
    asset_0.png
```

```kotlin
// 后台IO线程，与ZIP方式二选一。
val delivery = manager.installDeliveryDirectoryFromAssets(context.assets)
// 自定义目录：manager.installDeliveryDirectoryFromAssets(context.assets, "builtin/beauty_resources")
```

目录不能省略或改写清单引用的文件；包括展开副本和package.zip，两者会一致性校验。路径相对于assets，不加assets/前缀、绝对路径、../或末尾斜杠。目录加载有文件数、深度和总量限制；收集后走同一完整包校验及安装回滚，不会执行网络请求。

两种方式都返回ResourceDeliveryResult，提供resources、library、defaults/defaultsJson和gestures。安装目录在宿主私有文件夹，按摘要复用，重复加载不重复安装；仅安装，不启用效果、不写宿主选择。初始化和后续安装建议由宿主统一串行IO队列管理，复用一个manager。ZIP/目录最多32MiB、2000文件、99常规资源、8手势组，目录深度最多8层；路径和单项PNG/JSON仍受各自校验限制。取消可传ResourceCancellation，异常由宿主保留旧效果并提示。

Demo已经把最新完整ZIP放入主APK assets/resource_delivery.zip，并在资源Model启动时通过公共加载API安装一次；相机和资源中心默认读取内置library.json，不需要先手动导入。用户显式导入的library.json优先于内置清单；用户参数、资源选择和旧内置素材的删除记录保留。

本地文件或ContentResolver得到的InputStream直接调用manager.installDelivery(input)。独立单项包使用manager.install(input)，不是完整库入口。以上入口会关闭传入的流。

## 3. JSON驱动分类、名称、预览与滑杆

SDK提供数据和效果处理，宿主负责Activity/Fragment/Adapter布局；Demo实现可参考ResourceCenterController。Demo打开效果弹框和切换分类时默认展示“预设”；“微调”用于修改当前效果的具体参数强度。预设与微调共用固定弹框高度，参数刷新复用滑杆，不因资源快照刷新重新创建控件。

```kotlin
val locale = if (delivery.defaults.followHost) hostLanguageTag else delivery.defaults.locale
val library = requireNotNull(delivery.library)
for (category in library.categories) {
    val title = library.text(category.nameKey, locale)
    val controls = library.parameters(category.id)
    // controls提供 id/nameKey/min/max/step/default；宿主据此生成滑杆。
}
for (installed in delivery.resources) {
    val ref = library.resources.firstOrNull { it.id == installed.descriptor.id }
    val name = ref?.let { library.resourceName(it, locale) } ?: installed.descriptor.name
    val png = manager.preview(installed.hash) // IO线程读取，交UI解码/缓存展示
}
```

hostLanguageTag由宿主提供，例如en-US。分类与参数文字按完整语言→基础语言→默认语言回退。中文显示名保留在JSON，文件路径只使用英文分类和稳定ID。手势单独使用library.gestureCategory/gestureResources及manager.listGestureResources/gesturePreview，不扩充原8类ResourceCategory枚举。

## 4. 默认配置、用户选择和参数调整

```kotlin
val composer = BeautyEffectComposer(manager, ResourceCapabilities.androidGpuPixel())
val store = BeautyResourceStateStore(File(context.filesDir, "beauty_resource_state.json"))
// IO线程：恢复已保存选择；没有保存时才使用包内默认值。
val state = delivery.defaults.merge(store.read(), installed = manager.list())
    .resolveInstalled(manager.list()).state
val prepared = composer.prepare(state)
```

如果需要先展示资源列表、由用户决定是否采用默认配置，不要在导入成功后直接调用上述应用流程。采用默认时使用delivery.defaults.state，并由宿主保存选择。包里默认选择为空时不会自动选中第一张素材，美颜参数按SDK中性值或明确覆盖值处理。

```kotlin
// 资源卡片选择（IO配置线程）
val chosen = delivery.resources.first { it.descriptor.id == "beauty_clear" }
val nextState = composer.select(state, chosen)
val nextPrepared = composer.prepare(nextState)
// 美颜程度修改：校验JSON范围与SDK白名单，保留其他参数。
val editedState = library.updateParameters(nextState, mapOf("beauty.smoothing" to 0.5f))
val editedPrepared = composer.prepare(editedState)
// 保存成功后再发布新配置；未使用的快照及时close。
store.write(editedState)
nextPrepared.close()
```

示例假设资源库包含beauty_clear，生产代码先查找并处理缺失。prepare失败不替换正在使用的快照；保存失败不宣称配置已保存。旧快照只有在视频线程切换完成且GPU不再使用它时才关闭；正在使用的prepared/editedPrepared不能用use立刻释放。

## 5. 接到视频纹理管线

下面使用同一个宿主视频/GL线程；必须先有当前ES3 EGL上下文：

```kotlin
import com.beautysdk.*
import com.beautysdk.gpupixel.GpuPixelBeautyRenderer
import com.beautysdk.mediapipe.MediaPipeFaceAnalyzer

val session = BeautySession(
    factory = MediaPipeFaceAnalyzer.factory(context.applicationContext),
    renderer = GpuPixelBeautyRenderer(context.applicationContext),
    allowScaledAnalysis = true, // 仅在宿主保证分析小图和输出同视场、等比例时使用
    onError = { error -> /* 记录错误；切主线程更新UI */ }
)
session.settings = prepared.settings
session.start()
```

调参后的editedPrepared准备成功并保存后，在此视频线程把 `session.settings = editedPrepared.settings` 整体替换；GPU不再使用旧配置时再释放旧prepared。下面每帧处理，analysisRgba和inputTexture由宿主提供：

```kotlin
// 同视频线程，analysisRgba: RgbaFrame，inputTexture: TextureFrame
session.submitAnalysis(analysisRgba) // 有界异步，不等待推理
val output = session.process(inputTexture) as TextureFrame
// 在有效期内交回宿主编码前接口，或者复制至宿主自己的纹理池。
```

两路必须共享同一单调纳秒时间线、旋转/镜像与裁剪视场。TextureFrame支持TEXTURE_2D/EXTERNAL_OES，transform是列主序采样矩阵；原始像素旋转/镜像用FrameInfo描述。不同视场用AnalysisFrameMapping显式映射，不能仅因尺寸相同就假定匹配。

仅纹理输入时，不会自动取得人脸关键点：宿主可选GlTextureAnalysisBridge生成检测小图，或使用厂商提供的RGBA分析支路。没有有效分析时，美型/美妆定位、人脸贴纸不会完整生效。RGBA直接输入可以session.process(rgbaFrame)搭配RgbaBeautyRenderer作为CPU参考，但不等价于GPUPixel的美型/美妆主线。

session.settings可以整体替换，但宿主必须协调配置发布与快照寿命；不要在GL线程执行install/prepare/文件读写。输出GL纹理属于renderer，通常只在下一次处理/关闭前有效；旁路可能直接借用输入。异步编码应使用GlTextureOutputPool等受控副本与租约，跨context同步仍由宿主负责。

纯GpuPixelBeautyRenderer不负责背景/运镜。需要场景资源时，用BeautySceneRenderer包装delegate，并按[场景说明](SDK_SCENE_EFFECTS.md)接入前景分割。手势动画使用[手势说明](SDK_GESTURES.md)的独立分析、播放器与渲染器，不能仅把手势包赋给session.settings。

## 6. 换源、停用和释放

```kotlin
// 同创建session的GL线程，保持其EGL上下文当前。
session.stop()
// 同尺寸切摄像头也需要重置；准备新源后session.start()。
session.close()
// GPU不再使用旧配置后，释放其资源引用。
prepared.close()
editedPrepared.close()
```

避免Activity销毁后worker继续发布UI、避免跨线程关闭GL对象。BeautyResourceManager不持有相机或Activity；没有必须调用的close。宿主负责其IO队列、已准备快照以及其他分析/场景/手势运行时的释放。不要删除仍有引用的安装目录；通过manager.uninstall操作常规资源。

## 7. 常见问题与限制

- 安装成功但界面没变化：安装只返回数据，宿主须保存library并刷新分类/卡片；Demo导入完整库会完成此编排。
- 默认没有效果：检查defaults.selections/parameters，随后显式prepare并提交settings；不是默认取第一份资源。
- 资源无法删除：检查是否仍有ComposedBeauty/ResourceLease引用，先切换效果再释放。
- 本地内置加载不需要网络权限。核心库不在逐帧处理时联网；HTTPS资源下载是可选资源模块能力。
- 当前Android新完整包及旧包兼容，iOS完整库消费未实现。真机夹具通过不代表真人动态画质、持续性能或厂商推流验收。

协议详见[完整资源库](../resources/unified/library-v1/README.md)、[公共管线](SDK_PIPELINE.md)，本次执行结果见[测试报告](testing/TEST_REPORT.md)。
## 资源版本与在线手势

内置资源统一发布、校验和回滚见[资源发布说明](RESOURCE_RELEASE.md)。CMS `npm run release -- --sdk <SDK目录>` 一次同步ZIP、library/defaults及发布摘要；不要只替换其中一个文件。Demo构建门禁会拒绝混合版本。

在线目录V2包含常规与手势资源，V1保持兼容，后台调用：

```kotlin
val catalog = manager.unifiedCatalog("https://your-host/releases/<id>/beauty_resources/catalog.json")
val installedGesture = manager.downloadGesture(catalog.gestures.first())
// 下载只安装。用户明确选择后，在IO配置阶段准备，再交给已有手势播放器/渲染器。
val gesturePack = manager.prepareGestureSelection(listOf(installedGesture.hash))
```

目录/包/封面都经校验，手势默认不因下载自动启用。旧manager.catalog只返回普通资源；需展示全部分类时使用unifiedCatalog。分类顺序以已应用defaults.categoryOrder为准；FIXED语言来自defaults，缺失翻译回退由library声明。


## 试用包核对

工坊“手机试用”下载后显示完整ZIP的SHA-256；新版Demo资源中心在本地完整包成功安装后显示同一核对码并保存。失败导入不替换上次成功记录，重开页面仍可查看。该字段只指最近成功的本地完整包，不代表当前启用资源、参数或官方网络版本。先核对包，再显式应用默认配置并检查参数；不以摘要相同推断网页和真人画质一致。
