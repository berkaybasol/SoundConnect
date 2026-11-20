FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS builder

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar mediaWorkerBootJar --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m" -x test

FROM eclipse-temurin:21-jre-alpine-3.24@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime-base

RUN apk upgrade --no-cache \
    && grep -qx 'ping:x:999:' /etc/group \
    && delgroup ping \
    && addgroup -S -g 999 soundconnect \
    && adduser -S -D -H -u 999 -G soundconnect -h /app -s /sbin/nologin soundconnect

WORKDIR /app

# The official signed source is compiled here; no third-party FFmpeg binary or
# cross-distribution/edge package is copied into the runtime image.
FROM runtime-base AS ffmpeg-builder
RUN apk add --no-cache build-base pkgconf nasm curl gnupg tar xz patch \
    x264-dev dav1d-dev libwebp-dev zlib-dev
COPY docker/ffmpeg/build-ffmpeg.sh docker/ffmpeg/ffmpeg-release-key.asc \
    docker/ffmpeg/0001-encryption-info-iterative-free.patch \
    docker/ffmpeg/0002-encryption-info-linear-append.patch /build/
RUN /bin/sh /build/build-ffmpeg.sh

# This is the only image target containing native decoders. It has no HTTP port
# and starts the explicit allow-listed, no-web Spring context.
FROM runtime-base AS media-worker

RUN apk add --no-cache x264-libs dav1d libwebp libwebpdemux libwebpmux zlib
COPY --from=ffmpeg-builder /native/usr/local/ /usr/local/

COPY docker/media-worker/healthcheck.sh /usr/local/bin/media-worker-healthcheck
RUN chmod 0555 /usr/local/bin/media-worker-healthcheck

COPY --from=builder --chown=soundconnect:soundconnect /workspace/build/libs/soundconnect-media-worker.jar /app/soundconnect-media-worker.jar

USER soundconnect

ENV SPRING_PROFILES_ACTIVE=prod,media-worker \
    SOUNDCONNECT_MEDIA_WORKER_HEALTH_MAX_AGE_SECONDS=60

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD ["/usr/local/bin/media-worker-healthcheck"]

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/soundconnect-media-worker.jar"]

# The API target intentionally does not contain FFmpeg/FFprobe.
FROM runtime-base AS api

RUN apk add --no-cache curl

COPY --from=builder --chown=soundconnect:soundconnect /workspace/build/libs/soundconnect-api.jar /app/soundconnect-api.jar

USER soundconnect

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/liveness > /dev/null || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/soundconnect-api.jar"]
