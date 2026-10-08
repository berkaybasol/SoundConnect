from datetime import datetime, timezone
import tempfile
import json
from pathlib import Path
import unittest
from unittest.mock import patch, MagicMock
import urllib.error

import health_watch as monitor

NOW = 1791453600.0
ENV = {"MONITOR_MAIL_FROM": "sender@example.test", "MONITOR_MAIL_TO": "first@example.test,second@example.test",
       "MONITOR_MAIL_API_KEY": "private-key", "SOUNDCONNECT_MONITOR_TEST": "true"}
BINDING = monitor.state_binding("https://example.test/health", ENV)


def snapshot(status="UP", age=0):
    return {"success": True, "code": 200, "data": {"status": status,
        "generatedAt": datetime.fromtimestamp(NOW, timezone.utc).isoformat(),
        "staleAfterSeconds": 60,
        "components": [{"id": "database", "status": status, "ageSeconds": age,
                        "measuredAt": datetime.fromtimestamp(NOW - (age or 0), timezone.utc).isoformat(),
                        "reasonCode": "HEALTHY" if status == "UP" else "DEPENDENCY_UNAVAILABLE"}]}}


def idle_snapshot():
    raw = snapshot()
    raw["data"]["status"] = "UNKNOWN"
    raw["data"]["components"].insert(0, {"id": "api", "status": "UNKNOWN", "ageSeconds": 0,
        "measuredAt": raw["data"]["generatedAt"], "reasonCode": "NO_TRAFFIC",
        "metrics": {"requestCount": 0.0, "windowSeconds": 15.0}})
    return raw


class HealthWatchTest(unittest.TestCase):
    def test_valid_idle_traffic_idle_never_fires_or_recovers_an_incident(self):
        state = {}
        for step, raw in enumerate([idle_snapshot() for _ in range(4)] +
                                   [snapshot() for _ in range(4)] +
                                   [idle_snapshot() for _ in range(4)]):
            status, reasons = monitor.classify(raw, NOW)
            self.assertEqual((status, reasons), ("UP", []))
            self.assertIsNone(monitor.transition(state, status, reasons, NOW + step * 30))
        self.assertIsNot(state.get("incident"), True)

    def test_idle_api_never_masks_unknown_stale_or_failed_other_components(self):
        for status in ("UNKNOWN", "STALE", "DOWN", "DEGRADED"):
            with self.subTest(status=status):
                raw = idle_snapshot()
                raw["data"]["status"] = status
                raw["data"]["components"][1]["status"] = status
                observed, reasons = monitor.classify(raw, NOW)
                self.assertEqual(observed, "DOWN" if status == "DOWN" else "DEGRADED")
                self.assertEqual(reasons, ["database:" + status])
                state = {}
                for step in range(3):
                    event = monitor.transition(state, observed, reasons, NOW + step * 30)
                self.assertEqual(event["kind"], "FIRING")
        raw = idle_snapshot()
        raw["data"]["status"] = "DOWN"
        self.assertEqual(monitor.classify(raw, NOW), ("DOWN", ["overall:DOWN"]))

    def test_no_traffic_exception_requires_exact_api_reason_fresh_metadata_and_zero_count(self):
        for change in ({"id": "redis"}, {"reasonCode": "WARMING_UP"}, {"reasonCode": "PROBE_FAILED"},
                       {"measuredAt": None}, {"ageSeconds": None}, {"ageSeconds": True},
                       {"measuredAt": datetime.fromtimestamp(NOW - 61, timezone.utc).isoformat(), "ageSeconds": 61},
                       {"measuredAt": datetime.fromtimestamp(NOW + 1, timezone.utc).isoformat()},
                       {"metrics": {}}, {"metrics": {"requestCount": True, "windowSeconds": 15}},
                       {"metrics": {"requestCount": 1, "windowSeconds": 15}},
                       {"metrics": {"requestCount": 0, "windowSeconds": 0}},
                       {"metrics": {"requestCount": 0, "windowSeconds": 61}},
                       {"metrics": {"requestCount": 0, "windowSeconds": float("nan")}}):
            with self.subTest(change=change):
                raw = idle_snapshot()
                raw["data"]["components"][0].update(change)
                self.assertNotEqual(monitor.classify(raw, NOW)[0], "UP")

    def test_baseline_silent_and_incident_deduplicated_across_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            state = monitor.load_state(Path(directory) / "state.json", BINDING)
        for step in range(4):
            self.assertIsNone(monitor.transition(state, "UP", [], NOW + step))
        self.assertIsNone(monitor.transition(state, "DOWN", ["database:DOWN"], NOW + 5))
        self.assertIsNone(monitor.transition(state, "DOWN", ["database:DOWN"], NOW + 6))
        event = monitor.transition(state, "DOWN", ["database:DOWN"], NOW + 7)
        self.assertEqual(event["kind"], "FIRING")
        monitor.acknowledge(state, event)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            monitor.save_state(path, state)
            state = monitor.load_state(path, BINDING)
        self.assertIsNone(monitor.transition(state, "DOWN", ["database:DOWN"], NOW + 8))
        self.assertIsNone(monitor.transition(state, "UP", [], NOW + 9))
        recovery = monitor.transition(state, "UP", [], NOW + 10)
        self.assertEqual(recovery["kind"], "RECOVERED")
        monitor.acknowledge(state, recovery)
        self.assertIsNone(monitor.transition(state, "UP", [], NOW + 11))

    def test_failed_send_remains_pending(self):
        state = {}
        for i in range(3):
            event = monitor.transition(state, "DOWN", ["healthUnreachable"], NOW + i)
        self.assertIsNotNone(event)
        self.assertIsNotNone(monitor.transition(state, "DOWN", ["healthUnreachable"], NOW + 4))

    def test_restart_gap_or_clock_rollback_requires_new_consecutive_samples(self):
        for resumed in (NOW + 601, NOW - 1):
            state = {}
            self.assertIsNone(monitor.transition(state, "DOWN", ["healthUnreachable"], NOW))
            self.assertIsNone(monitor.transition(state, "DOWN", ["healthUnreachable"], NOW + 1))
            self.assertIsNone(monitor.transition(state, "DOWN", ["healthUnreachable"], resumed,
                                                 max_sample_gap_seconds=60))
            self.assertEqual(state["consecutive"], 1)
            self.assertIsNone(monitor.transition(state, "DOWN", ["healthUnreachable"], resumed + 1))
            self.assertIsNotNone(monitor.transition(state, "DOWN", ["healthUnreachable"], resumed + 2))

    def test_partial_failure_and_measurement_loss_not_http_success(self):
        raw = snapshot()
        raw["data"]["components"][0]["status"] = "DEGRADED"
        self.assertEqual(monitor.classify(raw, NOW)[0], "DEGRADED")
        self.assertEqual(monitor.classify(snapshot(age=61), NOW)[1], ["database:STALE"])
        self.assertEqual(monitor.classify(snapshot(), NOW + 61)[0], "MONITORING_LOST")
        self.assertEqual(monitor.classify(snapshot(), NOW - 31)[0], "MONITORING_LOST")

    def test_unknown_disabled_invalid_not_healthy(self):
        self.assertEqual(monitor.classify(snapshot("UNKNOWN", None), NOW)[0], "DEGRADED")
        self.assertEqual(monitor.classify(snapshot("DISABLED", None), NOW), ("UP", []))
        for value in ({}, {"data": {}}, snapshot("INVENTED")):
            self.assertEqual(monitor.classify(value, NOW)[0], "MONITORING_LOST")
        value = snapshot()
        value["data"]["components"] *= 2
        self.assertEqual(monitor.classify(value, NOW)[0], "MONITORING_LOST")

    def test_payload_content_never_enters_event(self):
        value = snapshot("DOWN")
        value["data"]["components"][0].update({"error": "secret-token", "label": "private@example.com"})
        status, reasons = monitor.classify(value, NOW)
        self.assertEqual(reasons, ["database:DOWN"])
        state = {}
        for i in range(3):
            event = monitor.transition(state, status, reasons, NOW + i)
        self.assertNotIn("secret-token", str(event) + str(state))
        self.assertNotIn("private@example.com", str(event) + str(state))

    def test_authentication_loss_distinct_from_outage(self):
        for code in (401, 403):
            with patch.object(monitor, "read_json", side_effect=urllib.error.HTTPError("url", code, "secret", {}, None)):
                self.assertEqual(monitor.observe("https://example.test/health", "token", NOW)[0], "MONITORING_LOST")
        with patch.object(monitor, "read_json", side_effect=OSError("private host")):
            self.assertEqual(monitor.observe("https://example.test/health", "token", NOW), ("DOWN", ["healthUnreachable"]))
        with patch.object(monitor, "read_json", return_value=snapshot()) as reader:
            self.assertEqual(monitor.observe("https://example.test/health", "token", NOW), ("UP", []))
            self.assertEqual(reader.call_args.args[0].get_header("User-agent"), "SoundConnect-Health-Monitor/1.0")

    def test_tls_redirect_and_credential_boundaries(self):
        for url in ("http://remote.test", "https://user:secret@host.test", "https://host.test?a=secret", "https://host.test/#token"):
            with self.assertRaises(ValueError):
                monitor.safe_url(url)
        self.assertEqual(monitor.safe_url("http://127.0.0.1:8080/health", True), "http://127.0.0.1:8080/health")
        self.assertIsNone(monitor.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.test"))

    def test_healthy_requires_matching_timestamp_age_and_reason(self):
        for change in ({"measuredAt": None}, {"measuredAt": "not-a-date"}, {"measuredAt": "2026-10-08T00:00:00"},
                       {"reasonCode": None}, {"ageSeconds": True}, {"ageSeconds": -1},
                       {"measuredAt": datetime.fromtimestamp(NOW - 120, timezone.utc).isoformat()},
                       {"measuredAt": datetime.fromtimestamp(NOW + 1, timezone.utc).isoformat()}):
            with self.subTest(change=change):
                raw = snapshot()
                raw["data"]["components"][0].update(change)
                self.assertEqual(monitor.classify(raw, NOW), ("DEGRADED", ["database:UNKNOWN"]))
        raw = snapshot(age=59)
        self.assertEqual(monitor.classify(raw, NOW + 2), ("DEGRADED", ["database:STALE"]))
        for field in ("success", "code"):
            raw = snapshot()
            del raw[field]
            self.assertEqual(monitor.classify(raw, NOW)[0], "MONITORING_LOST")

    def test_binding_changes_for_target_recipients_and_test_mode_but_not_credentials(self):
        same = dict(ENV, MONITOR_MAIL_API_KEY="rotated", SOUNDCONNECT_MONITOR_TOKEN="new-token",
                    MONITOR_MAIL_TO="second@example.test, first@example.test")
        self.assertEqual(BINDING, monitor.state_binding("https://EXAMPLE.test:443/health", same))
        for url, env in (("https://other.test/health", ENV), ("https://example.test/different", ENV),
                         ("https://example.test/health", dict(ENV, MONITOR_MAIL_TO="other@example.test")),
                         ("https://example.test/health", dict(ENV, SOUNDCONNECT_MONITOR_TEST="false"))):
            self.assertNotEqual(BINDING, monitor.state_binding(url, env))
        self.assertNotIn("example", BINDING)

    def test_state_strict_schema_and_target_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            valid = monitor.load_state(path, BINDING)
            monitor.save_state(path, valid)
            self.assertEqual(valid, monitor.load_state(path, BINDING))
            with self.assertRaises(ValueError):
                monitor.load_state(path, "a" * 64)
            for change in ({"schema": 1}, {"schema": True}, {"consecutive": -1}, {"consecutive": True},
                           {"consecutive": 101}, {"consecutive": 1}, {"candidate": "secret-account"},
                           {"notified": "secret-token"}, {"incident": "false"}, {"incident": True},
                           {"observedAt": float("nan")}, {"observedAt": float("inf")},
                           {"observedAt": -1}, {"unexpected": "secret"}):
                with self.subTest(change=change):
                    path.write_text(json.dumps(dict(valid, **change)), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        monitor.load_state(path, BINDING)
            for content in ("{}", "null", '{"schema":2,"schema":2}', " " * 4097):
                path.write_text(content, encoding="utf-8")
                with self.assertRaises(ValueError):
                    monitor.load_state(path, BINDING)

    def test_provider_receipt_is_returned_only_for_accepted_safe_header(self):
        event = {"kind": "FIRING", "status": "DOWN", "components": ["database:DOWN"],
                 "observedAt": "2026-10-08T09:00:00+00:00", "fingerprint": "a" * 64}
        for header, expected in (("message-123", "message-123"), (None, None), ("private\nvalue", None), ("x" * 257, None)):
            response = MagicMock(status=202, headers={"x-message-id": header})
            response.__enter__.return_value = response
            with patch.object(monitor.urllib.request, "build_opener") as factory:
                factory.return_value.open.return_value = response
                self.assertEqual(monitor.send_mail(event, ENV), expected)
                sent = factory.return_value.open.call_args.args[0]
                self.assertEqual(sent.get_header("User-agent"), "SoundConnect-Health-Monitor/1.0")
                payload = json.loads(sent.data)
                self.assertEqual(len(payload["to"]), 2)
                self.assertTrue(payload["subject"].startswith("[TEST] "))
                self.assertNotIn("private-key", sent.data.decode())
        response = MagicMock(status=200)
        response.__enter__.return_value = response
        with patch.object(monitor.urllib.request, "build_opener") as factory:
            factory.return_value.open.return_value = response
            with self.assertRaises(ValueError):
                monitor.send_mail(event, ENV)


if __name__ == "__main__":
    unittest.main()
