"""下载公开的官方测试图片到被忽略的构建目录，不打入产品 APK。"""
from pathlib import Path
import hashlib
import urllib.request

root = Path(__file__).resolve().parents[1]
target = root / "app/build/test-assets/portrait.jpg"
expected = "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
if not target.exists():
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(urllib.request.urlopen(
        "https://storage.googleapis.com/mediapipe-assets/portrait.jpg", timeout=60).read())
assert hashlib.sha256(target.read_bytes()).hexdigest() == expected, "测试图片 SHA256 不匹配"
print("官方人像测试图片 SHA256 已验证")

# 官方手势教程公开图片，仅打入测试APK。
gestures = {
    "thumbs_up": "2aee0e3a69ba5f0d3287597e61d265f4f3ac2a44ccec198dddd2639b0c8ef7ba",
    "victory": "6ac265f3ace6a6c4ac4a9079b63fcce4ab6517272afb1e430857f55ef324fde6",
    "pointing_up": "f4a701316b63dd8fa56e622f2b3042766369ccc189f0d89513f803cd985b993b",
    "thumbs_down": "080b589bf3b91ba10cc6c03645be3b5b491a8ca8c8f7d65b5f32c563ae266af9",
}
for name, digest in gestures.items():
    target = root / f"app/build/test-assets/gesture_{name}.jpg"
    if not target.exists():
        target.write_bytes(urllib.request.urlopen(
            f"https://storage.googleapis.com/mediapipe-tasks/gesture_recognizer/{name}.jpg", timeout=30).read())
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest, name
    print(f"官方手势测试图片 SHA256 已验证: {name}")
