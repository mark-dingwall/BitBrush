#!/usr/bin/env python3
"""Capture the deliberately failing child; never forward its diagnostics."""
import os
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET

sentinel = 'SECRET_' + uuid.uuid4().hex
report = Path('build/safe-results/reporterFixtureTest/results.xml')
report.unlink(missing_ok=True)
valid = False
try:
    child = subprocess.run([os.sys.executable, 'ci/java-check.py', 'fixture'],
                           env={**os.environ, 'REPORTER_SENTINEL': sentinel},
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240)
    published = report.read_bytes()
    cases = ET.fromstring(published).findall('testcase')
    valid = (child.returncode == 1 and sentinel.encode() not in child.stdout + child.stderr + published
             and len(cases) == 1
             and cases[0].get('classname') == 'au.com.dingwall.mark.bitbrush.CredentialReporterFixture'
             and cases[0].get('name') == 'designatedFailure'
             and len(cases[0]) == 1 and cases[0][0].tag == 'failure'
             and cases[0][0].get('message') == 'E_TEST_FAILURE')
except Exception:
    pass
finally:
    report.unlink(missing_ok=True)
print('SAFE_JAVA_REPORTER_OK' if valid else 'SAFE_JAVA_REPORTER_INVALID')
raise SystemExit(0 if valid else 1)
