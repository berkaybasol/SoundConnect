package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.telemetry.MediaWorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class ImageThumbnailServiceTest {

	@Mock StorageClient storageClient;
	@Mock ImageThumbnailProcessor thumbnailProcessor;
	SimpleMeterRegistry metrics;
	@BeforeEach void createMetrics() { metrics = new SimpleMeterRegistry(); }
	@AfterEach void closeMetrics() { metrics.close(); }

	@Test
	void generateAndStore_usesDeterministicPublicKeyAndPersistsDimensions() throws Exception {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.jpg";
		String thumbnailKey = "media/" + assetId + "/thumbnail.jpg";
		when(storageClient.publicUrl(thumbnailKey)).thenReturn("https://cdn.test/" + thumbnailKey);
		when(thumbnailProcessor.createThumbnail(any(Path.class), any(Path.class)))
				.thenReturn(new ProcessedImageThumbnail(2296, 4080, 540, 960));
		ImageThumbnailService service = new ImageThumbnailService(
				storageClient, thumbnailProcessor, new MediaImageVariantProperties());
		service.setTelemetry(new MediaWorkerMetrics(metrics));

		ImageThumbnailResult result = service.generateAndStoreDetached(assetId, sourceKey);

		assertThat(result.thumbnailKey()).isEqualTo(thumbnailKey);
		assertThat(result.thumbnailUrl()).isEqualTo("https://cdn.test/" + thumbnailKey);
		assertThat(result.sourceWidth()).isEqualTo(2296);
		assertThat(result.sourceHeight()).isEqualTo(4080);
		assertThat(result.thumbnailWidth()).isEqualTo(540);
		assertThat(result.thumbnailHeight()).isEqualTo(960);
		verify(storageClient).downloadToFile(
				any(String.class), any(Path.class),
				org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)));
		ArgumentCaptor<Path> fileCaptor = ArgumentCaptor.forClass(Path.class);
		verify(storageClient).putFile(
				fileCaptor.capture(), org.mockito.ArgumentMatchers.eq(thumbnailKey),
				org.mockito.ArgumentMatchers.eq("image/jpeg"),
				org.mockito.ArgumentMatchers.eq(
						"public, max-age=300, s-maxage=3600, stale-while-revalidate=60"));
		assertThat(fileCaptor.getValue().getFileName().toString()).isEqualTo("thumbnail.jpg");
		assertOutcome("success");
	}

	@Test
	void thumbnailKeyFor_rejectsProtectedSource() {
		assertThatThrownBy(() -> ImageThumbnailService.thumbnailKeyFor(
				"protected/media/id/source.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void protectedThumbnail_staysInImmutableAttemptWithoutPublicUrlOrCache() throws Exception {
		UUID assetId = UUID.randomUUID();
		String source = "protected/private-verified/media/" + assetId + "/attempts/"
				+ UUID.randomUUID() + "/source.png";
		String key = source.replace("source.png", "thumbnail.jpg");
		when(thumbnailProcessor.createThumbnail(any(Path.class), any(Path.class)))
				.thenReturn(new ProcessedImageThumbnail(2000, 1000, 960, 480));
		ImageThumbnailService service = new ImageThumbnailService(
				storageClient, thumbnailProcessor, new MediaImageVariantProperties());

		ImageThumbnailResult result = service.generateAndStoreDetached(assetId, source);

		assertThat(result.thumbnailKey()).isEqualTo(key);
		assertThat(result.thumbnailUrl()).isNull();
		verify(storageClient, never()).publicUrl(any());
		verify(storageClient).putFile(any(Path.class), org.mockito.ArgumentMatchers.eq(key),
				org.mockito.ArgumentMatchers.eq("image/jpeg"),
				org.mockito.ArgumentMatchers.eq("private,no-store,max-age=0"));
	}

	@Test
	void protectedThumbnail_rejectsMutablePublicAndTraversalSources() {
		for (String unsafe : java.util.List.of("protected/media/id/source.jpg", "media/id/source.jpg",
				"protected/private-verified/media/id/../other/source.jpg")) {
			assertThatThrownBy(() -> ImageThumbnailService.protectedThumbnailKeyFor(unsafe))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test void interruptionPreservesOriginalExceptionAndReleasesObservedActiveJob() throws Exception {
		var interrupted = new InterruptedException("private-path-or-message");
		when(thumbnailProcessor.createThumbnail(any(Path.class), any(Path.class))).thenThrow(interrupted);
		var service = new ImageThumbnailService(storageClient, thumbnailProcessor, new MediaImageVariantProperties());
		service.setTelemetry(new MediaWorkerMetrics(metrics));
		assertThatThrownBy(() -> service.generateAndStoreDetached(UUID.randomUUID(), "verified/media/SECRET/source.jpg"))
				.isSameAs(interrupted);
		assertOutcome("interrupted");
		assertThat(metrics.getMeters().toString()).doesNotContain("SECRET", "private-path-or-message");
	}

	@Test void storageFailureRemainsFailureAndDoesNotReportSuccessfulThumbnail() throws Exception {
		var failure = new IllegalStateException("private storage details");
		doThrow(failure).when(storageClient).downloadToFile(any(String.class), any(Path.class), any(Duration.class));
		var service = new ImageThumbnailService(storageClient, thumbnailProcessor, new MediaImageVariantProperties());
		service.setTelemetry(new MediaWorkerMetrics(metrics));
		assertThatThrownBy(() -> service.generateAndStoreDetached(UUID.randomUUID(), "verified/media/SECRET/source.jpg"))
				.isSameAs(failure);
		assertOutcome("failure");
		assertThat(metrics.get("soundconnect.worker.job.duration").tags("workflow", "image_thumbnail", "outcome", "success").timer().count()).isZero();
	}

	@Test void lazyCleanupFailurePreservesExceptionAndDoesNotRecordSuccessfulJob(@TempDir Path workDirectory) throws Exception {
		var failure = new UncheckedIOException(new IOException("private cleanup path"));
		when(thumbnailProcessor.createThumbnail(any(Path.class), any(Path.class)))
				.thenReturn(new ProcessedImageThumbnail(2000, 1000, 960, 480));
		var service = new ImageThumbnailService(storageClient, thumbnailProcessor, new MediaImageVariantProperties());
		service.setTelemetry(new MediaWorkerMetrics(metrics));
		try (var files = mockStatic(Files.class)) {
			files.when(() -> Files.createTempDirectory(anyString())).thenReturn(workDirectory);
			files.when(() -> Files.walk(workDirectory)).thenReturn(Stream.of(workDirectory).map(path -> { throw failure; }));
			assertThatThrownBy(() -> service.generateAndStoreDetached(UUID.randomUUID(), "verified/media/SECRET/source.jpg"))
					.isSameAs(failure);
		}
		verify(storageClient).putFile(any(Path.class), anyString(), anyString(), anyString());
		assertOutcome("failure");
		assertThat(metrics.get("soundconnect.worker.job.duration").tags("workflow", "image_thumbnail", "outcome", "success").timer().count()).isZero();
		assertThat(metrics.getMeters().toString()).doesNotContain("SECRET", "private cleanup path");
	}

	private void assertOutcome(String outcome) {
		assertThat(metrics.get("soundconnect.worker.job.duration").tags("workflow", "image_thumbnail", "outcome", outcome).timer().count()).isEqualTo(1);
		assertThat(metrics.get("soundconnect.worker.job.active").tag("workflow", "image_thumbnail").gauge().value()).isZero();
	}
}
