# 独立美妆选色（2026-10-31）

Android GPUPixel支持口红与腮红独立sRGB颜色，强度仍由原参数控制。颜色变更不自动开启强度；不是哑光/润泽材质或精细分割。默认浮点颜色与旧Shader相同。Demo在美妆→微调→口红/腮红提供色号入口，五个色号及自定义十六进制输入，选择保存在私有配置。

```kotlin
val adjustments = BeautyAdjustments(
    lipstick = .6f,
    blusher = .25f,
    makeupColors = MakeupColors(
        lipstick = MakeupColor.fromRgb(0xA04455),
        blusher = MakeupColor.fromRgb(0xC77060),
    ),
)
session.settings = session.settings.copy(adjustments = adjustments)
// copy修改其他强度时保留当前妆色。
session.settings = session.settings.copy(adjustments = adjustments.copy(eyelashes = .4f))
```

## 资源能力扩展

统一packageVersion=1的PARAMETERS资源可增加payload.colors。只能为当前LIPSTICK或BLUSHER子类声明同名妆区，必须额外声明requirements.features中的makeup.colors.v1。旧资源不需要新字段或新能力；旧执行器必须拒绝不支持的新能力，不能静默忽略颜色。CMS可视化选色编辑和iOS渲染尚未接入本扩展。

```json
{"effectVersion":1,"effectType":"PARAMETERS","payload":{"parameters":{"makeup.lipstick":0.6},"colors":{"lipstick":{"red":0.65,"green":0.27,"blue":0.33}}}}
```

可生成完整资源ZIP后在资源中心导入：

```powershell
python -B scripts/create_makeup_color_resource.py --region lipstick --color A04455 --strength 0.6 --name "Berry lipstick" --output build/makeup-color-examples/lipstick.zip
python -B scripts/create_makeup_color_resource.py --region blusher --color C77060 --strength 0.3 --name "Soft blush" --output build/makeup-color-examples/blusher.zip
```

## 存档与兼容

BeautyResourceState新增按区域保存的makeupColors。读取stateVersion=1时使用默认颜色，新存档写stateVersion=2；不覆盖已有强度或资源选择。旧版本SDK不能读取V2存档，降级需恢复旧存档备份。单项重新选择资源清除该作用域手动色号，其他区域保留；重置美妆分类清除手动色号。旧构造和copy的JVM描述符保留并经发布基线检查，但不等同于所有Kotlin元数据/行为兼容验证。

本轮设备像素验证颜色独立性、alpha、原图区域及默认色回归；真人肤色/光照、审美与长期性能另行验收。完整证据见testing/TEST_REPORT.md。
