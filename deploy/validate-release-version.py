"""Reject mismatched release tags before publishing images or JARs."""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def validate(root, tag):
    version = tag.removeprefix("v").removeprefix("V")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("发布标签必须是正式版本，例如 v2.3.22")
    pom = ET.parse(root / "pom.xml").getroot()
    backend = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
    package = json.loads((root / "vue-code/package.json").read_text(encoding="utf-8"))
    lock = json.loads((root / "vue-code/package-lock.json").read_text(encoding="utf-8"))
    versions = [backend, package["version"], lock["version"], lock["packages"][""]["version"]]
    if any(value != version for value in versions):
        raise ValueError(f"发布标签 {version} 与后端/前端/锁文件版本不一致: {versions}")


if __name__ == "__main__":
    validate(Path(__file__).resolve().parent.parent, sys.argv[1])
