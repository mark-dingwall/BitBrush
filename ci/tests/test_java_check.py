import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('java_check', Path(__file__).parents[1] / 'java-check.py')
java_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(java_check)

class JavaCheckTest(unittest.TestCase):
    def test_migration_requires_executed_results(self):
        for xml in ['<testsuite/>', '<testsuite><testcase><skipped/></testcase></testsuite>',
                    '<testsuite><testcase><failure message="E_TEST_FAILURE"/></testcase></testsuite>']:
            self.assertFalse(java_check.valid_report(xml, require_execution=True))
        self.assertTrue(java_check.valid_report('<testsuite><testcase/></testsuite>', require_execution=True))
    def test_unit_report_allows_intentional_calibration_skip(self):
        self.assertTrue(java_check.valid_report('<testsuite><testcase/><testcase><skipped/></testcase></testsuite>'))
        self.assertFalse(java_check.valid_report('<testsuite/>'))
        self.assertFalse(java_check.valid_report('<testsuite><testcase><failure/></testcase></testsuite>'))

if __name__ == '__main__': unittest.main()
