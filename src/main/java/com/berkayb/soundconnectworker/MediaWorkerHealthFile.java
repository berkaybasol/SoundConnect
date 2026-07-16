package com.berkayb.soundconnectworker;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Non-HTTP readiness contract for the no-web worker container. The marker
 * contains its last successful DB/Rabbit probe epoch; the container check
 * rejects missing, malformed, future, and stale values.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
		prefix = "soundconnect.media-worker",
		name = "health-file-enabled",
		havingValue = "true",
		matchIfMissing = true
)
@Slf4j
public class MediaWorkerHealthFile {

	private final MediaWorkerDependencyHealthIndicator dependencyHealth;
	private final Path marker;

	public MediaWorkerHealthFile(
			MediaWorkerDependencyHealthIndicator dependencyHealth,
			@Value("${soundconnect.media-worker.health-file:/tmp/soundconnect-media-worker.ready}") String marker
	) {
		this.dependencyHealth = dependencyHealth;
		this.marker = Path.of(marker).toAbsolutePath().normalize();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		refresh();
	}

	@Scheduled(
			initialDelayString = "${soundconnect.media-worker.health-initial-delay:PT15S}",
			fixedDelayString = "${soundconnect.media-worker.health-interval:PT15S}"
	)
	public void refresh() {
		try {
			if (Status.UP.equals(dependencyHealth.health().getStatus())) {
				writeMarker();
			} else {
				Files.deleteIfExists(marker);
			}
		} catch (RuntimeException | IOException unhealthy) {
			deleteMarkerBestEffort();
			log.warn("[media-worker] readiness check failed type={}",
					unhealthy.getClass().getSimpleName());
		}
	}

	private void writeMarker() throws IOException {
		Path parent = marker.getParent();
		if (parent == null) throw new IOException("Worker health marker requires a parent directory");
		Files.createDirectories(parent);
		Path temporary = parent.resolve(marker.getFileName() + ".tmp");
		Files.writeString(
				temporary,
				Long.toString(Instant.now().getEpochSecond()) + "\n",
				StandardCharsets.US_ASCII);
		try {
			Files.move(temporary, marker,
					StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@PreDestroy
	void deleteMarkerBestEffort() {
		try {
			Files.deleteIfExists(marker);
		} catch (IOException ignored) {
			// Container exit remains the authoritative liveness signal.
		}
	}
}
