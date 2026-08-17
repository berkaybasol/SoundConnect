# Türkiye location seed

`src/main/resources/location-seed.json` contains the application's Türkiye
location hierarchy. The checked-in snapshot has:

- 81 provinces (`City` in the current domain model)
- 973 user-facing districts, including the 51 provincial `Merkez` districts
- 32,254 municipality neighborhoods

The snapshot is generated from the MIT-licensed
[TurkiyeAPI 2025 static datasets](https://docs.turkiyeapi.dev/en/v2/guide/datasets).
The dataset metadata identifies TÜİK MEDAS, PTT, the General Directorate of
Mapping, Türk Telekom and OpenStreetMap as its upstream sources. At generation
time the dataset metadata reported `lastUpdated=2026-05-21`.
The upstream MIT notice is preserved in
[`docs/third-party/turkiye-api-LICENSE.txt`](third-party/turkiye-api-LICENSE.txt).
Only administrative names and hierarchy relationships are copied into the seed;
coordinates and other OpenStreetMap-derived fields are not imported.

## Why every province has neighborhoods

Venue and studio onboarding require a `neighborhoodId`. Leaving small provinces
or districts with an empty neighborhood list would make those flows impossible
there, so the snapshot includes every municipality neighborhood rather than only
the main nightlife and university cities.

The source contains 244 district/name collisions: 576 neighborhood records in
different municipalities share a name inside the same district. The current
domain model has no municipality level, so the generator disambiguates all rows
in each collision with the municipality name, for example:

```text
Fatih (Kesmetepe)
Fatih (Şambayat)
```

This preserves all 32,254 source records and gives the user distinct choices.
Unique neighborhood names are kept unchanged. Villages are not imported because
the current domain model represents neighborhoods only.

Cities, districts and neighborhoods are stored and returned with the same
deterministic Turkish alphabetical order. The comparison follows
`C < Ç`, `G < Ğ`, `I < İ`, `O < Ö`, `S < Ş`, `U < Ü` and treats leading
numbers naturally, so `2` comes before `10`, then `100`. Backend response
sorting does not depend on PostgreSQL/H2 collation, and the Flutter repository
applies the same rule as a client-side safeguard.

## Regenerating the snapshot

From the backend repository root on PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-location-seed.ps1
```

The generator uses versioned dataset URLs, validates source IDs and foreign-key
relationships, rejects duplicate generated labels, verifies the expected record
counts and Turkish alphabetical ordering, and writes UTF-8 without a BOM.

`LocationSeeder` is insert-only: it creates missing names but does not rename or
delete rows already referenced by application data. Production also requires
`app.location.seed.enabled=false`, so updating this file is not a production data
migration. Existing environments need a separately reviewed import/migration if
they already contain legacy location rows such as `Moda` or `Yüzüncüyıl`.
