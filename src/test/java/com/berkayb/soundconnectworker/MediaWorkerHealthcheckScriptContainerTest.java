package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MediaWorkerHealthcheckScriptContainerTest {

	private static final Path HEALTHCHECK = Path.of(
			"docker", "media-worker", "healthcheck.sh").toAbsolutePath();

	@Container
	static final GenericContainer<?> SHELL = new GenericContainer<>(
			DockerImageName.parse("postgres:16.4-alpine"))
			.withCopyFileToContainer(MountableFile.forHostPath(HEALTHCHECK), "/healthcheck.sh")
			.withCommand("sh", "-c", "sleep 300");

	@Test
	void freshMarkerIsHealthyButStaleFutureAndMalformedMarkersAreNot() throws Exception {
		assertThat(execWithMarker("now=$(date +%s); printf '%s\\n' \"$now\"").getExitCode())
				.isZero();
		assertThat(execWithMarker("now=$(date +%s); old=$((now - 61)); printf '%s\\n' \"$old\"").getExitCode())
				.isNotZero();
		assertThat(execWithMarker("now=$(date +%s); future=$((now + 120)); printf '%s\\n' \"$future\"").getExitCode())
				.isNotZero();
		assertThat(execWithMarker("printf 'ready\\n'").getExitCode())
				.isNotZero();
	}

	@Test
	void workerImageHealthcheckExecutesFreshnessScript() throws Exception {
		String dockerfile = Files.readString(Path.of("Dockerfile"));
		assertThat(dockerfile)
				.contains("COPY docker/media-worker/healthcheck.sh /usr/local/bin/media-worker-healthcheck")
				.contains("CMD [\"/usr/local/bin/media-worker-healthcheck\"]")
				.doesNotContain("CMD test -f /tmp/soundconnect-media-worker.ready");
	}

	private org.testcontainers.containers.Container.ExecResult execWithMarker(String writer)
			throws Exception {
		return SHELL.execInContainer(
				"sh", "-c",
				writer + " > /tmp/worker.ready; "
						+ "SOUNDCONNECT_MEDIA_WORKER_HEALTH_FILE=/tmp/worker.ready "
						+ "SOUNDCONNECT_MEDIA_WORKER_HEALTH_MAX_AGE_SECONDS=60 "
						+ "sh /healthcheck.sh");
	}
}
