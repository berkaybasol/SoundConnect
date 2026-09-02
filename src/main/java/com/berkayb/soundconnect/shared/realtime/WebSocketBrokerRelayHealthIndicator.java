package com.berkayb.soundconnect.shared.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.*;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Makes loss of the horizontally shared STOMP broker visible to readiness. */
@Component("webSocketBrokerRelayHealth")
public class WebSocketBrokerRelayHealthIndicator implements HealthIndicator {
	private final boolean relayEnabled;
	private final AtomicBoolean available = new AtomicBoolean(false);

	public WebSocketBrokerRelayHealthIndicator(
			@Value("${app.websocket.broker-relay.enabled:false}") boolean relayEnabled
	) {
		this.relayEnabled = relayEnabled;
	}

	@EventListener
	public void onBrokerAvailability(BrokerAvailabilityEvent event) {
		available.set(event.isBrokerAvailable());
	}

	@Override
	public Health health() {
		if (!relayEnabled) {
			return Health.up().withDetail("mode", "simple-broker").build();
		}
		return available.get()
				? Health.up().withDetail("mode", "broker-relay").build()
				: Health.down().withDetail("mode", "broker-relay")
						.withDetail("reason", "relay-unavailable").build();
	}
}
