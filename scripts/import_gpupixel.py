"""从固定 GPUPixel 源码导入本项目使用的 Shader 与 LUT；不下载、不执行上游构建。"""
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

REVISION = 'ef552bf8ce2d0d41fa9b979bfb5c7cf79374ca88'
ROOT = Path(__file__).resolve().parents[1]


def main():
    source = Path(sys.argv[1]).resolve()
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != REVISION:
        raise SystemExit(f'需要固定版本 {REVISION}，实际为 {actual}')
    if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'], text=True).strip():
        raise SystemExit('上游源码包含本地修改，请使用固定提交的干净工作区。')
    module = ROOT / 'core/lib_beauty_gpupixel'
    vendor = module / 'third_party/gpupixel'
    vendor.mkdir(parents=True, exist_ok=True)
    files = ['LICENSE', 'src/filter/beauty_face_unit_filter.cc',
             'src/filter/beauty_face_filter.cc', 'src/filter/box_mono_blur_filter.cc',
             'src/filter/box_difference_filter.cc', 'src/filter/box_high_pass_filter.cc',
             'src/filter/box_blur_filter.cc', 'src/filter/face_reshape_filter.cc',
             'src/filter/face_makeup_filter.cc', 'src/filter/lipstick_filter.cc', 'src/filter/blusher_filter.cc']
    assets = module / 'src/main/assets/gpupixel/res'
    assets.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name in files + [f'src/res/{name}.png' for name in
                         ('lookup_gray', 'lookup_origin', 'lookup_skin', 'lookup_light', 'mouth', 'blusher')]:
        data = (source / name).read_bytes()
        # Git 在 Windows 可能检出 CRLF；文本按 LF 校验，PNG 按原始字节校验。
        canonical = data if name.endswith('.png') else data.replace(b'\r\n', b'\n')
        hashes[name] = hashlib.sha256(canonical).hexdigest()
        target = assets / Path(name).name if '/res/' in name else vendor / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    text = (source / files[1]).read_text(encoding='utf-8')
    def shader(name):
        return re.search(r'const std::string ' + name + r' = R"\((.*?)\)";', text, re.S).group(1)
    generated = module / 'src/main/cpp/gpupixel_shaders.h'
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text('''// GPUPixel Copyright © 2021 PixPark. Apache-2.0，许可证见 third_party/gpupixel/LICENSE。
// 由 scripts/import_gpupixel.py 从固定版本生成；GLES Shader 内容保持上游原样。
#pragma once
''' + '\n'.join(f'inline constexpr const char* {symbol} = R"GPUPIXEL({shader(name)})GPUPIXEL";'
                 for symbol, name in [('kBeautyVertex', 'kGPUImageBaseBeautyFaceVertexShaderString'),
                                      ('kBeautyFragment', 'kGPUImageBaseBeautyFaceFragmentShaderString')]), encoding='utf-8')
    (vendor / 'SOURCE.json').write_text(json.dumps({'repository': 'https://github.com/pixpark/gpupixel',
        'revision': REVISION, 'license': 'Apache-2.0',
        'hashNormalization': 'Text CRLF to LF; PNG bytes unchanged', 'sha256': hashes}, indent=2) + '\n', encoding='utf-8')
    notices = module / 'src/main/assets/gpupixel'
    shutil.copyfile(vendor / 'LICENSE', notices / 'LICENSE')
    (notices / 'NOTICE').write_text('''GPUPixel — Copyright © 2021 PixPark. All rights reserved.
Source: https://github.com/pixpark/gpupixel
Revision: ''' + REVISION + '''
License: Apache-2.0 (see LICENSE).
BeautySDK adapts the beauty shaders, radius-4 box blur, high-pass difference, LUT assets,
curveWarp/enlargeEye, lipstick/blusher textures and multiply blending.
BeautySDK maps localized lip triangles and cheek quads to MediaPipe-478;
the lip interior is excluded and input alpha is preserved.
The EGL owner, JNI bridge and texture pipeline are implemented by BeautySDK;
the complete GPUPixel runtime, detector and model files are not included.
''', encoding='utf-8')
    print('已导入固定版本 Shader、参考源码、四张 LUT、口红与腮红贴图 与许可证。')


if __name__ == '__main__':
    main()
