#!/bin/sh
set -eu
umask 022

# Exact upstream stable release, verified before extraction or compilation.
version=9.0.2
source_sha256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
fingerprint=FCF986EA15E6E293A5644F10B4322F04D67658D8
mkdir -p /build/source /build/gnupg /native
chmod 0700 /build/gnupg
export GNUPGHOME=/build/gnupg
cd /build/source
curl --fail --silent --show-error --location --max-time 180 --proto '=https' \
    -o source.tar.xz "https://ffmpeg.org/releases/ffmpeg-${version}.tar.xz"
curl --fail --silent --show-error --location --max-time 60 --proto '=https' \
    -o source.tar.xz.asc "https://ffmpeg.org/releases/ffmpeg-${version}.tar.xz.asc"
printf '%s  source.tar.xz\n' "$source_sha256" | sha256sum -c -
gpg --batch --no-auto-key-retrieve --import /build/ffmpeg-release-key.asc
gpg --batch --no-auto-key-retrieve --status-fd 1 --verify source.tar.xz.asc source.tar.xz > signature.status
awk -v expected="$fingerprint" '
  $1 == "[GNUPG:]" && $2 == "VALIDSIG" { if ($3 != expected) exit 1; valid++ }
  $2 ~ /^(BADSIG|ERRSIG|EXPSIG|EXPKEYSIG|REVKEYSIG|NO_PUBKEY)$/ { bad=1 }
  END { if (valid != 1 || bad) exit 1 }
' signature.status
tar -xf source.tar.xz --strip-components=1

# Retain the reviewed iterative-free proposal backport as a separate patch.
printf '%s  %s\n' \
    da459d3113d590cffbcea6f14ad55de20a32869cf8979eea17ab3bf83e2641ba libavutil/encryption_info.c \
    6d11e6c00d266b0cdc8f1844ad95718f8c3c4599f84f0b3aeb64ae38a103f386 /build/0001-encryption-info-iterative-free.patch \
    | sha256sum -c -
patch --batch --fuzz=0 -p1 < /build/0001-encryption-info-iterative-free.patch
printf '%s  libavutil/encryption_info.c\n' \
    8d5f7a59862c845361ab0e6b283f9d95e99771737572e56e89d064489b8cfd99 | sha256sum -c -

# Local CPU backport of PR 24593's append API + MOV proposal, with cache
# pointer/capacity committed together on success. Upstream merge is unverified.
# Do not advertise master API version 61.11 on stable 9.0.2 (61.1.102).
printf '%s  %s\n' \
    9606997db04e096efb3c382cca8c1f87c2b2c75deb38e1c1fe9b1af421046aba /build/0002-encryption-info-linear-append.patch \
    ed2bd65ec4a605be90223dce87ae3086829990e89691f0c25896c4e66ba116a5 libavformat/isom.h \
    533674687505d25db7176cf876036f600e6d8cb25079ffa6b28b4a8e8266eae1 libavformat/mov.c \
    8d5f7a59862c845361ab0e6b283f9d95e99771737572e56e89d064489b8cfd99 libavutil/encryption_info.c \
    ccc3a4a889b8a3c5aaf37b9fb2407bcdf23a065487c7cba718518a517c463b18 libavutil/encryption_info.h \
    7bdefd94d46595828045928a4ecb6a8056b7632688eaaaafd02c8667edb56ac4 libavutil/tests/encryption_info.c \
    | sha256sum -c -
patch --batch --fuzz=0 -p1 < /build/0002-encryption-info-linear-append.patch
printf '%s  %s\n' \
    823d27a6804f115860525c9154ec41e0ce817a67234cf888fc52c8cb1a135938 libavformat/isom.h \
    b7e07fe88b4a31a367c71596be3559e216ae460dbd6acdee8dcc7ac5f30e8c6a libavformat/mov.c \
    17505745d726433d4d0aa4740809caaa38e8befd640f75806ecaa63ac896b0a1 libavutil/encryption_info.c \
    3046eff541dcc83842ee16db60084fb6ec0bec8ee4c7c483d340217d57b403c1 libavutil/encryption_info.h \
    336c641861b4b735cbbf1dbb3bcdea8a90db6e43d19107d43eb0170d2f80de67 libavutil/tests/encryption_info.c \
    | sha256sum -c -

# All built-in media decoders/filters/muxers remain enabled. External libraries
# are explicit: software AV1 (dav1d), H.264 encode, WebP and PNG/zlib. No aom,
# zvbi, librist, OpenSSL or device/network dependency is auto-discovered.
# lavfi is only a virtual filter input, used by real codec acceptance fixtures.
./configure --prefix=/usr/local --disable-autodetect --disable-doc --disable-debug \
    --disable-static --enable-shared --enable-gpl --enable-libx264 \
    --enable-libdav1d --enable-libwebp --enable-zlib \
    --disable-network --disable-protocols --enable-protocol=file,pipe \
    --disable-muxer=matroska,webm,webm_chunk,webm_dash_manifest \
    --disable-indevs --enable-indev=lavfi --disable-outdevs \
    --disable-ffplay --extra-ldflags='-Wl,-rpath,/usr/local/lib'
make -j2
make fate-encryption-info > /build/encryption-info-fate.log 2>&1
cat /build/encryption-info-fate.log
make DESTDIR=/native install
mkdir -p /native/usr/local/share/soundconnect-ffmpeg
cp COPYING.GPLv2 LICENSE.md signature.status /native/usr/local/share/soundconnect-ffmpeg/
printf 'version=%s\nsource_sha256=%s\nsigning_fingerprint=%s\n' \
    "$version" "$source_sha256" "$fingerprint" \
    > /native/usr/local/share/soundconnect-ffmpeg/source.txt
cp ffbuild/config.mak /native/usr/local/share/soundconnect-ffmpeg/build-config.mak
cp config.h config_components.h /native/usr/local/share/soundconnect-ffmpeg/
cp /build/0001-encryption-info-iterative-free.patch /native/usr/local/share/soundconnect-ffmpeg/
cp /build/0002-encryption-info-linear-append.patch /build/encryption-info-fate.log \
    /native/usr/local/share/soundconnect-ffmpeg/
printf 'commit=%s\npatch_sha256=%s\npatched_file_sha256=%s\n' \
    94924d045d5dce70f5557d400bb6d6b99c0ab96b \
    6d11e6c00d266b0cdc8f1844ad95718f8c3c4599f84f0b3aeb64ae38a103f386 \
    8d5f7a59862c845361ab0e6b283f9d95e99771737572e56e89d064489b8cfd99 \
    > /native/usr/local/share/soundconnect-ffmpeg/patch.txt
printf 'patched_file_scope=after-0001-before-0002\n' >> /native/usr/local/share/soundconnect-ffmpeg/patch.txt
{
    printf '%s\n' \
        'identity=soundconnect-9.0.2-pssh-append-v1' \
        'upstream_merge_verified=false' \
        'proposal_commits=abbd3cd2866dd5cc3491d99b19aa79f9287daf72,df40e69194187f8ab0db3558bf2a154d32fd0040' \
        'patch_sha256=9606997db04e096efb3c382cca8c1f87c2b2c75deb38e1c1fe9b1af421046aba' \
        'local_adjustment=commit-capacity-and-pointer-on-success;document-capacity-ownership' \
        'upstream_version_macros=unchanged'
    sha256sum libavformat/isom.h libavformat/mov.c libavutil/encryption_info.c \
        libavutil/encryption_info.h libavutil/tests/encryption_info.c
} > /native/usr/local/share/soundconnect-ffmpeg/cpu-patch.txt
find /native/usr/local -type f -name '*.a' -delete
