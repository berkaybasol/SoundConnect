"""Populate only the existing local API with resumable, explicitly synthetic listings.

Account creation is a separate guarded local-only CLI. This tool exercises the
normal authenticated marketplace and private S3 upload/verification endpoints.
Credentials, bearer tokens and signed URLs are never written to its progress log.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "http://127.0.0.1:8080"
MARKET = "/api/v1/user/marketplace"
NAMESPACE = uuid.UUID("0b33a4bc-74c5-4e4d-9475-8d9709c6eb73")
PRICES = {
    "GUITARS": 1850000, "KEYBOARDS": 2400000, "DRUMS": 2200000,
    "WIND": 900000, "STRINGS": 1250000,
    "TRADITIONAL": 1050000, "MICROPHONES": 480000, "RECORDING": 1450000,
    "LIVE_SOUND": 2700000, "DJ": 2100000, "AMPS_EFFECTS": 1400000,
    "ACCESSORIES": 165000, "LIGHTING_STAGE": 950000,
}
FIELDS = ("title", "description", "categoryId", "brand", "model", "condition",
          "priceMinor", "districtId", "negotiable", "deliveryMethod")
TERMINAL_MEDIA = {"FAILED", "CLEANUP_PENDING", "DELETION_PENDING"}


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".writing")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temp, path)


class ApiFailure(RuntimeError):
    def __init__(self, method, path, status, code):
        # No response bodies/headers: media responses may contain signed URLs.
        super().__init__(f"{method} {path}: HTTP {status}, application code {code}")
        self.status = status
        self.code = code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(NoRedirect)


def api(method, path, body=None, token=None, accepted=(200, 201)):
    if not path.startswith(("/api/", "/actuator/")) or "://" in path:
        raise ValueError("Only local API paths are allowed")
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    for attempt in range(6):
        request = urllib.request.Request(API + path, data=data, headers=headers, method=method)
        try:
            with HTTP.open(request, timeout=45) as response:
                status, raw = response.status, response.read(4_000_000)
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read(20000)
            if status == 429 and attempt < 5:
                delay = error.headers.get("Retry-After", "15")
                delay = min(60, max(5, int(delay))) if delay.isdigit() else 15
                print(f"Rate limit: waiting {delay}s for {path.split('?')[0]}", flush=True)
                time.sleep(delay)
                continue
        except (OSError, urllib.error.URLError):
            raise RuntimeError(f"Local API transport interrupted: {method} {path}; rerun to recover") from None
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise RuntimeError(f"Unexpected local response: HTTP {status}") from None
        if status not in accepted or payload.get("success") is False:
            raise ApiFailure(method, path, status, payload.get("code"))
        return payload.get("data", payload)
    raise RuntimeError("Local API rate limit did not recover")


def login(account, password):
    result = api("POST", "/api/v1/auth/login", {"username": account["username"], "password": password})
    if result.get("status") != "ACTIVE" or not result.get("token"):
        raise RuntimeError(f"Demo account is not active: {account['username']}")
    expected = account["role"]
    expected = expected if expected.startswith("ROLE_") else "ROLE_" + expected
    if expected not in result.get("roles", []):
        raise RuntimeError(f"Unexpected role: {account['username']}")
    if str(result.get("userId")) != account["userId"]:
        raise RuntimeError("Demo account identity mismatch")
    return result["token"]


def category_map(roots):
    result = {}
    for root in roots:
        for child in root["children"]:
            result[child["code"]] = {**child, "rootCode": root["code"], "rootName": root["name"]}
    return result


def make_plan(accounts, assets, categories):
    if len(accounts) != 25 or len({a["username"] for a in accounts}) != 25:
        raise ValueError("Expected exactly 25 distinct demo accounts")
    if len(assets) < 25:
        raise ValueError("At least 25 distinct equipment photographs are required")
    if len({a["key"] for a in assets}) != len(assets):
        raise ValueError("Duplicate equipment keys")
    plan = []
    for index in range(50):
        account = accounts[index // 2]
        asset = assets[index % len(assets)]
        if not account["username"].startswith("demo_"):
            raise ValueError("Refusing a non-demo account")
        image_path = Path(asset["imagePath"])
        if not image_path.is_file() or not 1000 <= image_path.stat().st_size <= 20_000_000:
            raise ValueError(f"Invalid local image for {asset['key']}")
        category = categories[asset["categoryCode"]]
        condition = "NEW" if index % 5 == 0 else "USED"
        condition_text = "Sıfır ürün" if condition == "NEW" else "İkinci el"
        delivery = ("PICKUP", "BOTH", "SHIPPING")[index % 3]
        delivery_text = {"PICKUP": "Elden teslim", "BOTH": "Elden teslim veya kargo", "SHIPPING": "Kargo"}[delivery]
        key = f"{account['username']}/{index % 2 + 1}"
        title = asset["titleTr"]
        price = asset.get("priceMinor", PRICES[category["rootCode"]])
        if type(price) is not int or not 1 <= price <= 100_000_000_000:
            raise ValueError("Invalid demo price")
        if index >= len(assets):
            price = max(5000, price // 100 * (90 + index % 16))
        if not 1 <= price <= 100_000_000_000:
            raise ValueError("Adjusted demo price exceeds API limits")
        attribution = asset.get("attributionText")
        if not isinstance(attribution, str) or not attribution.strip():
            raise ValueError("Reviewed image attribution is required")
        description = (
            f"{title}\n\n{condition_text} ekipman ilanı. Kategori: {category['name']}. "
            f"{account['district']}, {account['city']} konumunda {delivery_text.lower()} seçeneği. "
            + ("Fiyat için görüşülebilir. " if index % 3 else "Belirtilen fiyat sabittir. ")
            + "Ürün bilgisi ve teslim ayrıntıları için ilan sahibine uygulama içinden yazabilirsin.\n\n"
            + "DEMO İLAN: SoundConnect geliştirme ortamını denemek için oluşturulmuştur; gerçek satış değildir. "
            + "Görsel, başlıktaki ekipman türünü gösteren lisanslı temsili fotoğraftır.\n"
            + attribution.strip()
        )
        if not 5 <= len(title.strip()) <= 120 or not 10 <= len(description.strip()) <= 4000:
            raise ValueError("Listing text exceeds API limits")
        for field, maximum in (("brand", 80), ("model", 100)):
            if asset.get(field) is not None and (not isinstance(asset[field], str) or len(asset[field].strip()) > maximum):
                raise ValueError("Invalid demo brand/model")
        plan.append({
            "key": key, "username": account["username"], "role": account["role"],
            "userId": account["userId"], "clientRequestId": str(uuid.uuid5(NAMESPACE, key)),
            "assetKey": asset["key"], "imagePath": str(image_path.resolve()),
            "imageSha256": hashlib.sha256(image_path.read_bytes()).hexdigest(),
            "title": title, "description": description, "categoryId": category["id"],
            "categoryCode": category["code"], "categoryRoot": category["rootCode"],
            "brand": asset.get("brand"), "model": asset.get("model"),
            "condition": condition, "priceMinor": price, "districtId": account["districtId"],
            "city": account["city"], "district": account["district"],
            "negotiable": bool(index % 3), "deliveryMethod": delivery,
        })
    if {item["categoryRoot"] for item in plan} != set(PRICES):
        raise ValueError("Demo plan must cover all 13 equipment category groups")
    return plan


def payload_hash(plan):
    value = {key: plan[key] for key in FIELDS}
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def owner_media(listing_id, token):
    assets = []
    for page in range(10):
        result = api("GET", f"/api/v1/user/media/owner/MARKETPLACE/{listing_id}?page={page}&size=50", token=token)
        assets.extend(result["content"])
        if result["last"]:
            return assets
    raise RuntimeError("Unexpectedly large demo media history; refusing automatic recovery")


def media_matches(asset, listing_id, mime, size):
    return (asset.get("ownerType") == "MARKETPLACE" and asset.get("ownerId") == listing_id
            and asset.get("kind") == "IMAGE" and asset.get("visibility") == "PRIVATE"
            and asset.get("contentAudience") == "BACKSTAGE"
            and asset.get("mimeType") == mime and asset.get("size") == size)


def validate_upload_url(value, asset_id, *, verified=False):
    url = urllib.parse.urlsplit(value)
    host = url.hostname or ""
    path = urllib.parse.unquote(url.path)
    # protected/ selects the private bucket but is removed by physicalKey().
    # Private committed originals and thumbnails retain private-verified/.
    prefix = f"/private-verified/media/{asset_id}/" if verified else f"/media/{asset_id}/"
    suffix = path[len(prefix):] if path.startswith(prefix) else ""
    allowed_path = (r"(?:attempts/[0-9a-f-]{36}/)?(?:source\.(?:jpg|jpeg|png|webp)|thumbnail\.jpg)"
                    if verified else r"source\.(?:jpg|jpeg|png|webp)")
    if (url.scheme != "https" or url.username or url.password or url.port not in (None, 443)
            or url.fragment or not re.fullmatch(r"[a-z0-9][a-z0-9.-]*\.s3(?:[.-][a-z]{2}(?:-[a-z]+)+-\d)?\.amazonaws\.com", host)
            or not re.fullmatch(allowed_path, suffix)
            or ".." in path.split("/") or "\x00" in path):
        raise RuntimeError("Unexpected private upload destination")


def discard_failed_upload(record, token, save):
    asset_id = record["assetId"]
    listing = api("GET", MARKET + "/listings/" + record["listingId"], token=token)
    if (listing["status"] != "DRAFT" or listing["seller"]["userId"] != record["identity"]["userId"]
            or any(photo["assetId"] == asset_id for photo in listing["photos"])):
        raise RuntimeError("Refusing to discard attached or non-draft demo media")
    existing = next((asset for asset in owner_media(record["listingId"], token) if asset["uuid"] == asset_id), None)
    if existing is not None:
        if existing["status"] not in TERMINAL_MEDIA:
            raise RuntimeError("Media failure is not terminal; rerun after verification settles")
        # This endpoint repeats ownership and reference checks under a row lock.
        try:
            api("DELETE", f"/api/v1/user/media/{asset_id}?actingAsType=MARKETPLACE&actingAsId={record['listingId']}", token=token)
        except ApiFailure as error:
            if error.code != 1800:
                raise
    record.setdefault("abandonedAssetIds", []).append(asset_id)
    for key in ("assetId", "uploaded", "ready"):
        record.pop(key, None)
    save()


def upload(plan, record, token, save):
    path = Path(plan["imagePath"])
    mime = mimetypes.guess_type(path.name)[0]
    if mime not in ("image/jpeg", "image/png", "image/webp"):
        raise ValueError("Unsupported image MIME")
    image = path.read_bytes()
    if hashlib.sha256(image).hexdigest() != plan["imageSha256"]:
        raise RuntimeError("Demo image changed after planning")
    existing = owner_media(record["listingId"], token)
    if not record.get("assetId"):
        # A lost init reply still leaves its durable owner-bound asset. Adopt it
        # before opening another upload; signed URLs are never persisted.
        candidates = [asset for asset in existing if asset["status"] not in TERMINAL_MEDIA]
        if len(candidates) > 1 or any(not media_matches(asset, record["listingId"], mime, len(image)) for asset in candidates):
            raise RuntimeError("Ambiguous existing demo media; refusing a new upload")
        if candidates:
            record["assetId"] = candidates[0]["uuid"]
            save()
    if record.get("assetId"):
        current = next((asset for asset in existing if asset["uuid"] == record["assetId"]), None)
        if current and not media_matches(current, record["listingId"], mime, len(image)):
            raise RuntimeError("Existing demo media metadata changed")
        for _ in range(60):
            try:
                complete = api("POST", "/api/v1/user/media/complete-upload", {"assetId": record["assetId"]}, token)
                if complete.get("status") == "READY":
                    record["ready"] = True
                    save()
                    return record["assetId"]
                raise RuntimeError("Unexpected demo image verification state")
            except ApiFailure as error:
                if error.code in (1800, 1815, 1817):
                    discard_failed_upload(record, token, save)
                    break
                if error.code != 1814:
                    raise
            time.sleep(2)
        else:
            raise RuntimeError("Photo verification is still pending; safe to rerun")
    if not record.get("assetId"):
        result = api("POST", "/api/v1/user/media/init-upload", {
            "ownerType": "MARKETPLACE", "ownerId": record["listingId"], "kind": "IMAGE",
            "visibility": "PRIVATE", "contentAudience": "BACKSTAGE", "mimeType": mime,
            "sizeBytes": len(image), "originalFileName": ("demo-" + path.name)[:255],
        }, token)
        record["assetId"] = result["assetId"]
        save()
        validate_upload_url(result["uploadUrl"], record["assetId"])
        request = urllib.request.Request(result["uploadUrl"], image, {"Content-Type": mime}, method="PUT")
        try:
            with HTTP.open(request, timeout=60) as response:
                if response.status not in (200, 204):
                    raise RuntimeError("Private photo upload rejected")
        except (OSError, urllib.error.URLError):
            raise RuntimeError("Private upload interrupted; signed URL omitted. Resume with existing asset recovery.") from None
        record["uploaded"] = True
        save()
    for _ in range(60):
        try:
            result = api("POST", "/api/v1/user/media/complete-upload", {"assetId": record["assetId"]}, token)
            if result.get("status") == "READY":
                record["ready"] = True
                save()
                return record["assetId"]
        except ApiFailure as error:
            if error.code != 1814:
                raise
        time.sleep(2)
    raise RuntimeError("Photo verification is still pending; safe to rerun")


def validate_published(listing, item, record):
    for key in FIELDS:
        actual = (listing.get("category") or {}).get("id") if key == "categoryId" else (
            (listing.get("district") or {}).get("id") if key == "districtId" else listing.get(key))
        expected = item[key]
        if isinstance(expected, str):
            expected = expected.strip() or None
        if actual != expected:
            raise RuntimeError(f"Published demo metadata changed ({key}); refusing to overwrite")
    if (listing.get("currency") != "TRY" or len(listing.get("photos", [])) != 1
            or listing["photos"][0]["assetId"] != record.get("assetId")):
        raise RuntimeError("Published demo photo or currency changed; refusing to overwrite")


def populate(plan, accounts, password, progress_path, verify_only):
    state = read_json(progress_path) if progress_path.exists() else {"listings": {}, "seed": str(NAMESPACE)}
    if state.get("seed") != str(NAMESPACE):
        raise ValueError("Unexpected progress manifest")
    tokens = {}
    account_map = {a["username"]: a for a in accounts}
    def save():
        write_json(progress_path, state)
    for number, item in enumerate(plan, 1):
        username = item["username"]
        if username not in tokens:
            tokens[username] = login(account_map[username], password)
            # Respect the real IP login budget instead of weakening it.
            time.sleep(6.2)
        token = tokens[username]
        record = state["listings"].setdefault(item["key"], {})
        identity = {k: item[k] for k in ("username", "userId", "assetKey", "imageSha256", "clientRequestId")}
        identity["payloadSha256"] = payload_hash(item)
        if record.get("identity") not in (None, identity):
            raise ValueError("Seed input changed for an existing record")
        record["identity"] = identity
        if verify_only and not record.get("listingId"):
            raise RuntimeError("Cannot verify an uncreated listing")
        if not record.get("listingId"):
            draft = api("POST", MARKET + "/drafts", {"clientRequestId": item["clientRequestId"]}, token)
            record["listingId"] = draft["id"]
            save()
        listing = api("GET", MARKET + "/listings/" + record["listingId"], token=token)
        if listing["seller"]["userId"] != item["userId"]:
            raise RuntimeError("Refusing to change another account's listing")
        if listing["status"] == "PUBLISHED":
            validate_published(listing, item, record)
        elif not verify_only and listing["status"] == "DRAFT":
            asset_id = upload(item, record, token, save)
            body = {key: item[key] for key in FIELDS}
            body.update(expectedVersion=listing["version"], photoIds=[asset_id])
            listing = api("PUT", MARKET + "/listings/" + listing["id"], body, token)
            listing = api("POST", MARKET + "/listings/" + listing["id"] + "/publish", {"expectedVersion": listing["version"]}, token)
        else:
            raise RuntimeError("Unexpected listing state; no automatic overwrite or republish")
        if listing["status"] != "PUBLISHED" or len(listing["photos"]) < 1:
            raise RuntimeError("Listing did not publish completely")
        validate_published(listing, item, record)
        if listing["category"]["code"] != item["categoryCode"] or listing["district"]["id"] != item["districtId"]:
            raise RuntimeError("Published listing metadata mismatch")
        for photo in listing["photos"]:
            access = api("GET", f"/api/v1/user/media/{photo['assetId']}/access-url", token=token)
            if not access.get("accessUrl") or access.get("assetId") != photo["assetId"] or not access.get("expiresAt"):
                raise RuntimeError("No verified private photo access")
        record.update(status=listing["status"], version=listing["version"], title=listing["title"], priceMinor=listing["priceMinor"], categoryCode=item["categoryCode"], categoryRoot=item["categoryRoot"], city=item["city"], district=item["district"], verifiedAt=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        save()
        print(f"[{number}/50] {username}: {listing['title']} — PUBLISHED ({len(listing['photos'])} photo)", flush=True)
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accounts", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    accounts = read_json(args.accounts)
    accounts = accounts["accounts"] if isinstance(accounts, dict) else accounts
    assets = read_json(args.assets)
    assets = assets["assets"] if isinstance(assets, dict) else assets
    password = read_json(args.credentials)["password"]
    api("GET", "/actuator/health/readiness")
    token = login(accounts[0], password)
    categories = category_map(api("GET", MARKET + "/categories", token=token))
    plan = make_plan(accounts, assets, categories)
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "listing-plan.json", plan)
    print(f"Plan: {len(accounts)} accounts, {len(plan)} listings, {len({x['assetKey'] for x in plan})} equipment photographs, {len({x['categoryRoot'] for x in plan})} category groups", flush=True)
    if args.apply or args.verify_only:
        populate(plan, accounts, password, args.output / "listing-progress.json", args.verify_only)
        print("COMPLETE: 50 published and verified demo listings; no credentials logged.", flush=True)
    else:
        print("No listing or media mutation performed; add --apply to populate.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Keep exceptions terse and prevent urllib from exposing upload URLs.
        print(f"STOPPED: {error}", flush=True)
        raise SystemExit(1)
