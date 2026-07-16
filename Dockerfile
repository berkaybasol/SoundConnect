FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar mediaWorkerBootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy AS runtime-base

RUN groupadd --system soundconnect \
    && useradd --system --gid soundconnect --home-dir /app --shell /usr/sbin/nologin soundconnect

WORKDIR /app

# This is the only image target containing native decoders. It has no HTTP port
# and starts the explicit allow-listed, no-web Spring context.
FROM runtime-base AS media-worker

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

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

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder --chown=soundconnect:soundconnect /workspace/build/libs/soundconnect-api.jar /app/soundconnect-api.jar

USER soundconnect

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/liveness > /dev/null || exit 1

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/soundconnect-api.jar"]
