package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SimulationMediaCheckpointStoreTest {

	@TempDir
	Path temporaryDirectory;

	private SimulationWorldManifest manifest;
	private SimulationMediaCheckpointStore store;
	private SimulationProperties properties;

	@BeforeEach
	void setUp() {
		manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setMode(SimulationMode.FRESH);
		properties.setCommonPassword("never-write-this-password");
		properties.setReportDirectory(temporaryDirectory.resolve("reports"));
		store = new SimulationMediaCheckpointStore(
				mock(SimulationRuntimeGuard.class),
				properties,
				new ObjectMapper(),
				Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void atomicallyRoundTripsCredentialFreeResultAndStableIdentityFingerprint() throws Exception {
		Map<String, UUID> users = Map.of("account-b", id(2), "account-a", id(1));
		Map<String, UUID> profiles = Map.of("account-a", id(3));
		Map<String, UUID> bands = Map.of("band-a", id(4));
		String fingerprint = store.identityFingerprint(manifest, users, profiles, bands);
		String reordered = store.identityFingerprint(
				manifest,
				Map.of("account-a", id(1), "account-b", id(2)),
				profiles,
				bands);
		SimulationMediaSeedResult result = new SimulationMediaSeedResult(
				Map.of("account-a", id(10)),
				List.of(id(21)),
				List.of(id(20)),
				List.of(new SimulationMediaSeedResult.MediaTarget(
						"profile-media-001", "account-a", id(11), id(20))),
				List.of(new SimulationMediaSeedResult.MediaTarget(
						"track-001", "account-a", id(12), id(21))));

		store.save(manifest, fingerprint, result);
		SimulationMediaCheckpoint loaded = store.loadIfPresent(manifest).orElseThrow();

		assertThat(fingerprint).hasSize(64).isEqualTo(reordered);
		assertThat(loaded.result()).isEqualTo(result);
		assertThat(store.matches(loaded, manifest, fingerprint)).isTrue();
		String serialized = Files.readString(
				store.checkpointPath(manifest), StandardCharsets.UTF_8);
		assertThat(serialized)
				.doesNotContain(properties.getCommonPassword())
				.doesNotContain("uploadUrl")
				.doesNotContain("accessToken");
	}

	@Test
	void changedIdentityAndMalformedCheckpointFailClosed() throws Exception {
		String fingerprint = store.identityFingerprint(
				manifest, Map.of("account-a", id(1)), Map.of(), Map.of());
		SimulationMediaSeedResult result = new SimulationMediaSeedResult(
				Map.of(), List.of(), List.of(), List.of(), List.of());
		store.save(manifest, fingerprint, result);
		SimulationMediaCheckpoint loaded = store.loadIfPresent(manifest).orElseThrow();

		String changed = store.identityFingerprint(
				manifest, Map.of("account-a", id(2)), Map.of(), Map.of());
		assertThat(store.matches(loaded, manifest, changed)).isFalse();

		Files.writeString(
				store.checkpointPath(manifest),
				"{\"schemaVersion\":1,\"schemaVersion\":1}",
				StandardCharsets.UTF_8);
		assertThatThrownBy(() -> store.loadIfPresent(manifest))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("cannot be read");
	}

	@Test
	void freshResetDeletesOnlyTheExactWorldCheckpointAndIsIdempotent() {
		String fingerprint = store.identityFingerprint(
				manifest, Map.of("account-a", id(1)), Map.of(), Map.of());
		store.save(
				manifest,
				fingerprint,
				new SimulationMediaSeedResult(
						Map.of(), List.of(), List.of(), List.of(), List.of()));
		Path checkpoint = store.checkpointPath(manifest);
		assertThat(checkpoint).exists();

		store.resetForFresh(manifest);
		store.resetForFresh(manifest);

		assertThat(checkpoint).doesNotExist();
	}

	@Test
	void checkpointResetFailsClosedOutsideFreshModeAndPreservesTheFile() {
		String fingerprint = store.identityFingerprint(
				manifest, Map.of("account-a", id(1)), Map.of(), Map.of());
		store.save(
				manifest,
				fingerprint,
				new SimulationMediaSeedResult(
						Map.of(), List.of(), List.of(), List.of(), List.of()));
		Path checkpoint = store.checkpointPath(manifest);
		properties.setMode(SimulationMode.RESUME);

		assertThatThrownBy(() -> store.resetForFresh(manifest))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("requires FRESH");
		assertThat(checkpoint).exists();
	}

	private static UUID id(long value) {
		return new UUID(0L, value);
	}
}
