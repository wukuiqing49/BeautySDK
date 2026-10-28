"""核对被固定导入的上游源码、LUT、许可证与生成的美颜 Shader，防止来源漂移。"""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
module = root / 'core/lib_beauty_gpupixel'
vendor = module / 'third_party/gpupixel'
manifest = json.loads((vendor / 'SOURCE.json').read_text(encoding='utf-8'))
assert manifest['revision'] == 'ef552bf8ce2d0d41fa9b979bfb5c7cf79374ca88'
for name, expected in manifest['sha256'].items():
    path = module / 'src/main/assets/gpupixel/res' / Path(name).name if '/res/' in name else vendor / name
    data = path.read_bytes()
    canonical = data if name.endswith('.png') else data.replace(b'\r\n', b'\n')
    assert hashlib.sha256(canonical).hexdigest() == expected, f'来源文件变化：{name}'
assert (vendor / 'LICENSE').read_bytes() == (module / 'src/main/assets/gpupixel/LICENSE').read_bytes()
source = (vendor / 'src/filter/beauty_face_unit_filter.cc').read_text(encoding='utf-8')
generated = (module / 'src/main/cpp/gpupixel_shaders.h').read_text(encoding='utf-8')
for symbol, upstream in [('kBeautyVertex', 'kGPUImageBaseBeautyFaceVertexShaderString'),
                         ('kBeautyFragment', 'kGPUImageBaseBeautyFaceFragmentShaderString')]:
    original = re.search(r'const std::string ' + upstream + r' = R"\((.*?)\)";', source, re.S).group(1)
    actual = re.search(r'const char\* ' + symbol + r' = R"GPUPIXEL\((.*?)\)GPUPIXEL";', generated, re.S).group(1)
    assert actual == original, f'Shader 与固定上游不一致：{symbol}'
print(f'GPUPixel source verification passed: {len(manifest["sha256"])} files, 2 exact shaders, packaged license.')
