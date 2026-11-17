#!/usr/bin/env python3
"""Run Gradle behind a captured stream boundary, including JVM shutdown output.

Only the allowlisted XML listener generates published test diagnostics. Gradle
can forward worker shutdown streams outside its testLogging controls, so never
forward Gradle's streams, even on build/compile/infrastructure failure.
"""
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

COMMANDS = {
    'build': ['clean', 'build'],
    'migration': ['migrationTest'],
    'fixture': ['reporterFixtureTest', '--rerun-tasks'],
}
REPORTS = {'build': 'test', 'migration': 'migrationTest', 'fixture': 'reporterFixtureTest'}

def valid_report(xml, require_execution=False):
    cases = ET.fromstring(xml).findall('testcase')
    return bool(cases and not any(case.find('failure') is not None for case in cases)
                and (not require_execution or not any(case.find('skipped') is not None for case in cases)))

def main():
    mode = sys.argv[1]
    args = COMMANDS[mode]
    result = subprocess.run(['./gradlew', '-I', 'ci/safe-tests.gradle.kts', *args, '--no-daemon'],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1800)
    report = Path('build/safe-results') / REPORTS[mode] / 'results.xml'
    if report.exists():
        for case in ET.parse(report).getroot().findall('testcase'):
            outcome = 'E_TEST_FAILURE' if case.find('failure') is not None else (
                'SKIPPED' if case.find('skipped') is not None else 'PASSED')
            print(f'{case.get("classname")} {case.get("name")} {case.get("time")}s {outcome}')
    valid = (result.returncode == 0 and report.exists()
             and valid_report(report.read_bytes(), require_execution=mode == 'migration'))
    print('JAVA_CHECK_OK' if valid else 'E_JAVA_CHECK')
    return 0 if valid else 1

if __name__ == '__main__':
    try: sys.exit(main())
    except Exception:
        print('E_JAVA_CHECK', file=sys.stderr)
        sys.exit(1)
