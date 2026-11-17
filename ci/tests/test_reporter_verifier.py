import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('java_verifier', Path(__file__).parents[1] / 'verify-java-reporter.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

REPORT = '''<testsuite><testcase classname="au.com.dingwall.mark.bitbrush.CredentialReporterFixture" name="designatedFailure"><failure message="E_TEST_FAILURE"/></testcase></testsuite>'''

class ReporterVerifierTest(unittest.TestCase):
    def verify_child(self, *, marker=False, report=REPORT, exit_code=1, leak=False, timeout=False):
        with tempfile.TemporaryDirectory() as directory:
            result_file = Path(directory) / 'results.xml'
            def child(args, *, env, **kwargs):
                if timeout: raise subprocess.TimeoutExpired(args, 240)
                if report is not None: result_file.write_text(report)
                if marker:
                    Path(env['REPORTER_EXPECTED_FILE']).write_text('E_EXPECTED_CREDENTIAL_FAILURE')
                stdout = env['REPORTER_SENTINEL'].encode() if leak else b''
                return subprocess.CompletedProcess(args, exit_code, stdout, b'')
            with patch.object(verifier, 'REPORT', result_file), patch.object(verifier.subprocess, 'run', side_effect=child):
                return verifier.verify()

    def test_requires_witness_of_intended_assertion(self):
        self.assertFalse(self.verify_child())
        self.assertTrue(self.verify_child(marker=True))
    def test_rejects_unexpected_success_and_additional_failure(self):
        self.assertFalse(self.verify_child(marker=True, exit_code=0))
        self.assertFalse(self.verify_child(marker=True, report=REPORT.replace('</testsuite>', '<testcase><failure/></testcase></testsuite>')))
    def test_rejects_missing_results_and_timeout(self):
        self.assertFalse(self.verify_child(report=None))
        self.assertFalse(self.verify_child(timeout=True))
    def test_rejects_console_leak_even_with_expected_witness(self):
        self.assertFalse(self.verify_child(marker=True, leak=True))

if __name__ == '__main__': unittest.main()
