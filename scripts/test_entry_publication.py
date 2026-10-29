"""对已发布的统一入口做正向校验及依赖缺失/作用域/版本篡改拒绝回归。"""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
from publication_contract import validate_publication

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.search(r'^sdk\s*=\s*"([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
PREFIX = f'beautysdk-{VERSION}'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


class EntryPublicationTest(unittest.TestCase):
    def setUp(self):
        folder = ROOT / 'build/repo/com/beautysdk/beautysdk' / VERSION
        self.artifacts = {PREFIX + suffix: (folder / (PREFIX + suffix)).read_bytes()
                          for suffix in ('.aar', '.pom', '.module', '-sources.jar')}

    def test_actual_publication(self):
        validate_publication('beautysdk', VERSION, self.artifacts)

    def test_runtime_only_api_is_rejected(self):
        pom = ET.fromstring(self.artifacts[PREFIX + '.pom'])
        pom.find('m:dependencies/m:dependency/m:scope', NS).text = 'runtime'
        self.artifacts[PREFIX + '.pom'] = ET.tostring(pom)
        with self.assertRaisesRegex(ValueError, 'exported'):
            validate_publication('beautysdk', VERSION, self.artifacts)

    def test_missing_compile_dependency_is_rejected(self):
        metadata = json.loads(self.artifacts[PREFIX + '.module'])
        variant = next(v for v in metadata['variants'] if v['attributes'].get('org.gradle.usage') == 'java-api')
        variant['dependencies'] = [d for d in variant['dependencies'] if d['module'] != 'lib_beauty_resources']
        self.artifacts[PREFIX + '.module'] = json.dumps(metadata).encode()
        with self.assertRaisesRegex(ValueError, 'incomplete SDK'):
            validate_publication('beautysdk', VERSION, self.artifacts)

    def test_mixed_sdk_versions_are_rejected(self):
        pom = ET.fromstring(self.artifacts[PREFIX + '.pom'])
        pom.find('m:dependencies/m:dependency/m:version', NS).text = '0.0.0'
        self.artifacts[PREFIX + '.pom'] = ET.tostring(pom)
        with self.assertRaisesRegex(ValueError, 'mismatched SDK'):
            validate_publication('beautysdk', VERSION, self.artifacts)


if __name__ == '__main__':
    unittest.main()
