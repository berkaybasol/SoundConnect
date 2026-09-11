package com.berkayb.soundconnect.tools.simulation.report;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread-safe audit ledger shared by seed and future behavior phases.
 *
 * <p>Entries intentionally contain logical manifest keys rather than database identifiers or
 * credentials, which keeps reports stable and safe to attach to a bug.</p>
 */
public final class SimulationRunLedger {

	private static final int MAX_DETAIL_LENGTH = 1_024;

	private final String runId;
	private final String worldId;
	private final long seed;
	private final Clock clock;
	private final Instant startedAt;
	private final Map<String, Integer> planned = new LinkedHashMap<>();
	private final List<Entry> entries = new ArrayList<>();
	private long sequence;

	public SimulationRunLedger(String worldId, long seed, Clock clock) {
		this.worldId = requireText(worldId, "worldId");
		this.seed = seed;
		this.clock = Objects.requireNonNull(clock, "clock");
		this.startedAt = clock.instant();
		this.runId = this.worldId + "-" + this.startedAt.toEpochMilli() + "-"
				+ UUID.randomUUID().toString().substring(0, 8);
	}

	public synchronized void plan(String metric, int count) {
		String key = requireText(metric, "metric");
		if (count < 0) throw new IllegalArgumentException("planned count cannot be negative");
		if (planned.putIfAbsent(key, count) != null) {
			throw new IllegalStateException("planned metric already registered: " + key);
		}
	}

	public void succeeded(String phase, String action, String actorKey, String targetKey, String detail) {
		append(phase, action, actorKey, targetKey, Status.SUCCEEDED, detail, null);
	}

	public void skipped(String phase, String action, String actorKey, String targetKey, String reason) {
		append(phase, action, actorKey, targetKey, Status.SKIPPED, reason, null);
	}

	public void failed(String phase, String action, String actorKey, String targetKey, Throwable failure) {
		Objects.requireNonNull(failure, "failure");
		append(
				phase,
				action,
				actorKey,
				targetKey,
				Status.FAILED,
				failure.getMessage(),
				failure.getClass().getSimpleName()
		);
	}

	public synchronized Snapshot snapshot() {
		Map<Status, Long> totals = new LinkedHashMap<>();
		for (Status status : Status.values()) totals.put(status, 0L);
		for (Entry entry : entries) totals.compute(entry.status(), (ignored, count) -> count + 1L);
		return new Snapshot(
				runId,
				worldId,
				seed,
				startedAt,
				clock.instant(),
				Map.copyOf(planned),
				Map.copyOf(totals),
				List.copyOf(entries)
		);
	}

	private synchronized void append(
			String phase,
			String action,
			String actorKey,
			String targetKey,
			Status status,
			String detail,
			String errorType
	) {
		entries.add(new Entry(
				++sequence,
				clock.instant(),
				requireText(phase, "phase"),
				requireText(action, "action"),
				normalizeOptional(actorKey),
				normalizeOptional(targetKey),
				Objects.requireNonNull(status, "status"),
				bounded(detail),
				normalizeOptional(errorType)
		));
	}

	private static String bounded(String value) {
		String normalized = normalizeOptional(value);
		if (normalized == null || normalized.length() <= MAX_DETAIL_LENGTH) return normalized;
		return normalized.substring(0, MAX_DETAIL_LENGTH);
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String requireText(String value, String field) {
		String normalized = normalizeOptional(value);
		if (normalized == null) throw new IllegalArgumentException(field + " is required");
		return normalized;
	}

	public enum Status {
		SUCCEEDED,
		SKIPPED,
		FAILED
	}

	public record Entry(
			long sequence,
			Instant occurredAt,
			String phase,
			String action,
			String actorKey,
			String targetKey,
			Status status,
			String detail,
			String errorType
	) {
	}

	public record Snapshot(
			String runId,
			String worldId,
			long seed,
			Instant startedAt,
			Instant completedAt,
			Map<String, Integer> planned,
			Map<Status, Long> totals,
			List<Entry> entries
	) {
	}
}
