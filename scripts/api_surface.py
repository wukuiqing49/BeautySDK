"""Conservative JVM signature guard for published SDK AARs; not a Kotlin/native ABI checker."""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('beautysdk', 'lib_beauty', 'lib_beauty_resources', 'lib_beauty_mediapipe', 'lib_beauty_gpupixel')
BASELINE = ROOT / 'resources/commercial/api-baseline.json'


def signatures(output):
    result = set()
    owner = None
    for raw in output.splitlines():
        line = raw.strip()
        if line.endswith('{'):
            owner = line[:-1].strip() if line.startswith(('public ', 'protected ')) else None
            if owner:
                result.add(owner)
        elif line == '}':
            owner = None
        elif owner and line.startswith(('public ', 'protected ')):
            result.add(owner + ' :: ' + line)
    return result


def snapshot(root=ROOT):
    version = re.search(r'^sdk\s*=\s*"([^"]+)"', (root / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
    modules = {}
    with tempfile.TemporaryDirectory(prefix='beauty-api-') as temp:
        for module in MODULES:
            aar = root / f'build/repo/com/beautysdk/{module}/{version}/{module}-{version}.aar'
            with zipfile.ZipFile(aar) as archive:
                data = archive.read('classes.jar')
            jar = Path(temp) / f'{module}.jar'
            jar.write_bytes(data)
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                names = sorted(name[:-6].replace('/', '.') for name in archive.namelist()
                               if name.startswith('com/beautysdk/') and name.endswith('.class'))
            found = set()
            for offset in range(0, len(names), 40):
                output = subprocess.run(['javap', '-protected', '-classpath', str(jar), *names[offset:offset + 40]],
                                        check=True, capture_output=True, text=True, encoding='utf-8').stdout
                found.update(signatures(output))
            if not found:
                raise ValueError(f'Empty API surface: {module}')
            modules[module] = sorted(found)
    return {'formatVersion': 1, 'sdkVersion': version, 'modules': modules}


def removed_signatures(baseline, current):
    # Native-to-JVM wrappers preserve the JVM callable descriptor. Keep the stored
    # historical baseline, but do not treat the implementation modifier as removal.
    def callable_signature(value):
        return re.sub(r'\bnative\s+', '', value) if '(' in value else value
    removed = {}
    for module, values in baseline['modules'].items():
        available = {callable_signature(v) for v in current['modules'].get(module, [])}
        # Widening final -> open is safe; the reverse remains a breaking change.
        def present(value):
            signature = callable_signature(value)
            return signature in available or ('public final class ' in signature and
                signature.replace('public final class ', 'public class ') in available)
        missing = sorted(v for v in values if '$WhenMappings' not in v and not present(v))
        if missing:
            removed[module] = missing
    return removed


def check(current=None):
    current = current or snapshot()
    baseline = json.loads(BASELINE.read_text(encoding='utf-8'))
    if baseline.get('formatVersion') != 1 or set(baseline['modules']) != set(MODULES):
        raise ValueError('Invalid API baseline')
    removed = removed_signatures(baseline, current)
    if removed:
        raise ValueError('Removed/changed JVM signatures; review version and migration before accepting a baseline:\n' + json.dumps(removed, ensure_ascii=False, indent=2))
    return current


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['check', 'init'])
    args = parser.parse_args()
    if args.mode == 'init':
        data = snapshot()
        with BASELINE.open('x', encoding='utf-8') as target:
            json.dump(data, target, ensure_ascii=False, indent=2)
            target.write('\n')
        print('Initial JVM API baseline created; no previous-version compatibility claim')
    else:
        data = check()
        print(f"JVM API baseline preserved: {sum(map(len, data['modules'].values()))} signatures")
