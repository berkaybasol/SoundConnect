from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from verify_marketplace_test_reports import verify_reports


class RequiredMarketplaceReportsTest(unittest.TestCase):
    def check_report(self, content):
        with TemporaryDirectory() as directory:
            if content is not None:
                (Path(directory) / "TEST-critical.xml").write_text(content, encoding="utf-8")
            return verify_reports(Path(directory), ("critical",))

    def test_success_requires_executed_cases(self):
        self.assertEqual([], self.check_report(
            '<testsuite name="critical" tests="1" failures="0" errors="0" skipped="0"><testcase classname="critical" name="real-db"/></testsuite>'))

    def test_duplicate_test_identity_is_rejected(self):
        self.assertTrue(self.check_report(
            '<testsuite name="critical" tests="2" failures="0" errors="0" skipped="0">'
            '<testcase classname="critical" name="same"/><testcase classname="critical" name="same"/></testsuite>'))

    def test_exact_source_inventory_cannot_silently_shrink(self):
        with TemporaryDirectory() as directory:
            (Path(directory) / "TEST-critical.xml").write_text(
                '<testsuite name="critical" tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="critical" name="guard()"/></testsuite>', encoding="utf-8")
            self.assertEqual([], verify_reports(Path(directory), ("critical",), {"critical": ["guard()"]}))
            self.assertTrue(verify_reports(Path(directory), ("critical",), {"critical": ["guard()", "startup()"]}))

    def test_missing_and_empty_suites_fail(self):
        self.assertTrue(self.check_report(None))
        self.assertTrue(self.check_report('<testsuite name="critical" tests="0" failures="0" errors="0" skipped="0"/>'))

    def test_docker_unavailable_skip_cannot_pass(self):
        self.assertTrue(self.check_report(
            '<testsuite name="critical" tests="1" failures="0" errors="0" skipped="1"><testcase name="postgres"><skipped/></testcase></testsuite>'))

    def test_failed_case_cannot_hide_behind_incorrect_summary(self):
        self.assertTrue(self.check_report(
            '<testsuite name="critical" tests="1" failures="0" errors="0" skipped="0"><testcase name="postgres"><failure/></testcase></testsuite>'))

    def test_incomplete_and_wrong_suite_reports_fail(self):
        for content in ('<testsuite', '<testsuite name="critical"/>',
                        '<testsuite name="other" tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>'):
            with self.subTest(content=content):
                self.assertTrue(self.check_report(content))


if __name__ == "__main__":
    unittest.main()
