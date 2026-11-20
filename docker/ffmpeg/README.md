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

## Verification

The FFmpeg image build checks the signed source archive and local patches and runs the upstream encryption-info test. Build configuration, licenses, and source and patch hashes are retained in the worker image under `/usr/local/share/soundconnect-ffmpeg`.

Staging operators separately checked the worker with a real video, verified playback artifacts, and removed the test data. See the [Stage 01 acceptance record](../../docs/ready-for-prod/asamalar/01/KAPANIS_01_8_20261121.md) for the scope of that acceptance. The deployment tools and environment-specific records stay with the operators. The planned release-candidate test will measure concurrent video processing and other application traffic.
