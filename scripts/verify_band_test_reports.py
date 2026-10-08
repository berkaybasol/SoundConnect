"""Require every concrete BAND schema/startup test, using the existing XML verifier."""
from pathlib import Path
import re
import sys
from verify_marketplace_test_reports import verify_reports

ROOT = Path(__file__).resolve().parents[1]
SUITES = (
    "com.berkayb.soundconnect.modules.notification.service.BandNotificationIdentityMigrationPostgresTest",
    "com.berkayb.soundconnect.modules.notification.service.BandNotificationIdentityPostgresTest",
    "com.berkayb.soundconnect.modules.notification.service.BandNotificationStartupHttpPostgresTest",
)


def inventory(events=False):
    result = {}
    for suite in SUITES:
        source = ROOT / "src/test/java" / (suite.replace(".", "/") + ".java")
        text = source.read_text(encoding="utf-8")
        if re.search(r"@Disabled|disabledWithoutDocker\s*=\s*true", text):
            raise ValueError(f"Required BAND suite may not skip: {suite}")
        names = [name + "()" for name in re.findall(r"@Test\s+void\s+(\w+)\s*\(", text)]
        parameters = re.findall(r"@(?:org.junit.jupiter.params.)?ParameterizedTest\s+"
                               r"@(?:org.junit.jupiter.params.provider.)?ValueSource\(strings=\{([^}]+)\}\)\s+"
                               r"void\s+(\w+)\(String\s+(\w+)\)", text)
        if text.count("ParameterizedTest") != len(parameters):
            raise ValueError(f"Unreviewed parameterized inventory: {suite}")
        for values, method, argument in parameters:
            for index, value in enumerate(re.findall(r'"([^"\\]+)"', values), 1):
                names.append(f"{method}(String)[{index}]" if events else f"[{index}] {argument}={value}")
        if not names or len(names) != len(set(names)):
            raise ValueError(f"Invalid source inventory: {suite}")
        result[suite] = names
    return result


def verify_events(events, cases):
    expected = {f"{suite}#{method}" for suite, methods in cases.items() for method in methods}
    for event in ("START", "PASS"):
        observed = re.findall(r"^SC_BAND_" + event + r" (.+)$", events, re.M)
        selected = [value for value in observed if value.split("#")[0] in cases]
        if len(selected) != len(expected) or set(selected) != expected:
            raise ValueError(f"Missing/duplicate required BAND {event}")
    final = re.findall(r"^SC_BAND_FINAL (\d+) (\d+) (\d+) (\d+)$", events, re.M)
    # The normal backend task can contain an existing unrelated disabled test;
    # required BAND tests themselves must match the exact all-PASS inventory above.
    if len(final) != 1:
        raise ValueError("Missing/failed final backend suite")
    total, passed, failed, skipped = map(int, final[0])
    if failed or passed < len(expected) or total != passed + failed + skipped or "SC_BAND_FAIL " in events:
        raise ValueError("Missing/failed final backend suite")


def main():
    cases = inventory()
    failures = verify_reports(Path(sys.argv[1]), SUITES, cases)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    verify_events(Path(sys.argv[2]).read_text(encoding="utf-8-sig"), inventory(events=True))
    print(f"Verified {sum(map(len, cases.values()))} unique BAND tests; no missing, failed or skipped cases.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
