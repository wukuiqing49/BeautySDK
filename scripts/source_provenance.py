"""与gradle/beauty-publishing.gradle保持一致的SDK源文件摘要清单。"""
import hashlib

MODULES = ('lib_beauty', 'lib_beauty_resources', 'lib_beauty_mediapipe', 'lib_beauty_gpupixel', 'lib_beauty_sdk')
EXCLUDED = {'build', '.cxx', '.gradle', '.kotlin'}


def source_hashes(root):
    paths = []
    for module in MODULES:
        folder = root / 'core' / module
        paths.extend(path for path in folder.rglob('*') if path.is_file() and not EXCLUDED.intersection(path.relative_to(folder).parts))
    paths.extend(root / name for name in ('build.gradle', 'settings.gradle', 'gradle/libs.versions.toml', 'gradle/beauty-publishing.gradle'))
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}
