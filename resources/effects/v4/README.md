# 图片睫毛资源 V4：制作、打包与使用

本次补齐图片睫毛链路：透明 PNG → Web 绑定编辑 → BEAUTY V4 ZIP → Android GPUPixel / iOS Metal。V1 贴纸、V2 背景/运镜、V3 美颜继续兼容；主题 bundleVersion=1 不变，可将 V4 包作为 beauty.zip。眼影、眼线、眉毛的独立图片层尚未实现，不能将 EYELASH 改名为其他种类使用。

## 同事如何制作

1. 打开独立 Web 工程的 eyes.html（首页“图片睫毛”，或主题美颜编辑中的“图片睫毛”入口）。线上版本需另外发布，本地修改不会自动出现在 Pages。
2. 使用绘图工具制作 RGBA 透明 PNG。建议单眼横向画布 512×160，睫毛尖朝上、根部朝下，留透明边缘；避免白底、白色描边及有色透明像素。示例是自有程序生成素材，不代表最终商业美术质量。
3. 上传 PNG，将蓝线的 9 个根部点从素材左到右对准根部；渲染采样绑定曲线上方区域。可拖动点、用方向键微调，或填写 U/V。根部需从外眼角到内眼角制作。
4. 设置相对长度（眼宽的 0.02…0.5）、强度及右眼镜像；检查眼部特写、完整人像与开合眼示意。中央预览不做人脸检测，也不代表侧脸遮挡质量。
5. 导出 ZIP，单独加载或在主题编辑中“应用到主题”后导出完整主题。保存本机草稿只存当前浏览器，不会上传素材。

自然、浓密、眼尾三款在 samples/lash_natural.zip、lash_dense.zip、lash_cat.zip。Android Demo「内置资源」中有同名入口，现有本地导入、网络目录下载和下次恢复共用资源加载流程。网络发布需重新 npm run build 生成 catalog.json 和摘要后部署。

## 协议与校验

ZIP 根目录包含 manifest.json、preview.png 和 eyeMakeup.image 指向的 PNG。沿用单包16MiB、单PNG4MiB及既有解码像素预算；拒绝缺失、重复、未引用文件与非法路径。JSON Schema 负责结构，运行时还校验曲线单调和像素内容。

V4 仅用于 BEAUTY，保留 V3 的 smoothing、whitening、filter 及完整12项 adjustments，新增 adjustments.eyelashes（0…1）和必填 config.eyeMakeup：

```json
{"kind":"EYELASH","image":"eyelash.png","root":[[0.03,0.86],[0.1475,0.8294],[0.265,0.8034],[0.3825,0.7861],[0.5,0.78],[0.6175,0.7861],[0.735,0.8034],[0.8525,0.8294],[0.97,0.86]],"length":0.2,"mirrorRight":true}
```

root 为 PNG 左上角原点的归一化 UV，固定9点；U在0…1、V在0.3…1，U相邻至少0.01、总跨度至少0.5（浮点容差1e-6）。PNG必须同时有可见和透明像素，禁止完全透明/不透明图片；浏览器上传与平台解码器执行像素检查。未知字段、缺字段、非法枚举与类型直接拒绝，不能把 V4 字段写入旧版本。

## SDK 消费与性能

Android：后台调用 EffectResourcePack.load，使用 pack.applyTo(settings) 得到含 EyeMakeup 的 BeautySettings，再沿既有 BeautySession / BeautyEnginePipeline 发布。不需要相机专用入口；直播引擎需先按公共帧契约提供纹理、尺寸、坐标和所有权。切换旧美颜包会清除旧图片睫毛，强度为0时不绘制。

关键点使用输入纹理原始帧归一化坐标（0…1，左上角原点），几何内部按宽高换算像素，旋转/镜像由既有帧映射统一处理，不再单独对图片旋转。每眼绑定9个上眼睑点、生成9列4行网格，眉部决定向外方向；开合眼近似调整高度。mirrorRight=true 时两眼沿外→内使用同一素材，false 时右眼反向采样。

纹理只在换素材时上传并生成 mipmap；小脸缩小时避免细睫毛欠采样消失。逐帧只更新预分配顶点并 GPU 采样合成，不创建 Bitmap、不为睫毛新增检测器、不等待推理。Android 复用既有贴妆 FBO。有效关键点缺失时对应眼旁路；仍须按公共会话规则管理检测结果时效。

iOS：同一 ZIP 由 EffectResourcePack 解析，MetalEyeMakeupRenderer 独立消费，MetalThemeRenderer 已串接；宿主提供478点原始帧归一化坐标及 Metal commandBuffer，详见 ios/BeautyResources/README.md。iOS 不附带检测器，当前 Windows 环境没有编译或运行 Swift/Metal。

本轮验证是资源/几何/GL功能回归，不代表快速转头、真实眨眼、遮挡、持续温升或厂商推流已经验收。当前为2D网格，没有眼睑深度遮挡或3D睫毛模型。

## 重生成示例

独立 Web 工程：安装 Pillow 后运行 python scripts/generate-eyelash-art.py，再运行 node scripts/generate-eyelash-samples.mjs、npm test、npm run build。素材生成器为本项目自有代码，生成图可作为本项目示例使用；替换为第三方素材时由提供者确认授权。Web 测试生成跨平台几何夹具和主题样例，Android/iOS fixtures 与之同步。
