from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from verify_band_test_reports import inventory, verify_events
from verify_marketplace_test_reports import verify_reports


class BandGateTest(unittest.TestCase):
    GOOD = 'SC_BAND_START band#guard()\nSC_BAND_PASS band#guard()\nSC_BAND_FINAL 1 1 0 0\n'

    def test_required_source_inventory_is_nonempty(self):
        self.assertEqual(3, len(inventory()))
        self.assertTrue(all(inventory().values()))

    def test_actual_lifecycle_success(self):
        verify_events(self.GOOD, {'band': ['guard()']})

    def test_missing_duplicate_start_pass_final_and_crash_fail(self):
        for text in ('', self.GOOD.replace('SC_BAND_START', 'absent'),
                     self.GOOD.replace('SC_BAND_PASS', 'SC_BAND_FAIL'),
                     self.GOOD.replace('SC_BAND_FINAL', 'absent'),
                     self.GOOD + 'SC_BAND_PASS band#guard()\n',
                     self.GOOD + 'SC_BAND_FINAL 1 1 0 0\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                verify_events(text, {'band': ['guard()']})

    def test_required_method_cannot_disappear(self):
        with self.assertRaises(ValueError):
            verify_events(self.GOOD, {'band': ['guard()', 'startup()']})

    def test_missing_empty_skipped_failed_and_duplicate_reports_fail(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / 'TEST-band.xml'
            cases = {'band': ['guard()']}
            self.assertTrue(verify_reports(Path(directory), ('band',), cases))
            for body in ('', '<testcase classname="band" name="guard()"><skipped/></testcase>',
                         '<testcase classname="band" name="guard()"><failure/></testcase>',
                         '<testcase classname="band" name="guard()"/>' * 2):
                path.write_text('<testsuite name="band" tests="1" failures="0" errors="0" skipped="0">' + body + '</testsuite>', encoding='utf-8')
                self.assertTrue(verify_reports(Path(directory), ('band',), cases))


if __name__ == '__main__':
    unittest.main()
