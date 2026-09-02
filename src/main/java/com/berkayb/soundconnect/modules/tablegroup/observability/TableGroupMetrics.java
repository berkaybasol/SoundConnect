package com.berkayb.soundconnect.modules.tablegroup.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bounded-cardinality business metrics for TableGroup operations. */
@Component
@RequiredArgsConstructor
public class TableGroupMetrics {

	private static final String LIFECYCLE_METRIC = "soundconnect.tablegroup.lifecycle.total";
	private final MeterRegistry meterRegistry;

	public void created() { lifecycle("created"); }
	public void joinRequested() { lifecycle("join_requested"); }
	public void joinApproved() { lifecycle("join_approved"); }
	public void joinRejected() { lifecycle("join_rejected"); }
	public void participantLeft() { lifecycle("participant_left"); }
	public void participantKicked() { lifecycle("participant_kicked"); }
	public void cancelled() { lifecycle("cancelled"); }
	public void expired() { lifecycle("expired"); }
	public void expiryTransitionFailed() { lifecycle("expiry_failed"); }

	public void messageSent() {
		meterRegistry.counter("soundconnect.tablegroup.chat.messages.total").increment();
	}

	public void realtimePublishFailed() {
		meterRegistry.counter("soundconnect.tablegroup.chat.realtime.failures.total").increment();
	}

	public void gameCreated() { gameLifecycle("created"); }
	public void gameStarted() { gameLifecycle("started"); }
	public void gameCompleted() { gameLifecycle("completed"); }
	public void gameCancelled() { gameLifecycle("cancelled"); }
	public void gameActionSubmitted() {
		meterRegistry.counter("soundconnect.tablegroup.game.actions.total").increment();
	}
	public void gameDeadlineTransitionFailed() {
		meterRegistry.counter("soundconnect.tablegroup.game.deadline.failures.total").increment();
	}

	public void unreadCacheFailed(String operation) {
		meterRegistry.counter(
				"soundconnect.tablegroup.chat.unread.failures.total",
				"operation",
				operation
		).increment();
	}

	private void lifecycle(String action) {
		meterRegistry.counter(LIFECYCLE_METRIC, "action", action).increment();
	}

	private void gameLifecycle(String action) {
		meterRegistry.counter(
				"soundconnect.tablegroup.game.lifecycle.total",
				"action",
				action
		).increment();
	}
}
