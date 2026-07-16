package com.berkayb.soundconnectworker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaWorkerHealthFileTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void writesSuccessfulProbeEpochAndDeletesItWhenDependencyTurnsDown() throws Exception {
		MediaWorkerDependencyHealthIndicator dependency =
				mock(MediaWorkerDependencyHealthIndicator.class);
		Path marker = temporaryDirectory.resolve("worker.ready");
		MediaWorkerHealthFile healthFile =
				new MediaWorkerHealthFile(dependency, marker.toString());

		long before = Instant.now().getEpochSecond();
		when(dependency.health()).thenReturn(Health.up().build());
		healthFile.refresh();
		long after = Instant.now().getEpochSecond();

		long writtenEpoch = Long.parseLong(Files.readString(marker).trim());
		assertThat(writtenEpoch).isBetween(before, after);

		when(dependency.health()).thenReturn(Health.down().build());
		healthFile.refresh();
		assertThat(marker).doesNotExist();
	}
}
