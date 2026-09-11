package com.berkayb.soundconnect.tools.simulation.seed.reset;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationPropertiesFreshResetAuthorizationTest {

	@Test
	void allowsOnlyEnabledAcknowledgedFreshMode() {
		SimulationProperties properties = properties(true, SimulationMode.FRESH, true);

		assertThatCode(() -> authorization(properties).assertFreshResetAuthorized())
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsEveryMissingPartOfTheDestructiveContract() {
		assertRejected(properties(false, SimulationMode.FRESH, true));
		assertRejected(properties(true, SimulationMode.RESUME, true));
		assertRejected(properties(true, SimulationMode.FRESH, false));
	}

	private void assertRejected(SimulationProperties properties) {
		assertThatThrownBy(() -> authorization(properties).assertFreshResetAuthorized())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("enabled FRESH mode")
				.hasMessageContaining("acknowledgement");
	}

	private SimulationPropertiesFreshResetAuthorization authorization(SimulationProperties properties) {
		return new SimulationPropertiesFreshResetAuthorization(properties);
	}

	private SimulationProperties properties(boolean enabled, SimulationMode mode, boolean acknowledged) {
		SimulationProperties properties = new SimulationProperties();
		properties.setEnabled(enabled);
		properties.setMode(mode);
		properties.setDestructiveResetAcknowledged(acknowledged);
		return properties;
	}
}
