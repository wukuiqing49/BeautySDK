# 手势效果包 V1

独立于八分类资源包与旧宿主动画素材。网页入口：资源工坊 → 手势效果。Demo：资源中心导入 ZIP → 应用导入的手势效果 → 相机更多菜单控制手势检测。导入不会直接启用新包；应用替换整套绑定，清除只删除手势效果。

ZIP 根目录包含 `gestures.json`、1～7 张 `asset_0.png`～`asset_6.png`，不允许目录、额外文件或未引用图片。总解压预算32 MiB，JSON64 KiB，PNG每张4 MiB、边长≤2048，累计≤8,388,608像素。仅静态PNG，不接受APNG或包内脚本。

```json
{
  "format": "beauty-gesture-effects",
  "version": 1,
  "rules": [{
    "id": "gesture_victory",
    "gesture": "VICTORY",
    "kind": "ANIMATION",
    "image": "asset_0.png",
    "frameWidth": 192,
    "frameHeight": 192,
    "columns": 6,
    "frameCount": 36,
    "fps": 20,
    "durationMs": 1800,
    "position": [0.5, 0.5],
    "width": 0.6
  }]
}
```

字段固定，未知字段拒绝。1～7条规则，每种模型手势最多一个效果：CLOSED_FIST、OPEN_PALM、POINTING_UP、THUMB_DOWN、THUMB_UP、VICTORY、I_LOVE_YOU。实验性规则仍可观察，不参与首版效果绑定。

图集按从左到右、从上到下排列，整图宽=`columns*frameWidth`，高=`ceil(frameCount/columns)*frameHeight`。columns1～8、frameCount1～60、fps1～30。ANIMATION要求≥2帧且durationMs=`ceil(frameCount*1000/fps)`；STICKER要求单帧单列，durationMs500～10000。两类时长均500～10000ms。位置是旋转、镜像后的显示画面归一化中心，原点左上；width是显示画面宽度比例0.1～1，保留图片比例，越界部分裁切。该位置固定在画面中，不跟随人脸或手。

SDK显式加载 `GestureEffectArchive.loadZip`，在所属GL线程配置 `GestureEffectPlayer` 并消费 `GestureAnalysisRuntime.pollEvents`，使用 `GestureEffectRenderer(delegate,player)` 包住美颜/场景渲染器。只消费TRIGGER：过期300ms/未来事件及重复ID拒绝；全局同时一个效果，播放中忽略而不排队，800ms冷却；结束消失、不循环。运行时仍需开启识别；默认SDK不会自行开启或安装素材。换源、停用、原图和关闭时必须清理播放器。

播放器、renderer只在同一ES3线程调用。解析/解码在IO线程完成，不在帧回调读ZIP。输出为临时借用的2D纹理，沿用现有帧所有权与同步契约。事件回调本身仍然只返回观测，不执行动画；只有显式接入播放器的宿主合成效果。

Android Demo已接入；Windows未验证iOS消费，网页不进行摄像头识别。设备夹具不等于真人识别准确率、持续性能或厂商远端推流验收。
