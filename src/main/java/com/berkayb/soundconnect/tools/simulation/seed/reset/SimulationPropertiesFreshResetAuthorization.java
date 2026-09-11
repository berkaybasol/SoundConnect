package com.berkayb.soundconnect.tools.simulation.seed.reset;

import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Authorizes a reset only for an enabled, explicitly acknowledged FRESH run. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class SimulationPropertiesFreshResetAuthorization
		implements SimulationFreshResetAuthorization {

	private final SimulationProperties properties;

	public SimulationPropertiesFreshResetAuthorization(SimulationProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	@Override
	public void assertFreshResetAuthorized() {
		if (!properties.isEnabled()
				|| properties.getMode() != SimulationMode.FRESH
				|| !properties.isDestructiveResetAcknowledged()) {
			throw new IllegalStateException(
					"Simulation database reset requires enabled FRESH mode and explicit acknowledgement");
		}
	}
}
