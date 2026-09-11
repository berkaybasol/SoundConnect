package com.berkayb.soundconnect.tools.simulation.state;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SimulationWorldStateStoreTest {

	@TempDir
	Path directory;

	private final SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
			.loadDefault();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T20:15:30Z"), ZoneOffset.UTC);

	@Test
	void freshPersistsAnchorsAndResumeLoadsThemExactly() {
		SimulationProperties properties = properties(SimulationMode.FRESH);
		SimulationWorldStateStore store = store(properties);

		SimulationWorldState fresh = store.initializeOrLoad(manifest);
		properties.setMode(SimulationMode.RESUME);
		SimulationWorldState resumed = store.initializeOrLoad(manifest);

		assertThat(resumed).isEqualTo(fresh);
		assertThat(resumed.collabAnchor()).isEqualTo(Instant.parse("2026-09-11T20:15:30Z"));
		assertThat(resumed.eventAnchor()).isEqualTo(LocalDate.of(2026, 9, 11));
		assertThat(store.statePath(manifest)).exists();
	}

	@Test
	void resumeFailsClosedWithoutAFreshState() {
		SimulationProperties properties = properties(SimulationMode.RESUME);

		assertThatThrownBy(() -> store(properties).initializeOrLoad(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("earlier FRESH run");
	}

	@Test
	void pauseCannotCreateOrReadState() {
		SimulationProperties properties = properties(SimulationMode.PAUSE);

		assertThatThrownBy(() -> store(properties).initializeOrLoad(manifest))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("PAUSE");
	}

	private SimulationWorldStateStore store(SimulationProperties properties) {
		return new SimulationWorldStateStore(
				mock(SimulationRuntimeGuard.class), properties, new ObjectMapper(), clock);
	}

	private SimulationProperties properties(SimulationMode mode) {
		SimulationProperties properties = new SimulationProperties();
		properties.setMode(mode);
		properties.setReportDirectory(directory);
		return properties;
	}
}
