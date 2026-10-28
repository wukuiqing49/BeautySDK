from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

import yaml


SCRIPT = Path(__file__).resolve().parents[1] / "validate_agent_structure.py"
SPEC = importlib.util.spec_from_file_location("validate_agent_structure", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class RequiredAgentFilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "AGENTS.md").write_text("# Test project\n", encoding="utf-8")
        for directory, names in validator.REQUIRED.items():
            target = self.root / ".agents" / directory
            target.mkdir(parents=True)
            for name in names:
                (target / name).touch()
        self.profile_path = self.root / ".agents/config/project-profile.yml"
        self.profile_path.parent.mkdir(parents=True)

    def validate(self, registrations=None):
        profile = {} if registrations is None else {"agentFiles": registrations}
        self.profile_path.write_text(yaml.safe_dump(profile), encoding="utf-8")
        errors = []
        validator.validate_required_files(self.root, errors)
        return errors

    def test_missing_entry_is_rejected_even_with_complete_directories(self):
        (self.root / "AGENTS.md").unlink()
        self.assertTrue(any("AGENTS.md" in error for error in self.validate()))

    def test_common_tools_do_not_require_product_specific_files(self):
        self.assertEqual([], self.validate())

    def test_registered_product_files_must_exist(self):
        registered = {"rules": ["product.md"], "workflows": ["audit-product.md"]}
        errors = self.validate(registered)
        self.assertTrue(any("rules/product.md" in error for error in errors))
        self.assertTrue(any("workflows/audit-product.md" in error for error in errors))
        (self.root / ".agents/rules/product.md").touch()
        (self.root / ".agents/workflows/audit-product.md").touch()
        self.assertEqual([], self.validate(registered))

    def test_unregistered_files_still_report_incomplete_routing(self):
        (self.root / ".agents/rules/product.md").touch()
        self.assertTrue(any("unregistered" in error for error in self.validate()))

    def test_bad_registration_does_not_crash_or_escape_directories(self):
        for registration in (["rules"], {"unknown": []}, {"rules": "product.md"},
                             {"rules": ["../product.md"]}, {"rules": [None]}):
            with self.subTest(registration=registration):
                self.assertTrue(self.validate(registration))


class ProjectLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.profile_path = self.root / ".agents/config/project-profile.yml"
        self.profile_path.parent.mkdir(parents=True)
        self.profile = {
            "project": {"lifecycle": "pre-scaffold"},
            "android": {}, "modules": {}, "validation": {}, "sourceOfTruth": {},
        }

    def validate(self, mode="auto"):
        self.profile_path.write_text(yaml.safe_dump(self.profile), encoding="utf-8")
        errors = []
        validator.validate_profile(self.root, errors, mode)
        return errors

    def test_pre_scaffold_allows_empty_current_facts(self):
        self.assertEqual([], self.validate())

    def test_forced_project_rejects_empty_facts(self):
        self.assertTrue(self.validate("project"))

    def test_partial_gradle_project_cannot_hide_behind_pre_scaffold(self):
        (self.root / "build.gradle.kts").touch()
        self.assertTrue(any("pre-scaffold" in error for error in self.validate()))

    def test_config_mode_still_checks_declared_fact_paths(self):
        self.profile["sourceOfTruth"] = {"requirements": "missing.md"}
        self.assertTrue(any("missing.md" in error for error in self.validate("config")))

    def test_initialized_project_requires_existing_modules(self):
        self.profile["project"]["lifecycle"] = "initialized"
        self.profile["android"] = {"uiToolkit": "xml"}
        self.profile["validation"] = {"debugBuild": "gradlew.bat :app:assembleDebug"}
        self.profile["modules"] = {"app": "app"}
        self.profile["sourceOfTruth"] = {
            "dependencyVersions": "gradle/libs.versions.toml",
            "gradleWrapper": "gradle/wrapper/gradle-wrapper.properties",
            "moduleGraph": "settings.gradle",
            "moduleBuildFiles": "**/build.gradle",
        }
        for raw in list(self.profile["sourceOfTruth"].values())[:-1] + ["build.gradle"]:
            path = self.root / raw
            path.parent.mkdir(parents=True, exist_ok=True)
            path.touch()
        self.assertTrue(any("modules.app" in error for error in self.validate()))
        (self.root / "app").mkdir()
        self.assertEqual([], self.validate())

    def test_unknown_lifecycle_is_rejected(self):
        self.profile["project"]["lifecycle"] = "ready"
        self.assertTrue(any("unknown project lifecycle" in error for error in self.validate()))


if __name__ == "__main__":
    unittest.main()
