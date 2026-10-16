#!/usr/bin/env python3
"""Run audit probes using dependency classpaths from a completed Maven verify."""
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
java_home = os.environ.get("JAVA_HOME")


def executable(name):
    return str(Path(java_home) / "bin" / name) if java_home else name


def classpath(module):
    reports = sorted((ROOT / module / "target/surefire-reports").glob("TEST-*.xml"))
    if not reports:
        raise SystemExit(f"Missing {module} test reports; first run ./mvnw -B verify with JDK 17.")
    properties = ET.parse(reports[0]).getroot().find("properties")
    for prop in properties:
        if prop.get("name") == "java.class.path":
            return prop.get("value")
    raise SystemExit("Surefire report has no java.class.path")


with tempfile.TemporaryDirectory(prefix="hermit-audit-classes-") as classes:
    for module, source, main in [
        ("hermit-crab", "CrabAuditProbe.java", "dev.hermit.crab.engine.CrabAuditProbe"),
        ("hermit-reef", "ReefAuditProbe.java", "dev.hermit.audit.ReefAuditProbe"),
    ]:
        cp = classpath(module)
        subprocess.run([executable("javac"), "--release", "17", "-cp", cp,
                        "-d", classes, str(HERE / source)], check=True, cwd=ROOT)
        subprocess.run([executable("java"), "-cp", classes + os.pathsep + cp, main],
                       check=True, cwd=ROOT, timeout=60)
