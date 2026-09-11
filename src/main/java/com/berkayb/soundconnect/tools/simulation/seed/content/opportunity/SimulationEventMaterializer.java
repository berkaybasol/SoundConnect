package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.enums.EventVenueApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventPerformerStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventStory;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Materializes only approved-venue-owned Events through production services. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationEventMaterializer {

	private static final String PHASE = "opportunity-event";

	private final SimulationRuntimeGuard runtimeGuard;
	private final EventService eventService;
	private final EventPerformerRequestService performerRequestService;
	private final Validator validator;

	public SimulationEventMaterializer(
			SimulationRuntimeGuard runtimeGuard,
			EventService eventService,
			EventPerformerRequestService performerRequestService,
			Validator validator
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.eventService = Objects.requireNonNull(eventService, "eventService");
		this.performerRequestService = Objects.requireNonNull(
				performerRequestService, "performerRequestService");
		this.validator = Objects.requireNonNull(validator, "validator");
	}

	public SimulationOpportunitySeedResult.ModuleResult materialize(
			List<EventStory> stories,
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds,
			boolean allowExisting,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(stories, "stories");
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(profileIds, "profileIds");
		Objects.requireNonNull(venueIds, "venueIds");
		Objects.requireNonNull(bandIds, "bandIds");
		Objects.requireNonNull(ledger, "ledger");
		validateBatch(stories);

		Map<String, Account> accounts = manifest.accounts().stream()
				.collect(Collectors.toUnmodifiableMap(Account::key, Function.identity()));
		Map<String, SimulationWorldManifest.Band> bands = manifest.bands().stream()
				.collect(Collectors.toUnmodifiableMap(
						SimulationWorldManifest.Band::key, Function.identity()));
		Set<String> approvedVenues = manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.VENUE)
				.filter(account -> account.emailVerification() == EmailVerificationState.VERIFIED)
				.filter(account -> account.institutionState() == InstitutionState.APPROVED)
				.map(Account::key)
				.collect(Collectors.toUnmodifiableSet());

		Map<String, List<EventResponseDto>> eventCache = new LinkedHashMap<>();
		Map<String, UUID> ids = new LinkedHashMap<>();
		int created = 0;
		int skipped = 0;
		for (EventStory story : stories) {
			try {
				Outcome outcome = ensure(
						story, accounts, bands, approvedVenues, accountUserIds,
						profileIds, venueIds, bandIds, allowExisting, eventCache);
				if (outcome.retainedId() != null) ids.put(story.key(), outcome.retainedId());
				if (outcome.changed()) {
					created++;
					ledger.succeeded(PHASE, action(story), story.venueAccountKey(),
							story.key(), outcome.detail());
				} else {
					skipped++;
					ledger.skipped(PHASE, action(story), story.venueAccountKey(),
							story.key(), outcome.detail());
				}
			} catch (RuntimeException failure) {
				ledger.failed(PHASE, action(story), venueKey(story), storyKey(story), failure);
				throw failure;
			}
		}
		return new SimulationOpportunitySeedResult.ModuleResult(
				stories.size(), created, skipped, 0, ids);
	}

	private Outcome ensure(
			EventStory story,
			Map<String, Account> accounts,
			Map<String, SimulationWorldManifest.Band> bands,
			Set<String> approvedVenues,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds,
			boolean allowExisting,
			Map<String, List<EventResponseDto>> eventCache
	) {
		validateStoryShape(story);
		if (!approvedVenues.contains(story.venueAccountKey())) {
			throw new IllegalStateException(
					"Event publisher is not a verified approved venue: " + story.venueAccountKey());
		}
		UUID venueOwnerId = requireId(
				accountUserIds, story.venueAccountKey(), "approved venue owner");
		UUID venueId = requireId(venueIds, story.venueAccountKey(), "approved venue");
		PerformerTarget target = performerTarget(
				story, accounts, bands, accountUserIds, profileIds, bandIds);
		EventCreateRequestDto request = new EventCreateRequestDto(
				story.title(), story.description(), story.eventDate(), story.startTime(), story.endTime(),
				null, venueId, target.musicianProfileId(), target.bandId(), story.manualPerformerName());
		validate(request, story.key());

		List<EventResponseDto> venueEvents = eventCache.computeIfAbsent(
				story.venueAccountKey(), ignored -> new ArrayList<>(
						eventService.getOwnerEventsByVenue(venueOwnerId, venueId)));
		List<EventResponseDto> titleMatches = venueEvents.stream()
				.filter(event -> story.title().equals(event.title()))
				.toList();
		if (titleMatches.size() > 1) {
			throw new IllegalStateException("Multiple Events use simulation story title: " + story.key());
		}

		if (!titleMatches.isEmpty()) {
			if (!allowExisting) {
				throw new IllegalStateException("Event identity already exists outside RESUME: " + story.key());
			}
			EventResponseDto existing = titleMatches.getFirst();
			assertExactBase(existing, request, story);
			if (story.finalState() == EventFinalState.DELETED) {
				eventService.deleteEventById(venueOwnerId, existing.id());
				venueEvents.remove(existing);
				return new Outcome(null, true, "partial deleted-event lifecycle completed");
			}
			boolean changed = convergePerformer(
					story, existing, target, accountUserIds, profileIds, bandIds);
			return new Outcome(existing.id(), changed,
					changed ? "existing performer lifecycle completed" : "already present as requested");
		}

		if (story.finalState() == EventFinalState.DELETED && allowExisting) {
			return new Outcome(null, false, "deleted control already absent");
		}
		EventResponseDto created = eventService.createEvent(venueOwnerId, request);
		assertExactBase(created, request, story);
		venueEvents.add(created);
		convergePerformer(story, created, target, accountUserIds, profileIds, bandIds);
		if (story.finalState() == EventFinalState.DELETED) {
			eventService.deleteEventById(venueOwnerId, created.id());
			venueEvents.remove(created);
			return new Outcome(null, true, "created and deleted through production lifecycle");
		}
		return new Outcome(created.id(), true, "created through approved venue owner");
	}

	private boolean convergePerformer(
			EventStory story,
			EventResponseDto event,
			PerformerTarget target,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds
	) {
		assertPerformerSnapshot(story, event, target);
		if (!requiresPerformerRequest(story.performerStory())) return false;

		UUID actorUserId = requireId(
				accountUserIds, story.performerActorAccountKey(), "performer actor account");
		PerformerType targetType = isMusician(story.performerStory())
				? PerformerType.MUSICIAN : PerformerType.BAND;
		UUID targetId = targetType == PerformerType.MUSICIAN
				? requireId(profileIds, story.performerKey(), "musician performer profile")
				: requireId(bandIds, story.performerKey(), "band performer");

		if (isAccepted(story.performerStory())) {
			Optional<EventPerformerRequestResponseDto> accepted = requestFor(
					actorUserId, targetType, targetId, event.id(), EventPerformerRequestStatus.ACCEPTED);
			if (accepted.isPresent()) {
				assertAcceptedVisibility(story, accepted.get());
				return false;
			}
			EventPerformerRequestResponseDto pending = requestFor(
					actorUserId, targetType, targetId, event.id(), EventPerformerRequestStatus.PENDING)
					.orElseThrow(() -> new IllegalStateException(
							"Accepted Event story has no resumable performer request: " + story.key()));
			EventPerformerRequestResponseDto decided = performerRequestService.accept(
					actorUserId, pending.id(), story.showOnProfile());
			assertAcceptedVisibility(story, decided);
			return true;
		}

		if (requestFor(actorUserId, targetType, targetId, event.id(),
				EventPerformerRequestStatus.PENDING).isEmpty()) {
			throw new IllegalStateException("Pending Event story has no pending request: " + story.key());
		}
		return false;
	}

	private void assertAcceptedVisibility(
			EventStory story,
			EventPerformerRequestResponseDto request
	) {
		if (request == null
				|| request.status() != EventPerformerRequestStatus.ACCEPTED
				|| request.profileCalendarApproved() != story.showOnProfile()) {
			throw new IllegalStateException(
					"Accepted Event performer visibility conflicts with story: " + story.key());
		}
	}

	private Optional<EventPerformerRequestResponseDto> requestFor(
			UUID actorUserId,
			PerformerType targetType,
			UUID targetId,
			UUID eventId,
			EventPerformerRequestStatus status
	) {
		List<EventPerformerRequestResponseDto> matches = performerRequestService
				.getMine(actorUserId, status, targetType, targetId, 0, 50)
				.content().stream()
				.filter(request -> eventId.equals(request.eventId()))
				.toList();
		if (matches.size() > 1) {
			throw new IllegalStateException("Event has multiple performer requests in the same state");
		}
		return matches.stream().findFirst();
	}

	private PerformerTarget performerTarget(
			EventStory story,
			Map<String, Account> accounts,
			Map<String, SimulationWorldManifest.Band> bands,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds
	) {
		if (isMusician(story.performerStory())) {
			Account musician = Optional.ofNullable(accounts.get(story.performerKey()))
					.orElseThrow(() -> new IllegalStateException(
							"Event musician is absent from manifest: " + story.key()));
			if (musician.role() != AccountRole.MUSICIAN
					|| musician.emailVerification() != EmailVerificationState.VERIFIED) {
				throw new IllegalStateException("Event performer must be a verified musician: " + story.key());
			}
			UUID id = requireId(profileIds, musician.key(), "musician performer profile");
			requireId(accountUserIds, musician.key(), "musician performer account");
			if (!Objects.equals(story.performerActorAccountKey(), musician.key())) {
				throw new IllegalStateException("Event musician actor mapping conflicts with manifest");
			}
			return new PerformerTarget(id, null, musician.username());
		}
		if (isBand(story.performerStory())) {
			SimulationWorldManifest.Band band = Optional.ofNullable(bands.get(story.performerKey()))
					.orElseThrow(() -> new IllegalStateException(
							"Event band is absent from manifest: " + story.key()));
			if (!Objects.equals(story.performerActorAccountKey(), band.ownerAccountKey())) {
				throw new IllegalStateException("Event band actor mapping conflicts with manifest");
			}
			UUID id = requireId(bandIds, band.key(), "band performer");
			requireId(accountUserIds, band.ownerAccountKey(), "band representative account");
			return new PerformerTarget(null, id, band.name());
		}
		if (story.performerStory() == EventPerformerStory.MANUAL) {
			if (story.manualPerformerName() == null || story.manualPerformerName().isBlank()) {
				throw new IllegalArgumentException("Manual Event performer name is required: " + story.key());
			}
			return new PerformerTarget(null, null, story.manualPerformerName());
		}
		if (story.manualPerformerName() != null || story.performerKey() != null
				|| story.performerActorAccountKey() != null) {
			throw new IllegalArgumentException("Performer-free Event carries performer fields: " + story.key());
		}
		return new PerformerTarget(null, null, "Belirtilmemiş");
	}

	private void assertExactBase(
			EventResponseDto event,
			EventCreateRequestDto request,
			EventStory story
	) {
		boolean exact = event != null && event.id() != null
				&& request.title().equals(event.title())
				&& Objects.equals(request.description(), event.description())
				&& request.eventDate().equals(event.eventDate())
				&& request.startTime().equals(event.startTime())
				&& Objects.equals(request.endTime(), event.endTime())
				&& request.venueId().equals(event.venueId())
				&& event.eventOrigin() == EventOrigin.VENUE
				&& event.venueApprovalStatus() == EventVenueApprovalStatus.APPROVED
				&& event.venueCalendarApproved();
		if (!exact) {
			throw new IllegalStateException("Existing Event payload conflicts with story: " + story.key());
		}
	}

	private void assertPerformerSnapshot(
			EventStory story,
			EventResponseDto event,
			PerformerTarget target
	) {
		if (story.performerStory() == EventPerformerStory.NONE) {
			if (event.performerType() != null || event.musicianProfileId() != null || event.bandId() != null) {
				throw new IllegalStateException("Performer-free Event has an unexpected performer: " + story.key());
			}
			return;
		}
		if (story.performerStory() == EventPerformerStory.MANUAL) {
			if (event.performerType() != PerformerType.MANUAL
					|| !target.expectedName().equals(event.performerName())
					|| event.musicianProfileId() != null || event.bandId() != null) {
				throw new IllegalStateException("Manual Event performer conflicts with story: " + story.key());
			}
			return;
		}
		boolean targetName = target.expectedName().equals(event.performerName());
		boolean pendingSnapshot = event.performerType() == PerformerType.MANUAL
				&& event.musicianProfileId() == null && event.bandId() == null;
		boolean linkedTarget = isMusician(story.performerStory())
				? event.performerType() == PerformerType.MUSICIAN
						&& target.musicianProfileId().equals(event.musicianProfileId())
						&& event.bandId() == null
				: event.performerType() == PerformerType.BAND
						&& target.bandId().equals(event.bandId())
						&& event.musicianProfileId() == null;
		if (!targetName || (!pendingSnapshot && !linkedTarget)) {
			throw new IllegalStateException("Event performer conflicts with story: " + story.key());
		}
	}

	private void validateStoryShape(EventStory story) {
		Objects.requireNonNull(story, "Event story");
		Objects.requireNonNull(story.performerStory(), "Event performer story");
		Objects.requireNonNull(story.finalState(), "Event final state");
		if (story.key() == null || story.key().isBlank()) {
			throw new IllegalArgumentException("Event story key is required");
		}
		if (story.venueAccountKey() == null || story.venueAccountKey().isBlank()) {
			throw new IllegalArgumentException("Event venue account key is required: " + story.key());
		}
		if (story.endTime() != null && story.startTime() != null
				&& !story.endTime().isAfter(story.startTime())) {
			throw new IllegalArgumentException("Event end time must follow start time: " + story.key());
		}
		boolean hasTargetKey = story.performerKey() != null && !story.performerKey().isBlank();
		boolean hasActorKey = story.performerActorAccountKey() != null
				&& !story.performerActorAccountKey().isBlank();
		boolean hasManualName = story.manualPerformerName() != null
				&& !story.manualPerformerName().isBlank();
		switch (story.performerStory()) {
			case NONE -> {
				if (hasTargetKey || hasActorKey || hasManualName || story.showOnProfile()) {
					throw new IllegalArgumentException(
							"Performer-free Event carries performer state: " + story.key());
				}
			}
			case MANUAL -> {
				if (!hasManualName || hasTargetKey || hasActorKey || story.showOnProfile()) {
					throw new IllegalArgumentException(
							"Manual Event performer fields conflict: " + story.key());
				}
			}
			case MUSICIAN_PENDING, BAND_PENDING -> {
				if (!hasTargetKey || !hasActorKey || hasManualName || story.showOnProfile()) {
					throw new IllegalArgumentException(
							"Pending Event performer fields conflict: " + story.key());
				}
			}
			case MUSICIAN_ACCEPTED, BAND_ACCEPTED -> {
				if (!hasTargetKey || !hasActorKey || hasManualName) {
					throw new IllegalArgumentException(
							"Accepted Event performer fields conflict: " + story.key());
				}
			}
		}
	}

	private void validateBatch(List<EventStory> stories) {
		Set<String> keys = new HashSet<>();
		Set<EventIdentity> eventIdentities = new HashSet<>();
		for (EventStory story : stories) {
			validateStoryShape(story);
			if (!keys.add(story.key())) {
				throw new IllegalArgumentException("Duplicate Event story key: " + story.key());
			}
			if (!eventIdentities.add(new EventIdentity(story.venueAccountKey(), story.title()))) {
				throw new IllegalArgumentException(
						"Duplicate Event title within venue: " + story.title());
			}
		}
	}

	private <T> void validate(T request, String storyKey) {
		Set<ConstraintViolation<T>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(
					"Invalid simulation Event DTO for story " + storyKey, violations);
		}
	}

	private boolean requiresPerformerRequest(EventPerformerStory story) {
		return isMusician(story) || isBand(story);
	}

	private boolean isMusician(EventPerformerStory story) {
		return story == EventPerformerStory.MUSICIAN_PENDING
				|| story == EventPerformerStory.MUSICIAN_ACCEPTED;
	}

	private boolean isBand(EventPerformerStory story) {
		return story == EventPerformerStory.BAND_PENDING
				|| story == EventPerformerStory.BAND_ACCEPTED;
	}

	private boolean isAccepted(EventPerformerStory story) {
		return story == EventPerformerStory.MUSICIAN_ACCEPTED
				|| story == EventPerformerStory.BAND_ACCEPTED;
	}

	private UUID requireId(Map<String, UUID> values, String key, String description) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(description + " key is required");
		}
		UUID id = values.get(key);
		if (id == null) throw new IllegalStateException("No " + description + " id for: " + key);
		return id;
	}

	private String action(EventStory story) {
		return story == null || story.finalState() == null
				? "EVENT_INVALID" : "EVENT_" + story.finalState().name();
	}

	private String venueKey(EventStory story) {
		return story == null ? null : story.venueAccountKey();
	}

	private String storyKey(EventStory story) {
		return story == null ? null : story.key();
	}

	private record PerformerTarget(
			UUID musicianProfileId,
			UUID bandId,
			String expectedName
	) {
	}

	private record EventIdentity(String venueAccountKey, String title) {
	}

	private record Outcome(UUID retainedId, boolean changed, String detail) {
	}
}
