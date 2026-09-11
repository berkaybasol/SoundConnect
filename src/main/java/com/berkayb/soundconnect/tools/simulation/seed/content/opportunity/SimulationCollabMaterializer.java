package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabDraftCreateRequest;
import com.berkayb.soundconnect.modules.collab.dto.request.ExpectedVersionRequest;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabListingResponse;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.CollabClosureReason;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.service.CollabService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabStory;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.Publisher;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Materializes deterministic Collab stories through the production lifecycle. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationCollabMaterializer {

	private static final String PHASE = "opportunity-collab";

	private final SimulationRuntimeGuard runtimeGuard;
	private final CollabService collabService;
	private final CollabRepository collabRepository;
	private final Validator validator;

	public SimulationCollabMaterializer(
			SimulationRuntimeGuard runtimeGuard,
			CollabService collabService,
			CollabRepository collabRepository,
			Validator validator
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.collabService = Objects.requireNonNull(collabService, "collabService");
		this.collabRepository = Objects.requireNonNull(collabRepository, "collabRepository");
		this.validator = Objects.requireNonNull(validator, "validator");
	}

	public SimulationOpportunitySeedResult.ModuleResult materialize(
			List<CollabStory> stories,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds,
			SimulationPreflightResult preflight,
			boolean allowExisting,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(stories, "stories");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(profileIds, "profileIds");
		Objects.requireNonNull(venueIds, "venueIds");
		Objects.requireNonNull(bandIds, "bandIds");
		Objects.requireNonNull(preflight, "preflight");
		Objects.requireNonNull(ledger, "ledger");
		validateBatch(stories);

		Map<UUID, List<CollabActorSummary>> actorCache = new LinkedHashMap<>();
		Map<String, UUID> ids = new LinkedHashMap<>();
		int created = 0;
		int skipped = 0;
		for (CollabStory story : stories) {
			try {
				Outcome outcome = ensure(
						story, accountUserIds, profileIds, venueIds, bandIds,
						preflight, allowExisting, actorCache);
				ids.put(story.key(), outcome.id());
				if (outcome.changed()) {
					created++;
					ledger.succeeded(PHASE, action(story), story.publisher().sourceKey(),
							story.key(), outcome.detail());
				} else {
					skipped++;
					ledger.skipped(PHASE, action(story), story.publisher().sourceKey(),
							story.key(), outcome.detail());
				}
			} catch (RuntimeException failure) {
				ledger.failed(PHASE, action(story), publisherKey(story), storyKey(story), failure);
				throw failure;
			}
		}
		return new SimulationOpportunitySeedResult.ModuleResult(
				stories.size(), created, skipped, 0, ids);
	}

	private Outcome ensure(
			CollabStory story,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds,
			SimulationPreflightResult preflight,
			boolean allowExisting,
			Map<UUID, List<CollabActorSummary>> actorCache
	) {
		validateStoryShape(story);
		UUID ownerId = requireId(
				accountUserIds, story.publisher().representativeAccountKey(), "publisher account");
		UUID sourceId = publisherSourceId(story.publisher(), profileIds, venueIds, bandIds);
		CollabActorSummary actor = resolveActor(ownerId, story.publisher(), sourceId, actorCache);
		SimulationResolvedLocation location = Optional.ofNullable(
				preflight.locationsByAccountKey().get(story.cityAccountKey()))
				.orElseThrow(() -> new IllegalStateException(
						"No preflight location for Collab story: " + story.key()));
		UUID cityId = requireNonNullId(location.city().getId(), "Collab city", story.key());
		UUID instrumentId = null;
		if (story.instrumentName() != null) {
			Instrument instrument = Optional.ofNullable(
					preflight.instrumentsByName().get(story.instrumentName()))
					.orElseThrow(() -> new IllegalStateException(
							"No preflight instrument for Collab story: " + story.key()));
			instrumentId = requireNonNullId(instrument.getId(), "Collab instrument", story.key());
		}

		CollabDraftCreateRequest request = new CollabDraftCreateRequest(
				story.clientRequestId(), actor.actorId(), story.cadence(), story.wantedType(),
				instrumentId, story.branch(), story.customSpecialty(), story.title(), story.description(),
				cityId, story.genres(), story.scheduledAt(), story.feeAmountMinor(), story.currency());
		validate(request, story.key());

		Optional<Collab> identity = collabRepository.findByOwnerIdAndClientRequestId(
				ownerId, story.clientRequestId());
		if (identity.isPresent()) {
			if (!allowExisting) {
				throw new IllegalStateException("Collab identity already exists outside RESUME: " + story.key());
			}
			Collab detailed = collabRepository.findDetailedById(identity.get().getId())
					.orElseThrow(() -> new IllegalStateException(
							"Collab identity disappeared during RESUME: " + story.key()));
			assertExactExisting(detailed, ownerId, actor.actorId(), request, story);
			CollabListingResponse current = collabService.detail(ownerId, detailed.getId());
			boolean changed = converge(ownerId, story, current);
			return new Outcome(current.id(), changed,
					changed ? "existing lifecycle completed" : existingDetail(current));
		}

		CollabListingResponse draft = collabService.createDraft(ownerId, request);
		assertResponseIdentity(draft, story, actor.actorId());
		boolean transitioned = converge(ownerId, story, draft);
		return new Outcome(draft.id(), true,
				transitioned ? "created and lifecycle completed" : "created as " + story.finalState());
	}

	private boolean converge(UUID ownerId, CollabStory story, CollabListingResponse initial) {
		CollabListingResponse current = initial;
		boolean changed = false;
		if (story.finalState() == CollabFinalState.DRAFT) {
			if (current.status() != CollabListingStatus.DRAFT) {
				throw lifecycleConflict(story, current.status());
			}
			return false;
		}

		if (current.status() == CollabListingStatus.DRAFT) {
			ExpectedVersionRequest expected = new ExpectedVersionRequest(current.version());
			validate(expected, story.key());
			current = collabService.publish(ownerId, current.id(), expected);
			changed = true;
		}
		if (story.finalState() == CollabFinalState.OPEN) {
			if (current.status() != CollabListingStatus.OPEN
					&& current.status() != CollabListingStatus.EXPIRED) {
				throw lifecycleConflict(story, current.status());
			}
			return changed;
		}

		if (current.status() == CollabListingStatus.OPEN) {
			ExpectedVersionRequest expected = new ExpectedVersionRequest(current.version());
			validate(expected, story.key());
			current = collabService.close(ownerId, current.id(), expected);
			changed = true;
		}
		if (current.status() != CollabListingStatus.CLOSED
				|| current.closureReason() != CollabClosureReason.OWNER_CLOSED) {
			throw lifecycleConflict(story, current.status());
		}
		return changed;
	}

	private void assertExactExisting(
			Collab existing,
			UUID ownerId,
			UUID actorId,
			CollabDraftCreateRequest request,
			CollabStory story
	) {
		UUID existingInstrument = existing.getInstrument() == null ? null : existing.getInstrument().getId();
		boolean exact = existing.getOwner() != null
				&& ownerId.equals(existing.getOwner().getId())
				&& existing.getPublisherActor() != null
				&& actorId.equals(existing.getPublisherActor().getId())
				&& request.clientRequestId().equals(existing.getClientRequestId())
				&& request.cadence() == existing.getCadence()
				&& request.wantedType() == existing.getWantedType()
				&& Objects.equals(request.instrumentId(), existingInstrument)
				&& request.branch() == existing.getBranch()
				&& Objects.equals(request.customSpecialty(), existing.getCustomSpecialty())
				&& request.title().equals(existing.getTitle())
				&& request.description().equals(existing.getDescription())
				&& existing.getCity() != null
				&& request.cityId().equals(existing.getCity().getId())
				&& request.genres().equals(existing.getGenres())
				&& Objects.equals(request.scheduledAt(), existing.getScheduledAt())
				&& Objects.equals(request.feeAmountMinor(), existing.getFeeAmountMinor())
				&& Objects.equals(request.currency(), existing.getCurrency());
		if (!exact) {
			throw new IllegalStateException("Existing Collab payload conflicts with story: " + story.key());
		}
	}

	private void assertResponseIdentity(
			CollabListingResponse response,
			CollabStory story,
			UUID actorId
	) {
		if (response == null || response.id() == null
				|| response.publisher() == null || !actorId.equals(response.publisher().actorId())
				|| response.status() != CollabListingStatus.DRAFT) {
			throw new IllegalStateException("Collab service returned an unexpected draft: " + story.key());
		}
	}

	private CollabActorSummary resolveActor(
			UUID ownerId,
			Publisher publisher,
			UUID sourceId,
			Map<UUID, List<CollabActorSummary>> cache
	) {
		List<CollabActorSummary> actors = cache.computeIfAbsent(
				ownerId, collabService::actorsMine);
		List<CollabActorSummary> matches = actors.stream()
				.filter(actor -> actor.profileType() == publisher.profileType())
				.filter(actor -> sourceId.equals(actor.sourceProfileId()))
				.filter(actor -> ownerId.equals(actor.contactUserId()))
				.toList();
		if (matches.size() != 1 || matches.getFirst().actorId() == null) {
			throw new IllegalStateException("Owned Collab actor could not be resolved: " + publisher.sourceKey());
		}
		return matches.getFirst();
	}

	private UUID publisherSourceId(
			Publisher publisher,
			Map<String, UUID> profileIds,
			Map<String, UUID> venueIds,
			Map<String, UUID> bandIds
	) {
		Map<String, UUID> source = switch (publisher.profileType()) {
			case MUSICIAN, STUDIO -> profileIds;
			case VENUE -> venueIds;
			case BAND -> bandIds;
			default -> throw new IllegalArgumentException(
					"Unsupported Collab publisher type: " + publisher.profileType());
		};
		return requireId(source, publisher.sourceKey(), "publisher profile");
	}

	private void validateStoryShape(CollabStory story) {
		Objects.requireNonNull(story, "Collab story");
		Objects.requireNonNull(story.publisher(), "Collab publisher");
		Objects.requireNonNull(story.publisher().profileType(), "Collab publisher profile type");
		Objects.requireNonNull(story.clientRequestId(), "Collab client request id");
		Objects.requireNonNull(story.finalState(), "Collab final state");
		if (story.key() == null || story.key().isBlank()) {
			throw new IllegalArgumentException("Collab story key is required");
		}
		if (story.publisher().sourceKey() == null || story.publisher().sourceKey().isBlank()
				|| story.publisher().representativeAccountKey() == null
				|| story.publisher().representativeAccountKey().isBlank()) {
			throw new IllegalArgumentException("Collab publisher logical keys are required: " + story.key());
		}
		if (story.cityAccountKey() == null || story.cityAccountKey().isBlank()) {
			throw new IllegalArgumentException("Collab city account key is required: " + story.key());
		}
	}

	private void validateBatch(List<CollabStory> stories) {
		Set<String> keys = new HashSet<>();
		Set<UUID> requestIds = new HashSet<>();
		for (CollabStory story : stories) {
			validateStoryShape(story);
			if (!keys.add(story.key())) {
				throw new IllegalArgumentException("Duplicate Collab story key: " + story.key());
			}
			if (!requestIds.add(story.clientRequestId())) {
				throw new IllegalArgumentException(
						"Duplicate Collab client request id: " + story.clientRequestId());
			}
		}
	}

	private <T> void validate(T request, String storyKey) {
		Set<ConstraintViolation<T>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(
					"Invalid simulation Collab DTO for story " + storyKey, violations);
		}
	}

	private UUID requireId(Map<String, UUID> values, String key, String description) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException(description + " key is required");
		}
		return requireNonNullId(values.get(key), description, key);
	}

	private UUID requireNonNullId(UUID id, String description, String key) {
		if (id == null) throw new IllegalStateException("No " + description + " id for: " + key);
		return id;
	}

	private String existingDetail(CollabListingResponse response) {
		return response.status() == CollabListingStatus.EXPIRED
				? "already present; naturally expired" : "already present as requested";
	}

	private String action(CollabStory story) {
		return story == null || story.finalState() == null
				? "COLLAB_INVALID" : "COLLAB_" + story.finalState().name();
	}

	private String publisherKey(CollabStory story) {
		return story == null || story.publisher() == null ? null : story.publisher().sourceKey();
	}

	private String storyKey(CollabStory story) {
		return story == null ? null : story.key();
	}

	private IllegalStateException lifecycleConflict(CollabStory story, CollabListingStatus actual) {
		return new IllegalStateException(
				"Existing Collab lifecycle conflicts with story " + story.key() + ": " + actual);
	}

	private record Outcome(UUID id, boolean changed, String detail) {
	}
}
