"""验证本地模型版本，禁止悄悄替换同名模型。"""
from pathlib import Path
import hashlib

root = Path(__file__).resolve().parents[1]
models = {
    "core/lib_beauty_mediapipe/src/main/assets/gesture_recognizer.task":
        (8373440, "97952348cf6a6a4915c2ea1496b4b37ebabc50cbbf80571435643c455f2b0482"),
    "core/lib_beauty_mediapipe/src/main/assets/face_landmarker.task":
        (3758596, "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"),
}
for name, (size, digest) in models.items():
    data = (root / name).read_bytes()
    assert len(data) == size and hashlib.sha256(data).hexdigest() == digest, name
    print(f"OK {name}: {size} bytes")
