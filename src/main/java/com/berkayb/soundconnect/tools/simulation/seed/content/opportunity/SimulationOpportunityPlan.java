package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Deterministic opportunity stories derived exclusively from the world manifest. */
public record SimulationOpportunityPlan(
		List<CollabStory> collabs,
		List<EventStory> events
) {
	public SimulationOpportunityPlan {
		collabs = collabs == null ? List.of() : List.copyOf(collabs);
		events = events == null ? List.of() : List.copyOf(events);
	}

	public enum CollabFinalState {
		DRAFT,
		OPEN,
		CLOSED
	}

	public enum EventFinalState {
		VISIBLE,
		DELETED
	}

	public enum EventPerformerStory {
		NONE,
		MANUAL,
		MUSICIAN_PENDING,
		BAND_PENDING,
		MUSICIAN_ACCEPTED,
		BAND_ACCEPTED
	}

	public record Publisher(
			ProfileType profileType,
			String sourceKey,
			String representativeAccountKey
	) {
	}

	public record CollabStory(
			String key,
			UUID clientRequestId,
			Publisher publisher,
			String cityAccountKey,
			CollabCadence cadence,
			CollabWantedType wantedType,
			String instrumentName,
			CollabBranch branch,
			String customSpecialty,
			String title,
			String description,
			List<String> genres,
			Instant scheduledAt,
			Long feeAmountMinor,
			String currency,
			CollabFinalState finalState
	) {
		public CollabStory {
			genres = genres == null ? List.of() : List.copyOf(genres);
		}
	}

	public record EventStory(
			String key,
			String venueAccountKey,
			String title,
			String description,
			LocalDate eventDate,
			LocalTime startTime,
			LocalTime endTime,
			EventPerformerStory performerStory,
			String performerKey,
			String performerActorAccountKey,
			String manualPerformerName,
			boolean showOnProfile,
			EventFinalState finalState
	) {
	}
}
