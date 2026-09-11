package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.storage.MediaContentSignatureValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationMediaFixturesTest {

	@Test
	void versionControlledFixturesMatchDeclaredProductionSignatures() throws Exception {
		SimulationMediaFixtures fixtures = new SimulationMediaFixtures();

		assertThat(MediaContentSignatureValidator.matches(
				fixtures.image().contentType(),
				new ByteArrayInputStream(fixtures.image().bytes()))).isTrue();
		assertThat(MediaContentSignatureValidator.matches(
				fixtures.audio().contentType(),
				new ByteArrayInputStream(fixtures.audio().bytes()))).isTrue();
		assertThat(fixtures.image().bytes()).isNotEmpty();
		assertThat(fixtures.audio().bytes()).isNotEmpty();
	}

	@Test
	void fixtureBytesAreDefensivelyCopied() {
		SimulationMediaFixtures fixtures = new SimulationMediaFixtures();
		byte[] first = fixtures.image().bytes();
		byte expected = first[0];

		first[0] = 0;

		assertThat(fixtures.image().bytes()[0]).isEqualTo(expected);
	}
}
