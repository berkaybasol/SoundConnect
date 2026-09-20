"""Offline verifier checks; all local API and S3 operations are mocked."""
import copy
import hashlib
import io
import json
import unittest
from unittest.mock import Mock, patch

import populate_marketplace as seed
import verify_marketplace as verify


DATA = b"\xff\xd8\xffdemo-original"
HASH = hashlib.sha256(DATA).hexdigest()
URL = "https://demo-private.s3.eu-central-1.amazonaws.com/private-verified/media/asset/attempts/12345678-1234-1234-1234-123456789012/source.jpg?X-Amz-Signature=NEVER_LOG"


class Response(io.BytesIO):
    status = 200

    def __init__(self, data=DATA, mime="image/jpeg", length=None):
        super().__init__(data)
        self.headers = {"Content-Type": mime, "Content-Length": str(len(data) if length is None else length)}


def manifests():
    accounts, plan, records, listings = [], [], {}, {}
    for i in range(25):
        accounts.append({"username": f"demo_{i}", "userId": f"user-{i}", "profileId": f"profile-{i}",
                         "role": ("MUSICIAN", "STUDIO", "VENUE")[i % 3]})
    for i in range(50):
        account = accounts[i // 2]
        item = {"key": f"item-{i}", "username": account["username"], "userId": account["userId"],
                "assetKey": f"equipment-{i}", "imageSha256": HASH, "clientRequestId": f"request-{i}",
                "title": f"Demo product {i}", "description": "Synthetic demo description", "categoryId": "category",
                "categoryCode": "LEAF", "categoryRoot": "ROOT", "brand": "Brand", "model": None,
                "condition": "USED", "priceMinor": 1200000, "districtId": "district", "negotiable": False,
                "deliveryMethod": "BOTH"}
        identity = {key: item[key] for key in ("username", "userId", "assetKey", "imageSha256", "clientRequestId")}
        identity["payloadSha256"] = seed.payload_hash(item)
        record = {"listingId": f"listing-{i}", "assetId": f"asset-{i}", "identity": identity}
        listing = {**item, "id": record["listingId"], "status": "PUBLISHED", "currency": "TRY",
                   "seller": {**account, "profileType": account["role"]},
                   "category": {"id": "category", "code": "LEAF", "rootCode": "ROOT"},
                   "district": {"id": "district"}, "photos": [{"assetId": record["assetId"]}]}
        plan.append(item)
        records[item["key"]] = record
        listings[record["listingId"]] = listing
    return accounts, plan, {"seed": str(seed.NAMESPACE), "listings": records}, listings


class VerifyMarketplaceTest(unittest.TestCase):
    def fetch(self, response, expected_hash=HASH, url=URL):
        opener = Mock()
        opener.open.return_value = response
        with patch.object(verify.urllib.request, "build_opener", return_value=opener):
            result = verify.fetch_image(url, "asset", expected_hash)
        return result, opener

    def test_original_physical_private_path_and_hash_match(self):
        result, opener = self.fetch(Response())
        self.assertTrue(result["originalHashMatched"])
        self.assertEqual(result["bytes"], len(DATA))
        self.assertNotIn("NEVER_LOG", json.dumps(result))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.get_header("Authorization"))

    def test_thumbnail_path_is_valid_but_does_not_require_original_hash(self):
        result, _ = self.fetch(Response(b"different-generated-jpeg"), None, URL.replace("source.jpg", "thumbnail.jpg"))
        self.assertFalse(result["originalHashMatched"])

    def test_bad_hash_empty_nonimage_and_truncation_fail_closed(self):
        for response, expected in ((Response(b"different"), HASH), (Response(b""), HASH),
                                   (Response(mime="text/html"), HASH), (Response(length=len(DATA) + 10), HASH),
                                   (Response(length=verify.MAX_IMAGE_BYTES + 1), HASH)):
            with self.subTest(response=response), self.assertRaises(verify.VerificationError):
                self.fetch(response, expected)

    def test_public_or_wrong_asset_url_stops_before_get(self):
        for url in (URL.replace("/private-verified/", "/"), URL.replace("/asset/", "/another-asset/"),
                    URL.replace("amazonaws.com", "amazonaws.com.evil.test")):
            with patch.object(verify.urllib.request, "build_opener") as opener:
                with self.assertRaises(verify.VerificationError):
                    verify.fetch_image(url, "asset", HASH)
                opener.assert_not_called()

    def test_expired_missing_thumbnail_grant_fails_without_download(self):
        response = {"assetId": "asset", "accessUrl": URL, "expiresAt": "2999-01-01T00:00:00Z",
                    "thumbnailAccessUrl": URL.replace("source.jpg", "thumbnail.jpg"), "thumbnailExpiresAt": "2000-01-01T00:00:00Z"}
        with patch.object(seed, "api", return_value=response), patch.object(verify, "fetch_image") as fetch:
            with self.assertRaisesRegex(verify.VerificationError, "expired"):
                verify.verify_photo({"imageSha256": HASH}, {"assetId": "asset", "listingId": "listing"}, "token")
            fetch.assert_not_called()

    def test_full_fifty_metadata_profile_feed_and_image_contract(self):
        accounts, plan, progress, listings = manifests()
        verify.validate_inputs(accounts, plan, progress)
        calls = []
        def api(method, path, body=None, token=None):
            self.assertEqual(method, "GET")
            calls.append(path)
            if "/listings?" in path:
                page = int(path.split("page=")[1].split("&")[0])
                return {"content": list(listings.values())[page * 20:(page + 1) * 20],
                        "page": page, "size": 20, "last": page == 2}
            if path.endswith("/access-url"):
                asset_id = path.split("/")[-2]
                return {"assetId": asset_id, "accessUrl": URL.replace("/asset/", f"/{asset_id}/"),
                        "thumbnailAccessUrl": URL.replace("/asset/", f"/{asset_id}/").replace("source.jpg", "thumbnail.jpg"),
                        "expiresAt": "2999-01-01T00:00:00Z", "thumbnailExpiresAt": "2999-01-01T00:00:00Z"}
            return listings[path.split("/")[-1]]
        with patch.object(seed, "api", side_effect=api), patch.object(verify, "fetch_image", return_value={"httpStatus": 200, "bytes": 10}) as fetch:
            result = verify.verify(accounts, plan, progress, "MEMORY_TOKEN")
        self.assertTrue(result["success"])
        self.assertEqual(result["metadataVerified"], 50)
        self.assertEqual(result["discoveredListings"], 50)
        self.assertEqual(result["pagesRead"], 3)
        self.assertEqual(fetch.call_count, 100)
        self.assertEqual(len([path for path in calls if "size=20" in path]), 3)
        for sensitive in ("MEMORY_TOKEN", "NEVER_LOG", "accessUrl", "amazonaws"):
            self.assertNotIn(sensitive, json.dumps(result))

    def test_changed_profile_or_published_metadata_fails(self):
        accounts, plan, progress, listings = manifests()
        item = plan[0]
        for target, key in (("seller", "userId"), ("seller", "profileId"), ("seller", "profileType"),
                            ("category", "rootCode"), (None, "priceMinor")):
            listing = copy.deepcopy(listings["listing-0"])
            (listing[target] if target else listing)[key] = "changed"
            with self.subTest(key=key), self.assertRaises(verify.VerificationError):
                verify.verify_metadata(listing, item, progress["listings"][item["key"]], accounts[0])
        progress["listings"][item["key"]]["identity"]["payloadSha256"] = "changed"
        with self.assertRaises(verify.VerificationError):
            verify.validate_inputs(accounts, plan, progress)

    def test_feed_missing_demo_fails_after_last_page(self):
        with patch.object(seed, "api", return_value={"content": [{"id": "present"}], "page": 0, "size": 20, "last": True}):
            with self.assertRaisesRegex(verify.VerificationError, "missing 1"):
                verify.verify_feed({"present", "missing"}, "token")

    def test_unexpected_error_message_never_leaks_signed_url(self):
        self.assertNotIn("NEVER_LOG", verify.safe_error(RuntimeError(URL)))


if __name__ == "__main__":
    unittest.main()
