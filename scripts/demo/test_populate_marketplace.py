"""Offline contract/recovery tests. No HTTP request or application process runs."""
import copy
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import populate_marketplace as seed


class FakeApi:
    def __init__(self, initial=None, lost_init=False, lost_put=False, uploaded=False):
        self.assets = {} if initial is None else {initial["uuid"]: initial}
        self.bytes_uploaded = set(self.assets) if uploaded else set()
        self.calls = []
        self.puts = []
        self.lost_init = lost_init
        self.lost_put = lost_put
        self.attached = False

    @staticmethod
    def asset(asset_id, status="UPLOADING"):
        return {"uuid": asset_id, "ownerType": "MARKETPLACE", "ownerId": "listing",
                "kind": "IMAGE", "visibility": "PRIVATE", "contentAudience": "BACKSTAGE",
                "mimeType": "image/jpeg", "size": 1203, "status": status}

    def __call__(self, method, path, body=None, token=None, **kwargs):
        self.calls.append((method, path, body))
        if "/owner/MARKETPLACE/" in path:
            return {"content": list(self.assets.values()), "last": True}
        if path == seed.MARKET + "/listings/listing":
            return {"status": "DRAFT", "seller": {"userId": "user"},
                    "photos": [{"assetId": "old"}] if self.attached else []}
        if path.endswith("/init-upload"):
            asset_id = "new-" + str(sum(p.endswith("/init-upload") for _, p, _ in self.calls))
            self.assets[asset_id] = self.asset(asset_id)
            if self.lost_init:
                self.lost_init = False
                raise RuntimeError("lost init reply")
            return {"assetId": asset_id,
                    "uploadUrl": f"https://demo-private.s3.eu-central-1.amazonaws.com/media/{asset_id}/source.jpg?X-Amz-Signature=omitted"}
        if path.endswith("/complete-upload"):
            asset_id = body["assetId"]
            asset = self.assets.get(asset_id)
            if asset is None:
                raise seed.ApiFailure(method, path, 404, 1800)
            if asset["status"] in seed.TERMINAL_MEDIA:
                raise seed.ApiFailure(method, path, 409, 1817)
            if asset["status"] == "READY":
                return asset
            if asset_id not in self.bytes_uploaded:
                asset["status"] = "CLEANUP_PENDING"
                raise seed.ApiFailure(method, path, 409, 1815)
            asset["status"] = "READY"
            return asset
        if method == "DELETE":
            asset_id = path.split("?")[0].split("/")[-1]
            self.assets[asset_id]["status"] = "DELETION_PENDING"
            return None
        raise AssertionError(f"Unexpected fake request: {method} {path}")

    def open(self, request, timeout):
        asset_id = request.full_url.split("/media/")[1].split("/")[0]
        self.puts.append(asset_id)
        if self.lost_put:
            self.lost_put = False
            raise OSError("synthetic transport error")
        self.bytes_uploaded.add(asset_id)
        return FakeResponse()


class FakeResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class PopulateMarketplaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.photo = Path(self.temp.name) / "photo.jpg"
        self.photo.write_bytes(b"\xff\xd8\xff" + b"x" * 1200)
        self.plan = {"imagePath": str(self.photo),
                     "imageSha256": hashlib.sha256(self.photo.read_bytes()).hexdigest()}
        self.record = {"listingId": "listing", "identity": {"userId": "user"}}
        self.saved = []
        self.addCleanup(patch.stopall)
        patch.object(seed.time, "sleep", lambda _: None).start()

    def run_upload(self, fake):
        with patch.object(seed, "api", fake), patch.object(seed.HTTP, "open", fake.open):
            return seed.upload(self.plan, self.record, "memory-only-token",
                               lambda: self.saved.append(copy.deepcopy(self.record)))

    def test_lost_init_reply_adopts_orphan_before_replacement(self):
        fake = FakeApi(lost_init=True)
        with self.assertRaisesRegex(RuntimeError, "lost init"):
            self.run_upload(fake)
        self.assertNotIn("assetId", self.record)
        old_id = next(iter(fake.assets))
        result = self.run_upload(fake)
        self.assertEqual(result, "new-2")
        self.assertEqual(fake.assets[old_id]["status"], "DELETION_PENDING")
        self.assertEqual(fake.puts, ["new-2"])
        self.assertIn(old_id, self.record["abandonedAssetIds"])

    def test_failed_put_resumes_without_permanent_complete_loop(self):
        fake = FakeApi(lost_put=True)
        with self.assertRaisesRegex(RuntimeError, "Private upload interrupted"):
            self.run_upload(fake)
        old_id = self.record["assetId"]
        self.assertNotIn("uploaded", self.record)
        self.assertEqual(self.run_upload(fake), "new-2")
        self.assertEqual(fake.assets[old_id]["status"], "DELETION_PENDING")
        self.assertTrue(self.record["ready"])

    def test_put_committed_but_reply_lost_reuses_original_asset(self):
        self.record["assetId"] = "old"
        fake = FakeApi(FakeApi.asset("old"), uploaded=True)
        self.assertEqual(self.run_upload(fake), "old")
        self.assertFalse(fake.puts)
        self.assertFalse(any(method == "DELETE" or path.endswith("/init-upload") for method, path, _ in fake.calls))

    def test_lost_init_existing_ready_is_adopted_without_put(self):
        fake = FakeApi(FakeApi.asset("old", "READY"))
        self.assertEqual(self.run_upload(fake), "old")
        self.assertFalse(fake.puts)

    def test_multiple_existing_candidates_stop_before_mutation(self):
        fake = FakeApi(FakeApi.asset("old"))
        fake.assets["other"] = FakeApi.asset("other")
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.run_upload(fake)
        self.assertTrue(all(method == "GET" for method, _, _ in fake.calls))

    def test_unexpected_owner_metadata_stops_before_complete_or_delete(self):
        fake = FakeApi(FakeApi.asset("old"))
        fake.assets["old"]["ownerId"] = "someone-else"
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.run_upload(fake)
        self.assertTrue(all(method == "GET" for method, _, _ in fake.calls))

    def test_recovery_never_deletes_referenced_photo(self):
        fake = FakeApi(FakeApi.asset("old", "CLEANUP_PENDING"))
        fake.attached = True
        self.record["assetId"] = "old"
        with self.assertRaisesRegex(RuntimeError, "Refusing to discard"):
            self.run_upload(fake)
        self.assertEqual(self.record["assetId"], "old")
        self.assertFalse(any(method == "DELETE" for method, _, _ in fake.calls))

    def test_pending_verification_retains_asset_without_new_upload(self):
        self.record["assetId"] = "old"
        fake = FakeApi(FakeApi.asset("old", "VERIFYING"))
        def pending(method, path, body=None, token=None):
            if path.endswith("/complete-upload"):
                raise seed.ApiFailure(method, path, 409, 1814)
            return fake(method, path, body, token)
        with patch.object(seed, "api", pending):
            with self.assertRaisesRegex(RuntimeError, "still pending"):
                seed.upload(self.plan, self.record, "token", lambda: None)
        self.assertEqual(self.record["assetId"], "old")
        self.assertTrue(all(method == "GET" for method, _, _ in fake.calls))

    def test_image_mutation_after_plan_is_rejected_before_any_api(self):
        self.photo.write_bytes(b"different")
        fake = FakeApi()
        with self.assertRaisesRegex(RuntimeError, "changed after planning"):
            self.run_upload(fake)
        self.assertFalse(fake.calls)

    def test_only_protected_s3_asset_destination_is_accepted(self):
        valid = "https://demo-private.s3.eu-central-1.amazonaws.com/media/asset/source.jpg?X-Amz-Signature=x"
        seed.validate_upload_url(valid, "asset")
        for invalid in (valid.replace("https:", "http:"), valid.replace("amazonaws.com", "amazonaws.com.evil.test"),
                        valid.replace("s3.eu-central-1", "s3.evil"), valid.replace("/media/", "/public/media/"),
                        valid.replace("/asset/", "/other/"), valid.replace("https://", "https://user:password@"),
                        valid.replace("/media/", "/media/../media/")):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(RuntimeError, "Unexpected private"):
                seed.validate_upload_url(invalid, "asset")

    def test_published_verification_rejects_all_payload_or_photo_drift(self):
        item = {"title": "Demo guitar", "description": "This is a demo listing", "categoryId": "leaf",
                "brand": "Demo", "model": None, "condition": "USED", "priceMinor": 900000,
                "districtId": "district", "negotiable": True, "deliveryMethod": "BOTH"}
        listing = {**item, "category": {"id": "leaf"}, "district": {"id": "district"},
                   "currency": "TRY", "photos": [{"assetId": "photo"}]}
        record = {"assetId": "photo"}
        seed.validate_published(listing, item, record)
        for field in seed.FIELDS:
            changed = copy.deepcopy(listing)
            if field in ("categoryId", "districtId"):
                changed[field[:-2]]["id"] = "changed"
            else:
                changed[field] = "changed"
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                seed.validate_published(changed, item, record)
        with self.assertRaises(RuntimeError):
            seed.validate_published(listing, item, {"assetId": "different"})
        changed = dict(item, priceMinor=1000000)
        self.assertNotEqual(seed.payload_hash(item), seed.payload_hash(changed))

    def test_root_price_keys_match_actual_catalog(self):
        catalog = seed.read_json(Path(__file__).resolve().parents[2] / "src/main/resources/marketplace-category-seed.json")
        self.assertEqual(set(seed.PRICES), {root["code"] for root in catalog})

    def test_list_manifest_preserves_reviewed_attribution_in_fifty_item_plan(self):
        accounts = [{"username": f"demo_{i:02}", "userId": f"user-{i}", "role": "MUSICIAN",
                     "districtId": "district", "district": "Kadıköy", "city": "İstanbul"} for i in range(25)]
        roots = list(seed.PRICES)
        categories = {f"LEAF_{i}": {"id": f"category-{i}", "code": f"LEAF_{i}", "name": "Ekipman",
                                   "rootCode": roots[i % len(roots)]} for i in range(25)}
        attribution = "Fotoğraf: Demo sanatçı; kırpma: Editör — CC BY-SA. Kaynak ve lisans URL. Commons boyutlandırması."
        assets = [{"key": f"equipment-{i}", "imagePath": str(self.photo), "categoryCode": f"LEAF_{i}",
                   "titleTr": f"Demo ekipman {i}", "attributionText": attribution} for i in range(25)]
        plan = seed.make_plan(accounts, assets, categories)
        self.assertEqual(len(plan), 50)
        self.assertEqual(len({item["categoryRoot"] for item in plan}), 13)
        self.assertTrue(all(attribution in item["description"] for item in plan))
        self.assertEqual(len({item["clientRequestId"] for item in plan}), 50)
        changed = copy.deepcopy(assets)
        changed[0]["priceMinor"] = 1.5
        with self.assertRaisesRegex(ValueError, "price"):
            seed.make_plan(accounts, changed, categories)


if __name__ == "__main__":
    unittest.main()
