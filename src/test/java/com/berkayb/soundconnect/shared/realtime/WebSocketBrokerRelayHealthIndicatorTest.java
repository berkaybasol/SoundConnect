package com.berkayb.soundconnect.shared.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketBrokerRelayHealthIndicatorTest {
	@Test
	void relayModeIsDownUntilBrokerReportsAvailability() {
		var indicator = new WebSocketBrokerRelayHealthIndicator(true);
		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

		indicator.onBrokerAvailability(new BrokerAvailabilityEvent(true, this));

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void localSimpleBrokerModeDoesNotRequireExternalRelay() {
		assertThat(new WebSocketBrokerRelayHealthIndicator(false).health().getStatus())
				.isEqualTo(Status.UP);
	}
}
