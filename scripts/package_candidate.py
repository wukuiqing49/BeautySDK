"""打包已构建的SDK候选版；不构建、不上传，不把候选版宣称为商用验收通过。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
import io
from publication_contract import validate_publication
from source_provenance import source_hashes
from trial_contract import validate_trial
from api_surface import check as check_api

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('beautysdk', 'lib_beauty', 'lib_beauty_resources', 'lib_beauty_mediapipe', 'lib_beauty_gpupixel')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--license-config', type=Path, required=True, help='Issuer public configuration used to build this commercial Release')
    args = parser.parse_args()
    license_config = json.loads(args.license_config.read_text(encoding='utf-8'))
    if set(license_config) != {'endpoint', 'keys'} or not license_config['keys'] or not license_config['endpoint'].startswith('https://'):
        raise SystemExit('Configured issuer HTTPS endpoint and public keys required')
    version = re.search(r'^sdk\s*=\s*"([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
    sample_version = re.search(r'^beautySdkVersion=(.+)$', (ROOT / 'samples/standalone/gradle.properties').read_text(), re.M).group(1).strip()
    if sample_version != version:
        raise SystemExit('Standalone sample SDK version differs from publication version')
    repo = ROOT / 'build/repo/com/beautysdk'
    trial = validate_trial(ROOT)
    check_api()
    entries = {}
    contracts = []
    current_sources = source_hashes(ROOT)
    for module in MODULES:
        folder = repo / module / version
        artifacts = {}
        for suffix in ('.aar', '.pom', '.module', '-sources.jar'):
            path = folder / f'{module}-{version}{suffix}'
            if not path.is_file():
                raise SystemExit(f'Missing publication: {module}{suffix}. Run publishBeautySdkToLocalBuildRepository first.')
            artifacts[path.name] = path.read_bytes()
        contracts.append(validate_publication(module, version, artifacts))
        with zipfile.ZipFile(io.BytesIO(artifacts[f'{module}-{version}.aar'])) as aar:
            source_module = 'lib_beauty_sdk' if module == 'beautysdk' else module
            record = json.loads(aar.read(f'assets/beautysdk-build/{source_module}.json'))
            if record.get('licensePolicy') != {'enforcedRelease': True, 'configured': True, 'publicConfig': license_config}:
                raise SystemExit(f'{module}: commercial license policy/configuration missing or mismatched; rebuild with -PbeautyLicenseConfig')
            if record.get('recordVersion') != 1 or record.get('module') != source_module or record.get('sdkVersion') != version or record.get('sourceHashes') != current_sources:
                raise SystemExit(f'{module}: build provenance does not match current sources; rebuild all SDK modules')
        for name, data in artifacts.items():
            entries[f'repo/com/beautysdk/{module}/{version}/{name}'] = data
    sample = ROOT / 'samples/standalone'
    for path in sample.rglob('*'):
        if not path.is_file():
            continue
        relative = path.relative_to(sample)
        if any(part in {'build', '.gradle', '.kotlin', '.idea'} for part in relative.parts) or path.name == 'local.properties':
            continue
        if path.suffix not in {'.gradle', '.properties', '.kt', '.xml', '.md'}:
            continue
        entries['sample/' + relative.as_posix()] = path.read_bytes()
    for name in ('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'):
        entries['sample/' + name] = (ROOT / name).read_bytes()
    # 文档与通知采用白名单，不遍历工作区或打入密钥/私有构建配置。
    for name in ('doc/LICENSING.md', 'doc/PRODUCT_STATUS.md', 'doc/FIRST_INTEGRATION.md', 'doc/RELEASE_CHECKLIST.md', 'doc/testing/TRIAL_ACCEPTANCE.md', 'doc/testing/TRIAL_BUILD_REPORT.md', 'resources/commercial/trial-release.json', 'resources/commercial/api-baseline.json', 'doc/COMMERCIAL_READINESS.md', 'doc/ANDROID_SDK_USAGE.md', 'doc/RESOURCE_SIGNATURE.md', 'doc/SDK_INTEGRATION_CONTRACT.md', 'resources/commercial/capabilities.json',
                 'doc/third-party/COMMERCIAL_INVENTORY.md', 'doc/third-party/MODELS.md', 'doc/third-party/MediaPipe-LICENSE',
                 'core/lib_beauty_gpupixel/src/main/assets/gpupixel/LICENSE', 'core/lib_beauty_gpupixel/src/main/assets/gpupixel/NOTICE'):
        entries[name] = (ROOT / name).read_bytes()
    entries['README.txt'] = ('BeautySDK candidate ' + version + '\nNot commercially qualified.\n'
        'Single dependency: com.beautysdk:beautysdk:' + version + '\n'
        'From sample/: gradlew -PbeautyRepo=../repo :app:assembleDebug\n'
        'Google/Maven Central dependencies and Android SDK must be available.\n'
        'No resource ZIP, signing private key or customer license is included.\n').encode()
    manifest = {'manifestVersion': 1, 'sdkVersion': version, 'qualification': 'pending', 'trialScope': trial, 'publicationContracts': contracts, 'sourceHashes': current_sources,
                'files': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for name, data in sorted(entries.items())}}
    entries['candidate.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # 禁止覆盖已有制品；相同SDK版本的正式发布不可静默替换。
    with args.output.open('xb') as output:
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(entries.items()):
                archive.writestr('beautysdk-candidate/' + name, data)
    print(f'Candidate written: {args.output}; qualification remains pending')


if __name__ == '__main__':
    main()
