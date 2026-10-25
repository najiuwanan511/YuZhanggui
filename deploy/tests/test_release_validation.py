import importlib.util
import json
import re
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_validator", ROOT / "deploy/validate-release-version.py")
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class ReleaseValidationTest(unittest.TestCase):
    def test_release_rejects_source_version_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "vue-code").mkdir()
            (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.3.22</version></project>')
            (root / "vue-code/package.json").write_text(json.dumps({"version": "2.3.22"}))
            (root / "vue-code/package-lock.json").write_text(json.dumps({"version": "2.3.22", "packages": {"": {"version": "2.3.22"}}}))
            VALIDATOR.validate(root, "v2.3.22")
            with self.assertRaises(ValueError):
                VALIDATOR.validate(root, "v2.3.23")

    def test_agent_checks_internal_jar_version_before_drain(self):
        script = (ROOT / "deploy/self-update/yuzhanggui-update-agent.sh").read_text(encoding="utf-8")
        check = re.search(r"<<'PY_VERSION'\n(.*?)\nPY_VERSION", script, re.S).group(1)
        self.assertLess(script.index("<<'PY_VERSION'"), script.index('status "DRAINING"'))
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "app.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/build-info.properties", "build.version=2.3.22\n")
            with patch.object(sys, "argv", ["validator", str(jar), "2.3.22"]):
                exec(check, {})
            with patch.object(sys, "argv", ["validator", str(jar), "2.3.23"]):
                with self.assertRaises(SystemExit):
                    exec(check, {})


if __name__ == "__main__":
    unittest.main()
