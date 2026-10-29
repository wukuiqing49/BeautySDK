import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from trial_contract import validate_trial
from api_surface import removed_signatures, signatures

ROOT = Path(__file__).resolve().parents[1]


class TrialContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('resources/commercial/trial-release.json', 'resources/commercial/capabilities.json', 'gradle/libs.versions.toml'):
            dest = self.root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes((ROOT / name).read_bytes())
        self.file = self.root / 'resources/commercial/trial-release.json'
        self.data = json.loads(self.file.read_text())

    def write(self):
        self.file.write_text(json.dumps(self.data))

    def test_pending_is_valid_trial_but_not_qualified(self):
        self.assertFalse(validate_trial(self.root)['qualified'])
        with self.assertRaisesRegex(ValueError, 'Qualification pending'):
            validate_trial(self.root, True)

    def test_claim_without_reviews_rejected(self):
        self.data['qualified'] = True
        self.write()
        with self.assertRaisesRegex(ValueError, 'conflicts'):
            validate_trial(self.root)

    def test_missing_evidence_rejected(self):
        self.data['gates'][0]['status'] = 'passed'
        self.write()
        with self.assertRaisesRegex(ValueError, 'reviewer'):
            validate_trial(self.root)

    def test_experimental_primary_rejected(self):
        self.data['primaryFeatures'].append('gesture.effects.v1')
        self.write()
        with self.assertRaisesRegex(ValueError, 'non-candidate'):
            validate_trial(self.root)

    def test_reviewed_evidence_digest_is_enforced(self):
        evidence = self.root / 'review.txt'
        evidence.write_text('fixture evidence, not a real qualification')
        self.data['gates'][0].update(status='passed', reviewer='test', reviewedAt='2026-10-29',
            evidence=[{'path': 'review.txt', 'sha256': hashlib.sha256(evidence.read_bytes()).hexdigest()}])
        self.write()
        self.assertFalse(validate_trial(self.root)['qualified'])
        evidence.write_text('changed')
        with self.assertRaisesRegex(ValueError, 'digest'):
            validate_trial(self.root)

    def test_duplicate_gate_cannot_replace_required_review(self):
        self.data['gates'][1] = self.data['gates'][0]
        self.write()
        with self.assertRaisesRegex(ValueError, 'missing or duplicated'):
            validate_trial(self.root)

    def test_api_removal_detected_addition_allowed(self):
        old = {'modules': {'sdk': ['public void start();']}}
        new = copy.deepcopy(old)
        new['modules']['sdk'].append('public void stop();')
        self.assertFalse(removed_signatures(old, new))
        new['modules']['sdk'].remove('public void start();')
        self.assertTrue(removed_signatures(old, new))

    def test_api_parser_ignores_non_public_classes(self):
        actual = signatures('class Hidden {\n public void x();\n}\npublic final class Visible {\n public void start();\n}\n')
        self.assertEqual(actual, {'public final class Visible', 'public final class Visible :: public void start();'})

    def test_native_wrapper_preserves_descriptor_but_not_visibility_or_arguments(self):
        old = {'modules': {'sdk': ['public final native int render(long, int);']}}
        self.assertFalse(removed_signatures(old, {'modules': {'sdk': ['public final int render(long, int);']}}))
        self.assertTrue(removed_signatures(old, {'modules': {'sdk': ['private final int render(long, int);']}}))
        self.assertTrue(removed_signatures(old, {'modules': {'sdk': ['public final int render(long);']}}))


if __name__ == '__main__':
    unittest.main()
