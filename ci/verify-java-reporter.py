#!/usr/bin/env python3
"""Capture the deliberately failing child; never forward its diagnostics."""
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import uuid
import xml.etree.ElementTree as ET

REPORT = Path('build/safe-results/reporterFixtureTest/results.xml')
EXPECTED_CODE = 'E_EXPECTED_CREDENTIAL_FAILURE'

def verify(mode='failure'):
    sentinel = 'SECRET_' + uuid.uuid4().hex
    REPORT.unlink(missing_ok=True)
    try:
        with TemporaryDirectory(prefix='bitbrush-java-reporter-') as directory:
            expected_file = Path(directory) / 'expected-code'
            child = subprocess.run([os.sys.executable, 'ci/java-check.py', 'fixture'],
                env={**os.environ, 'REPORTER_SENTINEL': sentinel, 'REPORTER_FIXTURE_MODE': mode,
                     'REPORTER_EXPECTED_FILE': str(expected_file)},
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240)
            published = REPORT.read_bytes()
            cases = ET.fromstring(published).findall('testcase')
            return (child.returncode == 1
                    and expected_file.read_text() == EXPECTED_CODE
                    and sentinel.encode() not in child.stdout + child.stderr + published
                    and len(cases) == 1
                    and cases[0].get('classname') == 'au.com.dingwall.mark.bitbrush.CredentialReporterFixture'
                    and cases[0].get('name') == 'designatedFailure'
                    and len(cases[0]) == 1 and cases[0][0].tag == 'failure'
                    and cases[0][0].get('message') == 'E_TEST_FAILURE')
    except Exception:
        return False
    finally:
        REPORT.unlink(missing_ok=True)

if __name__ == '__main__':
    valid = verify() and not verify('success') and not verify('same-test-error')
    print('SAFE_JAVA_REPORTER_OK' if valid else 'SAFE_JAVA_REPORTER_INVALID')
    raise SystemExit(0 if valid else 1)
