"""Require the marketplace database/security regressions to run, not silently skip."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED_SUITES = (
    "com.berkayb.soundconnect.modules.marketplace.MarketplacePostgresTest",
    "com.berkayb.soundconnect.modules.marketplace.MarketplaceHttpSecurityPostgresTest",
    "com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaQuotaPostgresTest",
    "com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaTransactionPostgresTest",
    "com.berkayb.soundconnect.modules.media.image.ProtectedImageLifecyclePostgresTest",
    "com.berkayb.soundconnectworker.MediaWorkerDatabaseRolePostgresTest",
)


def verify_reports(directory: Path, required=REQUIRED_SUITES) -> list[str]:
    failures = []
    for suite in required:
        path = directory / f"TEST-{suite}.xml"
        if not path.is_file():
            failures.append(f"Missing required test report: {suite}")
            continue
        try:
            report = ET.parse(path).getroot()
            counts = {key: int(report.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            cases = report.findall("testcase")
            if report.tag != "testsuite" or report.get("name") != suite:
                failures.append(f"Unexpected test report identity: {suite}")
            if counts["tests"] <= 0 or len(cases) != counts["tests"]:
                failures.append(f"Required suite did not execute its tests: {suite}")
            if any(counts[key] for key in ("failures", "errors", "skipped")) or any(
                case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")
            ):
                failures.append(f"Required suite has failed or skipped tests: {suite}")
        except (ET.ParseError, ValueError, KeyError, OSError):
            failures.append(f"Unreadable or incomplete required test report: {suite}")
    return failures


def main() -> int:
    directory = Path(sys.argv[1]) if len(sys.argv) == 2 else Path("build/test-results/test")
    failures = verify_reports(directory)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Verified {len(REQUIRED_SUITES)} required marketplace/PostgreSQL suites: no missing, failed or skipped tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
