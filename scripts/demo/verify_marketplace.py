"""Read-only demo verification; --run enables login/API/S3 GETs on existing 8080.

Only safe IDs, counts and timings are saved. Tokens and signed URLs stay in RAM.
Original private images are immutable S3 copies, so their bytes must match the
input SHA256. Generated JPEG thumbnails are checked independently, not compared
to the original hash. No listing, account or media mutation endpoint is called.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import time
import urllib.error
import urllib.request

import populate_marketplace as seed

MAX_IMAGE_BYTES = 20_000_000
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}


class VerificationError(RuntimeError):
    """Messages contain only locally chosen descriptions, never remote text."""


def safe_error(error):
    if isinstance(error, seed.ApiFailure):
        return f"API HTTP {error.status}, code {error.code}"
    if isinstance(error, VerificationError):
        return str(error)
    return f"Verification stopped ({type(error).__name__})"


def validate_inputs(accounts, plan, progress):
    if (len(accounts) != 25 or len({account["userId"] for account in accounts}) != 25
            or len(plan) != 50 or len({item["key"] for item in plan}) != 50
            or progress.get("seed") != str(seed.NAMESPACE)):
        raise VerificationError("Expected the complete 25-account, 50-listing demo manifests")
    known = {account["username"]: account for account in accounts}
    listing_ids = set()
    for item in plan:
        account = known.get(item["username"])
        record = progress["listings"].get(item["key"], {})
        identity = {key: item[key] for key in ("username", "userId", "assetKey", "imageSha256", "clientRequestId")}
        identity["payloadSha256"] = seed.payload_hash(item)
        if (not account or not account.get("profileId") or account["userId"] != item["userId"]
                or not account["username"].startswith("demo_")
                or record.get("identity") != identity or not record.get("assetId")
                or not record.get("listingId") or record["listingId"] in listing_ids):
            raise VerificationError("Incomplete or changed demo identity/progress manifest")
        listing_ids.add(record["listingId"])


def verify_metadata(listing, item, record, account):
    if listing.get("id") != record["listingId"] or listing.get("status") != "PUBLISHED":
        raise VerificationError("Listing ID/status mismatch")
    seller = listing.get("seller", {})
    if (seller.get("userId") != account["userId"] or seller.get("username") != account["username"]
            or seller.get("profileType") != account["role"].removeprefix("ROLE_")
            or seller.get("profileId") != account["profileId"]):
        raise VerificationError("Seller user/profile identity mismatch")
    try:
        seed.validate_published(listing, item, record)
    except (KeyError, TypeError, RuntimeError):
        raise VerificationError("Published listing metadata/photo mismatch") from None
    if (listing["category"].get("code") != item["categoryCode"]
            or listing["category"].get("rootCode") != item["categoryRoot"]):
        raise VerificationError("Published category hierarchy mismatch")


def verify_feed(expected_ids, token):
    found = set()
    for page in range(1001):
        result = seed.api("GET", seed.MARKET + f"/listings?page={page}&size=20&sort=NEWEST", token=token)
        if result.get("page") != page or result.get("size") != 20 or len(result["content"]) > 20:
            raise VerificationError("Unexpected discovery pagination contract")
        found.update(item["id"] for item in result["content"] if item["id"] in expected_ids)
        if found == expected_ids:
            return {"pagesRead": page + 1, "discoveredListings": len(found)}
        if result["last"]:
            break
    raise VerificationError(f"Discovery is missing {len(expected_ids - found)} demo listings")


def require_unexpired(value):
    try:
        expiry = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if expiry.tzinfo is None or expiry <= datetime.now(timezone.utc):
            raise ValueError()
    except (AttributeError, TypeError, ValueError):
        raise VerificationError("Image access grant is missing or expired") from None


def fetch_image(url, asset_id, expected_hash=None):
    try:
        seed.validate_upload_url(url, asset_id, verified=True)
    except (TypeError, ValueError, RuntimeError):
        raise VerificationError("Image URL is outside the protected S3 asset path") from None
    start = time.monotonic()
    digest = hashlib.sha256()
    size = 0
    # A separate opener per worker; redirects never receive signed requests.
    opener = urllib.request.build_opener(seed.NoRedirect)
    try:
        request = urllib.request.Request(url, headers={"Accept": "image/jpeg,image/png,image/webp"}, method="GET")
        with opener.open(request, timeout=30) as response:
            if response.status != 200:
                raise VerificationError(f"Image HTTP status {response.status}")
            mime = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if mime not in IMAGE_TYPES:
                raise VerificationError("Image response has a non-image Content-Type")
            length = response.headers.get("Content-Length")
            if length and (not length.isdigit() or not 0 < int(length) <= MAX_IMAGE_BYTES):
                raise VerificationError("Image Content-Length is outside the byte limit")
            while True:
                chunk = response.read(min(65536, MAX_IMAGE_BYTES + 1 - size))
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_IMAGE_BYTES or time.monotonic() - start > 60:
                    raise VerificationError("Image download exceeded its byte/time limit")
                digest.update(chunk)
            if not size or length and int(length) != size:
                raise VerificationError("Image body is empty or truncated")
    except urllib.error.HTTPError as error:
        raise VerificationError(f"Image HTTP status {error.code}") from None
    except (OSError, urllib.error.URLError):
        raise VerificationError("Image transport interrupted") from None
    if expected_hash and digest.hexdigest() != expected_hash:
        raise VerificationError("Original photo SHA256 does not match the seed input")
    return {"httpStatus": 200, "bytes": size, "contentType": mime,
            "elapsedMs": round((time.monotonic() - start) * 1000),
            "originalHashMatched": bool(expected_hash)}


def verify_photo(item, record, token):
    asset_id = record["assetId"]
    access = seed.api("GET", f"/api/v1/user/media/{asset_id}/access-url", token=token)
    if access.get("assetId") != asset_id:
        raise VerificationError("Image access asset identity mismatch")
    require_unexpired(access.get("expiresAt"))
    require_unexpired(access.get("thumbnailExpiresAt"))
    if not access.get("accessUrl") or not access.get("thumbnailAccessUrl"):
        raise VerificationError("Original or thumbnail access is not ready")
    original = fetch_image(access["accessUrl"], asset_id, item["imageSha256"])
    thumbnail = fetch_image(access["thumbnailAccessUrl"], asset_id)
    return {"listingId": record["listingId"], "assetId": asset_id,
            "original": original, "thumbnail": thumbnail}


def verify(accounts, plan, progress, token, workers=4):
    start = time.monotonic()
    known = {account["username"]: account for account in accounts}
    ids = set()
    for item in plan:
        record = progress["listings"][item["key"]]
        listing = seed.api("GET", seed.MARKET + "/listings/" + record["listingId"], token=token)
        verify_metadata(listing, item, record, known[item["username"]])
        ids.add(listing["id"])
    feed = verify_feed(ids, token)
    photos, failures = [], []
    with ThreadPoolExecutor(max_workers=min(4, max(1, workers))) as pool:
        jobs = {pool.submit(verify_photo, item, progress["listings"][item["key"]], token):
                progress["listings"][item["key"]] for item in plan}
        for job in as_completed(jobs):
            record = jobs[job]
            try:
                photos.append(job.result())
            except Exception as error:
                failures.append({"listingId": record["listingId"], "assetId": record["assetId"], "reason": safe_error(error)})
    return {"success": not failures, "checkedAt": datetime.now(timezone.utc).isoformat(),
            "metadataVerified": len(ids), **feed, "originalsVerified": len(photos),
            "thumbnailsVerified": len(photos), "elapsedMs": round((time.monotonic() - start) * 1000),
            "photos": sorted(photos, key=lambda photo: photo["listingId"]), "failures": failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("accounts", "plan", "progress", "credentials"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--run", action="store_true")
    args = parser.parse_args()
    accounts = seed.read_json(args.accounts)
    accounts = accounts["accounts"] if isinstance(accounts, dict) else accounts
    plan, progress = seed.read_json(args.plan), seed.read_json(args.progress)
    validate_inputs(accounts, plan, progress)
    if not args.run:
        print("Inputs verified locally: 25 accounts, 50 listings. Add --run for read-only API/S3 checks.", flush=True)
        return 0
    output = args.output or args.progress.with_name("listing-verification.json")
    try:
        actor = next(account for account in accounts if account["role"].removeprefix("ROLE_") == "MUSICIAN")
        token = seed.login(actor, seed.read_json(args.credentials)["password"])
        result = verify(accounts, plan, progress, token)
    except Exception as error:
        result = {"success": False, "failures": [{"reason": safe_error(error)}]}
    seed.write_json(output, result)
    print(f"Verification {'PASSED' if result['success'] else 'FAILED'}: "
          f"{result.get('metadataVerified', 0)} listings, {result.get('originalsVerified', 0)} original/thumbnail pairs.", flush=True)
    return 0 if result["success"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("STOPPED: " + safe_error(error), flush=True)
        raise SystemExit(1)
