# Local marketplace demo accounts

This one-shot CLI provisions the 25 fictional accounts in `accounts.json`: 15 musicians, six studios and four venues. It does not start Spring, modify registration or OTP, send mail, or create media/listings. Emails use the reserved `.invalid` suffix. No real personal data is required.

Run from the backend repository with the existing local PostgreSQL Compose service and JDK 21:

```powershell
./scripts/demo/seed-local-accounts.ps1
./scripts/demo/seed-local-accounts.ps1 -Apply -PasswordFile <path-to-ignored-credentials.local.json>
```

The optional password file must be JSON with a `password` string of 16 or more characters and at most 72 UTF-8 bytes. Alternatively set `SOUNDCONNECT_DEMO_PASSWORD` in the invoking process. Never pass the password on the command line or commit the credentials file. The launcher restores the previous environment value when it exits. Passwords are encoded with the same default `BCryptPasswordEncoder` used by `SecurityConfig`.

The default invocation performs a read-only transaction and writes `tmp/marketplace-demo/accounts-preview.json`. Only `-Apply` inserts data and writes `tmp/marketplace-demo/accounts-created.json`. Both manifests contain account/profile/location IDs and no credentials or password hashes. For venues, `profileId` is the venue ID used by public profiles/marketplace, while `venueProfileId` identifies its separate profile row. Missing neighborhood names resolve to the first existing neighborhood of the selected district; city/district/neighborhood links are checked against existing catalogs.

The launcher requires a local Docker socket, exactly one running `soundconnect-local` / `postgres` Compose container owned by this repository, and a loopback-only host port. It compares the in-container database name and PostgreSQL cluster system identifier with the JDBC connection before any inserts. JDBC must use the same port/database and cannot include URL options or credentials. Configuration is read internally from `.env.local` and matching process variables, never printed. No dependency downloads, Gradle tasks, API restart or additional server are involved; JDK source-file mode runs this helper using already cached dependency jars.

All 25 accounts and their profiles are inserted in one transaction under a seed-specific advisory lock. IDs/public codes are deterministic for this seed and username. Repeated runs validate identity, role, profile ownership and password, then skip existing records. Conflicts abort the transaction; the tool never resets passwords, overwrites profiles, grants admin/direct permissions or deletes data. Mutable profile text is left unchanged so subsequent API edits survive a repeat. If the transaction committed but manifest writing was interrupted, repeat the same command to reconstruct the manifest without duplicate accounts.

Only initial identity/profile rows bypass OTP locally. Use normal login and profile/media/marketplace APIs afterwards so validation, private-media processing and listing lifecycle rules remain exercised. Studio/venue locations and musician instruments reference the existing catalog; no schema/catalog data is added. Keep the generated accounts and assets until the user requests cleanup.

## Listings and licensed photographs

`populate_marketplace.py` uses only the Python standard library and the existing
API at `http://127.0.0.1:8080`. It creates two listings per account, with 25 distinct
photographs covering 13 category groups. `assets.json` preserves source URLs,
licenses, authors, SHA256 values, verified equipment titles and fictional demo
prices. Attribution text is included in every listing description. Listings
explicitly state that they are demo records, not real sales.

Local photographs live in `tmp/marketplace-demo/images/`; they are not committed.
Paths in `assets.json` are relative to the backend working directory. On another
machine, obtain the same licensed files from their sources and verify their
SHA256 values before using existing progress. Do not silently replace files with
different content from a changed source URL.

```powershell
python scripts/demo/populate_marketplace.py --accounts tmp/marketplace-demo/accounts-created.json --assets scripts/demo/assets.json --credentials <ignored-credentials-file> --output tmp/marketplace-demo
# Inspect listing-plan.json, then add --apply to the same command.
# Use --verify-only to inspect completed listings without modifying them.
```

The tool runs normal login, draft, private S3 upload, server verification, update
and publish endpoints. It never fabricates media readiness in the database or
changes rate limits. Bearer tokens and signed S3 URLs remain in memory. Progress
contains only safe IDs and metadata in `listing-progress.json`; preserve it and
rerun with the same inputs after interruption. Published user edits are never
overwritten. Recovery can remove only a permanently rejected, unreferenced
upload belonging to this tool's own draft; ready or attached photos are retained.

## Verification

```powershell
python scripts/demo/verify_marketplace.py --accounts tmp/marketplace-demo/accounts-created.json --plan tmp/marketplace-demo/listing-plan.json --progress tmp/marketplace-demo/listing-progress.json --credentials <ignored-credentials-file> --run
python -m unittest discover -s scripts/demo -p 'test_*.py'
```

The verifier checks all 50 listings, seller/profile identities and discovery
pagination. It fetches every original and thumbnail over HTTPS, checks status,
MIME and nonempty content, and compares each original SHA256 with the input.
At most four reads run concurrently. Without `--run`, it only validates local
input manifests. Results contain counts, IDs and timings, never signed URLs.

The 22 offline tests cover interrupted upload recovery, ownership/reference
guards, input changes, physical S3 paths, metadata and read-only verification.
Populating 50 listings is functional test coverage, not a capacity test for
thousands of concurrent users. Previous QA data and the new demo records are
preserved for review; there is no automatic cleanup.
