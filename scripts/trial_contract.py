"""候选范围与验收记录门禁。检查证据存在/摘要，不代替真人或授权审核。"""
import hashlib
import json
from pathlib import Path
import re

GATES = {'human-quality', 'device-soak', 'external-integration', 'distribution-rights', 'signed-release'}


def validate_trial(root, require_qualified=False):
    root = Path(root).resolve()
    data = json.loads((root / 'resources/commercial/trial-release.json').read_text(encoding='utf-8'))
    capabilities = json.loads((root / 'resources/commercial/capabilities.json').read_text(encoding='utf-8'))
    version = re.search(r'^sdk\s*=\s*"([^"]+)"', (root / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
    if data.get('manifestVersion') != 1 or data.get('sdkVersion') != version or data.get('stage') != 'limited-trial':
        raise ValueError('Invalid trial version or stage')
    primary = data.get('primaryFeatures', [])
    candidate = {item['id'] for item in capabilities['features'] if item['tier'] == 'candidate'}
    if not primary or len(set(primary)) != len(primary) or not set(primary) <= candidate:
        raise ValueError('Primary scope contains non-candidate or duplicate features')
    gates = data.get('gates', [])
    if len(gates) != len(GATES) or {g['id'] for g in gates} != GATES:
        raise ValueError('Required qualification gates missing or duplicated')
    for gate in gates:
        if gate['status'] not in {'pending', 'passed', 'failed'}:
            raise ValueError('Unknown qualification status')
        if gate['status'] == 'passed' and (not gate.get('reviewer') or not gate.get('reviewedAt') or not gate.get('evidence')):
            raise ValueError('Passed gate requires reviewer, date and evidence')
        for evidence in gate.get('evidence', []):
            path = (root / evidence['path']).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                raise ValueError('Evidence must be an existing project file')
            if hashlib.sha256(path.read_bytes()).hexdigest() != evidence['sha256']:
                raise ValueError('Evidence digest mismatch')
    qualified = all(g['status'] == 'passed' for g in gates)
    if type(data.get('qualified')) is not bool or data['qualified'] != qualified:
        raise ValueError('Qualification claim conflicts with gates')
    if require_qualified and not qualified:
        raise ValueError('Qualification pending: ' + ', '.join(g['id'] for g in gates if g['status'] != 'passed'))
    return data


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--require-qualified', action='store_true')
    args = parser.parse_args()
    record = validate_trial(Path(__file__).resolve().parents[1], args.require_qualified)
    print(f"Trial scope valid: {record['sdkVersion']}; qualified={record['qualified']}")
