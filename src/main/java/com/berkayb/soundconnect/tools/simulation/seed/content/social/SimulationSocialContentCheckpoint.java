package com.berkayb.soundconnect.tools.simulation.seed.content.social;

import com.berkayb.soundconnect.modules.event.audience.EventIntent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Credential-free identity ledger for the listener-authored social story.
 *
 * <p>Production aggregates in this slice use server-generated publication IDs.
 * Persisting those IDs is therefore the only safe way for RESUME and
 * FAST_FORWARD to verify the original story without invoking a create command.</p>
 */
public record SimulationSocialContentCheckpoint(
		int schemaVersion,
		int worldSchemaVersion,
		String worldId,
		long seed,
		String storyFingerprint,
		Instant createdAt,
		List<OverthinkingPublication> overthinkingPublications,
		List<TableGroupAggregate> tableGroups,
		List<TableGroupPublication> tableGroupPublications,
		List<EventPublication> eventPublications
) {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public SimulationSocialContentCheckpoint {
		worldId = requireText(worldId, "worldId");
		storyFingerprint = requireText(storyFingerprint, "storyFingerprint");
		Objects.requireNonNull(createdAt, "createdAt");
		overthinkingPublications = copy(overthinkingPublications, "overthinkingPublications");
		tableGroups = copy(tableGroups, "tableGroups");
		tableGroupPublications = copy(tableGroupPublications, "tableGroupPublications");
		eventPublications = copy(eventPublications, "eventPublications");
	}

	public record OverthinkingPublication(
			String logicalKey,
			String ownerAccountKey,
			UUID ownerUserId,
			UUID sourcePostId,
			UUID profileShareId
	) {
		public OverthinkingPublication {
			logicalKey = requireText(logicalKey, "logicalKey");
			ownerAccountKey = requireText(ownerAccountKey, "ownerAccountKey");
			requireId(ownerUserId, "ownerUserId");
			requireId(sourcePostId, "sourcePostId");
			requireId(profileShareId, "profileShareId");
		}
	}

	public record TableGroupAggregate(
			String logicalKey,
			String ownerAccountKey,
			UUID ownerUserId,
			UUID tableGroupId,
			Instant meetingAt
	) {
		public TableGroupAggregate {
			logicalKey = requireText(logicalKey, "logicalKey");
			ownerAccountKey = requireText(ownerAccountKey, "ownerAccountKey");
			requireId(ownerUserId, "ownerUserId");
			requireId(tableGroupId, "tableGroupId");
			Objects.requireNonNull(meetingAt, "meetingAt");
		}
	}

	public record TableGroupPublication(
			String logicalKey,
			String publisherAccountKey,
			UUID publisherUserId,
			String tableLogicalKey,
			UUID tableGroupId,
			UUID profileShareId
	) {
		public TableGroupPublication {
			logicalKey = requireText(logicalKey, "logicalKey");
			publisherAccountKey = requireText(publisherAccountKey, "publisherAccountKey");
			requireId(publisherUserId, "publisherUserId");
			tableLogicalKey = requireText(tableLogicalKey, "tableLogicalKey");
			requireId(tableGroupId, "tableGroupId");
			requireId(profileShareId, "profileShareId");
		}
	}

	public record EventPublication(
			String logicalKey,
			String listenerAccountKey,
			UUID listenerUserId,
			String eventStoryKey,
			UUID eventId,
			UUID profilePostId,
			EventIntent intent,
			String note,
			long version
	) {
		public EventPublication {
			logicalKey = requireText(logicalKey, "logicalKey");
			listenerAccountKey = requireText(listenerAccountKey, "listenerAccountKey");
			requireId(listenerUserId, "listenerUserId");
			eventStoryKey = requireText(eventStoryKey, "eventStoryKey");
			requireId(eventId, "eventId");
			requireId(profilePostId, "profilePostId");
			Objects.requireNonNull(intent, "intent");
			note = requireText(note, "note");
			if (version < 1) throw new IllegalArgumentException("version must be positive");
		}
	}

	private static <T> List<T> copy(List<T> values, String field) {
		Objects.requireNonNull(values, field);
		if (values.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException(field + " cannot contain null values");
		}
		return List.copyOf(values);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
		return value.trim();
	}

	private static void requireId(UUID value, String field) {
		if (value == null || value.equals(new UUID(0L, 0L))) {
			throw new IllegalArgumentException(field + " is required");
		}
	}
}
