# Worker native dependency

The worker compiles the official signed FFmpeg 9.0.2 source on the same pinned
Alpine 3.24 / Temurin 21 runtime base. The Java build stage remains separate.
The API image contains no FFmpeg/FFprobe. Both application processes retain
UID/GID 999; the pinned Alpine base's empty `ping` GID 999 record is checked
before replacing that group with the application group.

`build-ffmpeg.sh` verifies the exact archive SHA-256 and release-key fingerprint
before extraction. It then checks each base file, exact proposal/local patch and
resulting file hashes around zero-fuzz patch applications. The public key,
patches and build script are the only native build inputs admitted by the root
Docker context allowlist.

`0001` retains proposal commit `94924d045d5dce70f5557d400bb6d6b99c0ab96b`
(iterative encryption-init-info cleanup). `0002` is a local 9.0.2 adaptation
of the append API and MOV changes proposed in PR 24593, commits
`abbd3cd2866dd5cc3491d99b19aa79f9287daf72` and
`df40e69194187f8ab0db3558bf2a154d32fd0040`. Upstream merge status has not been
verified; this is a modified local build, not an official fixed release.

The new path appends serialized PSSH records without repeatedly decoding the
previous list. Growth is amortized while the buffer's capacity remains known;
root/fragment changes invalidate the cache, so this is not a claim of global
linear time for every possible file. A local adjustment commits the cached
pointer and capacity together only after successful insertion. The API comment
documents writable ownership, unknown capacity and failure behavior.

The master-only version/APIchanges hunks are omitted: 9.0.2 retains libavutil
61.1.102 and does not advertise unrelated 61.11 APIs. All native libraries
must be built and delivered together; patched libavformat requires the new
append symbol and must not be mixed with a stock libavutil of the same SONAME.
`cpu-patch.txt` identifies the exact local patch and all five resulting file
hashes. The hash in `patch.txt` is explicitly the intermediate state after
0001 and before 0002. The build also requires `fate-encryption-info` to pass.

External dependencies are explicit: x264 for H.264 encode, dav1d for AV1 decode,
WebP and zlib. Autodetection, network, device inputs and non-local protocols are
disabled; only `file` and `pipe` protocols remain. `lavfi` is a virtual test input.
Matroska/WebM output muxers are disabled; their input decoding remains available.
The runtime package list and dynamic links must confirm that aom, zvbi and rist
are absent. The final OpenSSL package version must be checked independently.

The image retains FFmpeg source/patch hashes, signature status, build configuration
and licenses under `/usr/local/share/soundconnect-ffmpeg`. Alpine package indexes
can advance despite the base digest pin: record the final image digest and exact
installed package inventory. Updating a base or package is not itself a security
acceptance result.

## Acceptance

`scripts/staging/test_media_runtime.py --run --images <candidate-images.json>
--evidence <new-directory>` runs only owned synthetic containers and an internal
network. It compiles a small Java harness against the exact image JAR and exercises
the existing production media classes, not a copied implementation. The checks
cover Java DNS/TLS (including wrong CA/hostname), HLS fMP4/TS with and without AAC,
MP4/video thumbnails, AV1/VP9/HEVC inputs, PNG/JPEG/WebP image thumbnails, local
protocol restrictions, malformed input and output-budget rejection. No Spring
application context, AWS, database or real user media is used.

`scripts/staging/media_runtime/encryption_info_smoke.c` is a bounded normal API
compatibility check (null, one and four valid nodes); it is not an exploit test.

`scripts/staging/test_ffmpeg_pssh_cpu.py --run --candidate <candidate.json>
--evidence <new-directory>` compares the existing worker with its native
candidate in owned, network-disabled containers. Valid synthetic MP4 fixtures
contain at most 1,024 PSSH records. Deterministic calls/record counts check that
repeated previous-list decoding is removed; CPU timing is diagnostic only.
The same binary checks exact bytes/order/roundtrips, codecpar ownership,
allocation refusal and retry, and copy followed by fragment/root parsing.
These checks do not claim capacity, malformed-media coverage or live acceptance.

The 13 October native candidate is recorded separately from the still-running
staging image. A local patch/build result does not update that worker or close
its deployment acceptance. See the dated readiness record for the tested image,
remaining limits, review and explicit rollout decision.

Source-built FFmpeg may be absent from an OS package scanner's findings. Keep the
signed-source, upstream patch/CVE mapping, exact native build configuration and
runtime inventory as separate evidence; an empty scanner result is not proof
that FFmpeg has no vulnerabilities. Real staging API/worker and media-delivery
acceptance remain separate deployment gates.
