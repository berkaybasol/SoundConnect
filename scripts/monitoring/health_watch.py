#!/usr/bin/env python3
"""Independent, passive health observer. No application queue or SMTP worker.

Run on a separate host for host-outage coverage. An owned local instance only
proves API/process outage coverage. Secrets and destinations come from process
environment, never command-line arguments or the persisted observation state.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

MAX_RESPONSE = 64 * 1024
USER_AGENT = "SoundConnect-Health-Monitor/1.0"
STATES = {"UP", "DEGRADED", "DOWN", "UNKNOWN", "DISABLED", "STALE"}
SAFE_ID = re.compile(r"^[a-zA-Z][a-zA-Z0-9]{0,63}$")
FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")
STATE_SCHEMA = 2
STATE_FIELDS = {"schema", "binding", "candidate", "consecutive", "observedAt", "notified", "incident"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the administrator credential to a redirect.


def safe_url(value: str, allow_loopback: bool = False) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("URL credentials, query and fragment are forbidden")
    local = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
    if not parsed.hostname or (parsed.scheme != "https" and not (
            allow_loopback and local and parsed.scheme == "http")):
        raise ValueError("HTTPS required (explicit loopback testing is supported)")
    return value


def read_json(request: urllib.request.Request, *, timeout: float = 8):
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(request, timeout=timeout) as response:
        content = response.read(MAX_RESPONSE + 1)
        if len(content) > MAX_RESPONSE:
            raise ValueError("Response exceeds limit")
        return json.loads(content)


def no_traffic_metrics(metrics, stale_after: int) -> bool:
    """The API probe measured an empty interval; that is not a latency/error sample."""
    if not isinstance(metrics, dict) or set(metrics) != {"requestCount", "windowSeconds"}:
        return False
    count, window = metrics["requestCount"], metrics["windowSeconds"]
    return (type(count) in {int, float} and math.isfinite(count) and count == 0
            and type(window) in {int, float} and math.isfinite(window) and 0 < window <= stale_after)


def classify(raw, now: float):
    """Return only non-sensitive state codes; never retain the server payload."""
    data = raw.get("data") if isinstance(raw, dict) and raw.get("success") is True and type(raw.get("code")) is int and raw.get("code") == 200 else None
    if not isinstance(data, dict):
        return "MONITORING_LOST", ["invalidSnapshot"]
    try:
        generated = datetime.fromisoformat(data["generatedAt"].replace("Z", "+00:00"))
        if generated.tzinfo is None:
            raise ValueError("Timezone missing")
        skew = now - generated.timestamp()
        stale_after = data["staleAfterSeconds"]
        if type(stale_after) is not int or not 5 <= stale_after <= 3600:
            raise ValueError("Invalid freshness bound")
        if skew < -30 or skew > stale_after:
            return "MONITORING_LOST", ["staleSnapshot"]
        components = data["components"]
        if not isinstance(components, list) or not 1 <= len(components) <= 32:
            raise ValueError("Invalid component count")
        reasons = []
        seen = set()
        down = False
        benign_idle = False
        for item in components:
            identity, status = item["id"], item["status"]
            if not isinstance(identity, str) or not SAFE_ID.fullmatch(identity) or identity in seen:
                raise ValueError("Invalid component")
            seen.add(identity)
            if status not in STATES:
                raise ValueError("Invalid status")
            age = item.get("ageSeconds")
            idle_candidate = identity == "api" and status == "UNKNOWN" and item.get("reasonCode") == "NO_TRAFFIC"
            measurement_fresh = False
            if status not in {"UNKNOWN", "DISABLED"} or idle_candidate:
                if type(age) is not int or age < 0:
                    status = "UNKNOWN"
                else:
                    try:
                        measured = datetime.fromisoformat(item["measuredAt"].replace("Z", "+00:00"))
                        if measured.tzinfo is None:
                            raise ValueError("Timezone missing")
                        elapsed = generated.timestamp() - measured.timestamp()
                        if elapsed < 0 or abs(elapsed - age) >= 1:
                            raise ValueError("Inconsistent measurement age")
                        if status == "UP" and item.get("reasonCode") != "HEALTHY":
                            raise ValueError("Healthy measurement reason missing")
                        if max(age + max(0, skew), now - measured.timestamp()) > stale_after:
                            status = "STALE"
                        else:
                            measurement_fresh = True
                    except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
                        status = "UNKNOWN"
            if idle_candidate and measurement_fresh and no_traffic_metrics(item.get("metrics"), stale_after):
                benign_idle = True
                continue
            if status in {"UP", "DISABLED"}:
                continue
            reasons.append(identity + ":" + status)
            down = down or status == "DOWN"
        overall = data.get("status")
        if overall not in STATES:
            raise ValueError("Invalid overall status")
        if not reasons and overall not in {"UP", "DISABLED"} and not (benign_idle and overall == "UNKNOWN"):
            reasons.append("overall:" + overall)
            down = overall == "DOWN"
        if not reasons:
            return "UP", []
        return "DOWN" if down else "DEGRADED", sorted(reasons)
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
        return "MONITORING_LOST", ["invalidSnapshot"]


def observe(url: str, token: str, now: float):
    try:
        request = urllib.request.Request(url, headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/json", "Cache-Control": "no-cache",
            "User-Agent": USER_AGENT,
        })
        return classify(read_json(request), now)
    except urllib.error.HTTPError as error:
        if error.code in {401, 403}:
            return "MONITORING_LOST", ["monitorCredentialRejected"]
        return "DOWN", ["healthHttpFailure"]
    except (OSError, ValueError, TimeoutError):
        return "DOWN", ["healthUnreachable"]


def transition(state: dict, status: str, reasons: list[str], now: float,
               failure_samples: int = 3, recovery_samples: int = 2,
               max_sample_gap_seconds: float = 600):
    fingerprint = hashlib.sha256(json.dumps([status, reasons]).encode()).hexdigest()
    same = state.get("candidate") == fingerprint
    previous = state.get("observedAt")
    if previous is None or now < previous or now - previous > max_sample_gap_seconds:
        same = False  # A restart gap or clock rollback cannot stand in for consecutive observations.
    state["candidate"] = fingerprint
    state["consecutive"] = min(100, state.get("consecutive", 0) + 1) if same else 1
    state["observedAt"] = now
    needed = recovery_samples if status == "UP" else failure_samples
    if state["consecutive"] < needed or state.get("notified") == fingerprint:
        return None
    if status == "UP" and state.get("incident") is not True:
        state["notified"] = fingerprint  # The first normal baseline is silent.
        return None
    return {"schema": 1, "kind": "RECOVERED" if status == "UP" else "FIRING",
            "status": status, "components": reasons,
            "observedAt": datetime.fromtimestamp(now, timezone.utc).isoformat(),
            "fingerprint": fingerprint}


def acknowledge(state: dict, event: dict):
    state["notified"] = event["fingerprint"]
    state["incident"] = event["kind"] != "RECOVERED"


def mail_addresses(env: dict):
    sender = env["MONITOR_MAIL_FROM"]
    recipients = [part.strip() for part in env["MONITOR_MAIL_TO"].split(",")]
    if not 1 <= len(recipients) <= 2 or len(set(recipients)) != len(recipients):
        raise ValueError("One or two distinct alarm recipients are required")
    for address in (sender, *recipients):
        if len(address) > 254 or any(c.isspace() for c in address) or address.count("@") != 1 or any(not part for part in address.split("@")):
            raise ValueError("Invalid alarm address")
    return sender, recipients


def state_binding(url: str, env: dict) -> str:
    """Credential rotation retains dedup; a different target/destination/test mode cannot."""
    sender, recipients = mail_addresses(env)
    target = urllib.parse.urlsplit(url)
    canonical = [target.scheme.lower(), target.hostname.lower(), target.port or (443 if target.scheme == "https" else 80), target.path or "/"]
    value = [canonical, sender, sorted(recipients), env.get("SOUNDCONNECT_MONITOR_TEST") == "true"]
    return hashlib.sha256(json.dumps(value, separators=(",", ":")).encode()).hexdigest()


def send_mail(event: dict, env: dict) -> str | None:
    # Receiver selection and real test delivery need explicit user authorization.
    # All fields sent are fixed operational status codes, never service payloads.
    sender, recipients = mail_addresses(env)
    payload = {
        "from": {"email": sender, "name": "SoundConnect Sağlık"},
        "to": [{"email": recipient} for recipient in recipients],
        "subject": ("[TEST] " if env.get("SOUNDCONNECT_MONITOR_TEST") == "true" else "") +
                   "SoundConnect - " + event["kind"] + " / " + event["status"],
        "text": "Sistem ölçümü: " + event["status"] + "\n" +
                "\n".join(event["components"]) + "\n" + event["observedAt"] +
                "\nReferans: " + event["fingerprint"][:16],
    }
    request = urllib.request.Request("https://api.mailersend.com/v1/email",
        data=json.dumps(payload).encode(), method="POST", headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + env["MONITOR_MAIL_API_KEY"],
            "User-Agent": USER_AGENT,
        })
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(request, timeout=8) as response:
        if response.status != 202:
            raise ValueError("Alarm provider did not accept the request")
        message_id = response.headers.get("x-message-id")
        # Receipt identity is only returned to an explicitly authorized acceptance caller.
        # It never enters monitor logs or durable state; malformed/missing IDs do not resend.
        return message_id if isinstance(message_id, str) and re.fullmatch(r"[a-zA-Z0-9_-]{1,256}", message_id) else None
    # Provider acceptance is NOT mailbox/user receipt; verify receipt separately.


def save_state(path: Path, state: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".health-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(state, handle, allow_nan=False)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_state(path: Path, binding: str):
    if not isinstance(binding, str) or not FINGERPRINT.fullmatch(binding):
        raise ValueError("Invalid monitor binding")
    if not path.exists():
        return {"schema": STATE_SCHEMA, "binding": binding, "candidate": None, "consecutive": 0,
                "observedAt": None, "notified": None, "incident": False}
    if path.stat().st_size > 4096:
        raise ValueError("Invalid monitor state")
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Invalid monitor state")
            result[key] = value
        return result
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)
    except RecursionError as invalid:
        raise ValueError("Invalid monitor state") from invalid
    if (not isinstance(value, dict) or set(value) != STATE_FIELDS or type(value["schema"]) is not int
            or value["schema"] != STATE_SCHEMA or value["binding"] != binding
            or type(value["consecutive"]) is not int or not 0 <= value["consecutive"] <= 100
            or type(value["incident"]) is not bool):
        raise ValueError("Invalid monitor state")
    for key in ("candidate", "notified"):
        if value[key] is not None and (not isinstance(value[key], str) or not FINGERPRINT.fullmatch(value[key])):
            raise ValueError("Invalid monitor state")
    observed = value["observedAt"]
    if observed is not None and (type(observed) not in {int, float} or not math.isfinite(observed) or observed < 0):
        raise ValueError("Invalid monitor state")
    if ((value["candidate"] is None) != (value["consecutive"] == 0)
            or (value["candidate"] is None) != (observed is None)
            or (value["incident"] and value["notified"] is None)):
        raise ValueError("Invalid monitor state")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loopback-test", action="store_true")
    parser.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    try:
        url = safe_url(os.environ["SOUNDCONNECT_MONITOR_URL"], args.loopback_test)
        token = os.environ["SOUNDCONNECT_MONITOR_TOKEN"]
        if not token or len(token) > 8192 or any(c.isspace() for c in token):
            raise ValueError("Invalid monitor credential")
        interval = int(os.environ.get("SOUNDCONNECT_MONITOR_INTERVAL_SECONDS", "30"))
        if not 15 <= interval <= 300:
            raise ValueError("Invalid monitor interval")
        for key in ("MONITOR_MAIL_FROM", "MONITOR_MAIL_TO", "MONITOR_MAIL_API_KEY"):
            if not os.environ.get(key):
                raise ValueError("Alarm destination is not configured")
        state = load_state(args.state, state_binding(url, os.environ))
    except (OSError, ValueError, KeyError):
        print('{"status":"CONFIGURATION_REQUIRED"}')
        return 2
    while True:
        now = time.time()
        status, reasons = observe(url, token, now)
        event = transition(state, status, reasons, now, max_sample_gap_seconds=2 * interval)
        delivery = "NOT_REQUIRED"
        if event is not None:
            try:
                send_mail(event, os.environ)
                acknowledge(state, event)
                delivery = "PROVIDER_ACCEPTED"
            except (OSError, ValueError, KeyError):
                delivery = "FAILED"  # Unacknowledged event retries on next sample.
        try:
            save_state(args.state, state)
        except OSError:
            print('{"status":"STATE_PERSISTENCE_FAILED"}')
            return 3
        print(json.dumps({"status": status, "delivery": delivery}), flush=True)
        if args.once:
            return 1 if delivery == "FAILED" else 0
        time.sleep(interval)


if __name__ == "__main__":
    raise SystemExit(main())
