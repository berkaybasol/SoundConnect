package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SimulationFileStorageClientTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void opaqueSingleUseUploadSupportsRealConditionalSnapshotAndPromotion() throws Exception {
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		MutableClock clock = new MutableClock(Instant.parse("2026-09-11T10:00:00Z"));
		SimulationFileStorageClient storage = storage(guard, clock);
		byte[] bytes = new SimulationMediaFixtures().image().bytes();
		String uploadKey = "quarantine/media/00000000-0000-0000-0000-000000000001/source.png";

		String capability = storage.createPresignedPutUrl(uploadKey, "image/png", bytes.length);

		assertThat(capability).startsWith("simulation-upload://local/");
		assertThat(capability).doesNotContain("quarantine").doesNotContain("source.png");
		storage.upload(capability, "image/png", bytes);
		assertThatThrownBy(() -> storage.upload(capability, "image/png", bytes))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("already consumed");

		var mutableMetadata = storage.getObjectMetadata(uploadKey).orElseThrow();
		assertThat(mutableMetadata.sizeBytes()).isEqualTo(bytes.length);
		assertThat(mutableMetadata.contentType()).isEqualTo("image/png");
		assertThat(mutableMetadata.eTag()).isNotBlank();

		String immutableKey = StorageObjectKeys.immutableKeyForUpload(uploadKey, UUID.randomUUID());
		assertThatThrownBy(() -> storage.copyUploadToImmutable(uploadKey, immutableKey, "\"wrong\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ETag mismatch");
		storage.copyUploadToImmutable(uploadKey, immutableKey, mutableMetadata.eTag());
		var immutableMetadata = storage.getObjectMetadata(immutableKey).orElseThrow();
		assertThat(immutableMetadata).isEqualTo(mutableMetadata);

		String publicKey = StorageObjectKeys.publicKeyForVerified(immutableKey);
		storage.promoteVerifiedObject(
				immutableKey, publicKey, "image/png", "public, max-age=300", immutableMetadata.eTag());
		assertThat(storage.getObjectMetadata(publicKey).orElseThrow().contentType())
				.isEqualTo("image/png");
		try (var stream = storage.getObjectStream(publicKey)) {
			assertThat(stream.readAllBytes()).isEqualTo(bytes);
		}
		assertThat(storage.publicUrl(publicKey))
				.startsWith("http://10.0.2.2:8080/api/v1/public/simulation-media/")
				.doesNotContain(publicKey);
		verify(guard, org.mockito.Mockito.atLeastOnce()).assertRuntimeAllowed();
	}

	@Test
	void expiredOrMetadataMismatchedCapabilityCannotWrite() {
		MutableClock clock = new MutableClock(Instant.parse("2026-09-11T10:00:00Z"));
		SimulationFileStorageClient storage = storage(mock(SimulationRuntimeGuard.class), clock);
		byte[] bytes = new SimulationMediaFixtures().audio().bytes();
		String key = "quarantine/media/00000000-0000-0000-0000-000000000002/source.wav";

		String wrongMetadata = storage.createPresignedPutUrl(key, "audio/wav", bytes.length);
		assertThatThrownBy(() -> storage.upload(wrongMetadata, "audio/wav", new byte[]{1}))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("signed metadata");
		assertThat(storage.getObjectMetadata(key)).isEmpty();

		String expired = storage.createPresignedPutUrl(key, "audio/wav", bytes.length);
		clock.advance(Duration.ofMinutes(16));
		assertThatThrownBy(() -> storage.upload(expired, "audio/wav", bytes))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("expired");
		assertThat(storage.getObjectMetadata(key)).isEmpty();
	}

	@Test
	void pathTraversalAndSymlinkEscapeAreRejected() throws IOException {
		SimulationFileStorageClient storage = storage(
				mock(SimulationRuntimeGuard.class),
				new MutableClock(Instant.parse("2026-09-11T10:00:00Z")));

		assertThatThrownBy(() -> storage.putBytes(
				new byte[]{1}, "media/../outside", "image/png", "no-store"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unsafe");

		Path outside = temporaryDirectory.resolve("outside");
		Files.createDirectories(outside);
		Path publicRoot = storage.storageRootForTesting().resolve("public");
		Path link = publicRoot.resolve("linked");
		try {
			Files.createSymbolicLink(link, outside);
		} catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
			assumeTrue(false, "Symbolic links are not available in this test environment");
		}
		assertThatThrownBy(() -> storage.putBytes(
				new byte[]{1}, "linked/escape.png", "image/png", "no-store"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("symbolic link");
		assertThatThrownBy(storage::resetStorageForFresh)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("unsafe path");
		assertThat(Files.exists(outside.resolve("escape.png"))).isFalse();
	}

	@Test
	void opaquePublicCapabilitiesSurviveRestartAndFreshResetRevokesThem() {
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		MutableClock clock = new MutableClock(Instant.parse("2026-09-11T10:00:00Z"));
		SimulationFileStorageClient first = storage(guard, clock);
		byte[] bytes = new SimulationMediaFixtures().image().bytes();
		String key = "media/restart/source.png";
		first.putBytes(bytes, key, "image/png", "public, max-age=60");
		String firstUrl = first.publicUrl(key);
		String capability = java.net.URI.create(firstUrl).getPath()
				.substring(SimulationFileStorageClient.PUBLIC_ROUTE.length() + 1);

		SimulationFileStorageClient restarted = storage(guard, clock);

		assertThat(restarted.publicUrl(key)).isEqualTo(firstUrl);
		assertThat(restarted.readPublicCapability(capability).orElseThrow().bytes())
				.isEqualTo(bytes);

		restarted.resetStorageForFresh();

		assertThat(restarted.readPublicCapability(capability)).isEmpty();
		assertThat(restarted.getObjectMetadata(key)).isEmpty();
	}

	@Test
	void storageResetIsRejectedOutsideFreshMode() {
		SimulationFileStorageClient storage = new SimulationFileStorageClient(
				mock(SimulationRuntimeGuard.class),
				temporaryDirectory.resolve("resume-reports"),
				new MutableClock(Instant.parse("2026-09-11T10:00:00Z")),
				Duration.ofMinutes(15),
				Duration.ofMinutes(5),
				"http://10.0.2.2:8080",
				8L * 1024L * 1024L,
				4_096,
				SimulationMode.RESUME);

		assertThatThrownBy(storage::resetStorageForFresh)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("FRESH mode");
	}

	@Test
	void constructorRefusesToClaimPreexistingUnownedStorageContents() throws IOException {
		Path reports = temporaryDirectory.resolve("unowned-reports");
		Path unknown = reports.resolve("media-storage-v1").resolve("private").resolve("keep.txt");
		Files.createDirectories(unknown.getParent());
		Files.writeString(unknown, "not-created-by-simulation-storage");

		assertThatThrownBy(() -> new SimulationFileStorageClient(
				mock(SimulationRuntimeGuard.class),
				reports,
				Clock.systemUTC(),
				Duration.ofMinutes(15),
				Duration.ofMinutes(5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Refusing to claim");
		assertThat(unknown).hasContent("not-created-by-simulation-storage");
	}

	@Test
	void retainedObjectSurvivesBoundedPrefixDeletionAndDownload() throws IOException {
		SimulationFileStorageClient storage = storage(
				mock(SimulationRuntimeGuard.class),
				new MutableClock(Instant.parse("2026-09-11T10:00:00Z")));
		byte[] bytes = new SimulationMediaFixtures().image().bytes();
		String retained = "media/example/attempts/one/source.png";
		String removed = "media/example/attempts/two/source.png";
		storage.putBytes(bytes, retained, "image/png", "public, max-age=60");
		storage.putBytes(bytes, removed, "image/png", "public, max-age=60");

		storage.deleteFolderExcept("media/example", retained);

		assertThat(storage.getObjectMetadata(retained)).isPresent();
		assertThat(storage.getObjectMetadata(removed)).isEmpty();
		Path downloaded = temporaryDirectory.resolve("downloaded.png");
		storage.downloadToFile(retained, downloaded, Duration.ofSeconds(1));
		assertThat(Files.readAllBytes(downloaded)).isEqualTo(bytes);
	}

	@Test
	void everyStorageBoundaryRechecksRuntimeGuard() {
		SimulationRuntimeGuard guard = mock(SimulationRuntimeGuard.class);
		SimulationFileStorageClient storage = storage(
				guard, new MutableClock(Instant.parse("2026-09-11T10:00:00Z")));
		doThrow(new IllegalStateException("disabled")).when(guard).assertRuntimeAllowed();

		assertThatThrownBy(() -> storage.publicUrl("media/example/source.png"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("disabled");
	}

	private SimulationFileStorageClient storage(SimulationRuntimeGuard guard, Clock clock) {
		return new SimulationFileStorageClient(
				guard,
				temporaryDirectory.resolve("reports"),
				clock,
				Duration.ofMinutes(15),
				Duration.ofMinutes(5));
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
