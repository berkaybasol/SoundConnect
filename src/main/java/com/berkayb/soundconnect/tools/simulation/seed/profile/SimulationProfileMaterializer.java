package com.berkayb.soundconnect.tools.simulation.seed.profile;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfilePictureResult.Outcome;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfileReference.State;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationLocationResolver;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.MusicianProfilePlan;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes profile state through the same application services used by clients.
 *
 * <p>The profile phase deliberately does not attach media. Asset ownership does not exist until
 * the media slice has completed, so {@link #applyProfilePictures} is a separate idempotent phase.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationProfileMaterializer {
	private static final String PROFILE_PHASE = "profiles";
	private static final String PICTURE_PHASE = "profile-pictures";
	private static final Set<String> STUDIO_FACILITIES = Set.of(
			"Kayıt Odası", "Prova Odası", "Canlı Kayıt");

	private final SimulationRuntimeGuard runtimeGuard;
	private final Validator validator;
	private final SimulationLocationResolver locationResolver;
	private final InstrumentRepository instrumentRepository;
	private final MusicianProfileService musicianProfileService;
	private final MusicianFeedPreferencesService musicianFeedPreferencesService;
	private final SimulationMusicianLegacyStageNameCleaner musicianStageNameCleaner;
	private final ListenerProfileService listenerProfileService;
	private final VenueProfileService venueProfileService;
	private final StudioProfileService studioProfileService;

	public SimulationProfileMaterializationResult materialize(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds
	) {
		return materialize(manifest, accountUserIds, null);
	}

	public SimulationProfileMaterializationResult materialize(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Map<String, Account> accounts = validateInputs(manifest, accountUserIds);
		LinkedHashSet<UUID> musicianUserIds = new LinkedHashSet<>();
		accounts.values().stream()
				.filter(account -> account.role() == AccountRole.MUSICIAN)
				.map(account -> accountUserIds.get(account.key()))
				.forEach(musicianUserIds::add);
		if (!musicianUserIds.isEmpty()) musicianStageNameCleaner.clearForUserIds(musicianUserIds);
		LinkedHashMap<String, SimulationProfileReference> result = new LinkedHashMap<>();

		for (Account account : accounts.values()) {
			UUID userId = accountUserIds.get(account.key());
			try {
				SimulationProfileReference reference = switch (account.role()) {
					case MUSICIAN -> materializeMusician(account, userId, ledger);
					case LISTENER -> materializeListener(account, userId, ledger);
					case VENUE -> materializeVenue(account, userId, ledger);
					case STUDIO -> materializeStudio(account, userId, ledger);
				};
				result.put(account.key(), reference);
			} catch (RuntimeException failure) {
				failed(ledger, PROFILE_PHASE, "materialize", account.key(), failure);
				throw failure;
			}
		}
		return new SimulationProfileMaterializationResult(result);
	}

	public SimulationProfilePictureResult applyProfilePictures(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationProfileMaterializationResult profiles,
			Map<String, UUID> profilePictureAssetIds
	) {
		return applyProfilePictures(manifest, accountUserIds, profiles, profilePictureAssetIds, null);
	}

	public SimulationProfilePictureResult applyProfilePictures(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationProfileMaterializationResult profiles,
			Map<String, UUID> profilePictureAssetIds,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Map<String, Account> accounts = validateInputs(manifest, accountUserIds);
		Objects.requireNonNull(profiles, "profiles");
		Objects.requireNonNull(profilePictureAssetIds, "profilePictureAssetIds");
		validateProfileReferences(accounts, accountUserIds, profiles);
		for (Map.Entry<String, UUID> entry : profilePictureAssetIds.entrySet()) {
			if (!accounts.containsKey(entry.getKey()) || entry.getValue() == null) {
				throw new IllegalArgumentException("Unknown account or null profile-picture asset: " + entry.getKey());
			}
		}

		LinkedHashMap<String, Outcome> outcomes = new LinkedHashMap<>();
		for (Account account : accounts.values()) {
			UUID mediaAssetId = profilePictureAssetIds.get(account.key());
			if (mediaAssetId == null) continue;
			try {
				Outcome outcome = applyProfilePicture(
						account,
						accountUserIds.get(account.key()),
						profiles.require(account.key()),
						mediaAssetId);
				outcomes.put(account.key(), outcome);
				if (outcome == Outcome.INELIGIBLE_CONTROL) {
					skipped(ledger, PICTURE_PHASE, "attach", account.key(), "negative control");
				} else {
					succeeded(ledger, PICTURE_PHASE, "attach", account.key(), outcome.name());
				}
			} catch (RuntimeException failure) {
				failed(ledger, PICTURE_PHASE, "attach", account.key(), failure);
				throw failure;
			}
		}
		return new SimulationProfilePictureResult(outcomes);
	}

	private SimulationProfileReference materializeMusician(
			Account account,
			UUID userId,
			SimulationRunLedger ledger
	) {
		MusicianProfilePlan plan = Objects.requireNonNull(
				account.musicianProfile(), "musicianProfile for " + account.key());
		MusicianProfileResponseDto current = musicianProfileService.getProfileByUserId(userId);
		assertProfileIdentity(account, userId, current.userId(), current.id());
		if (current.stageName() != null) {
			throw mismatch(account.key(), "legacy stage name cleanup was not applied");
		}

		if (account.emailVerification() == EmailVerificationState.UNVERIFIED) {
			skipped(ledger, PROFILE_PHASE, "musician", account.key(), "unverified control");
			return reference(account, userId, current.id(), null, State.UNVERIFIED_CONTROL);
		}

		assertIncompleteFieldsRemainEmpty(account, current, plan);
		Set<UUID> instrumentIds = plan.populateInstruments()
				? resolveInstrumentIds(account) : Set.of();
		Set<String> desiredInstrumentNames = plan.populateInstruments()
				? new LinkedHashSet<>(account.instrumentNames()) : Set.of();
		String desiredBio = plan.populateBio() ? account.bio() : null;
		String desiredInstagram = plan.populateSocialLinks() ? instagramUrl(account.username()) : null;

		boolean profileChange = plan.populateBio() && !Objects.equals(current.bio(), desiredBio)
				|| plan.populateSocialLinks() && !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !sameNormalizedStrings(current.instruments(), desiredInstrumentNames);
		if (profileChange) {
			MusicianProfileSaveRequestDto update = new MusicianProfileSaveRequestDto(
					null,
					plan.populateBio() ? desiredBio : null,
					null,
					plan.populateSocialLinks() ? desiredInstagram : null,
					null, null, null, null,
					instrumentIds,
					null, null);
			validateDto(update, account.key());
			current = musicianProfileService.updateProfile(userId, update);
			assertProfileIdentity(account, userId, current.userId(), current.id());
		}
		if (current.stageName() != null
				|| plan.populateBio() && !Objects.equals(current.bio(), desiredBio)
				|| plan.populateSocialLinks() && !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !sameNormalizedStrings(current.instruments(), desiredInstrumentNames)) {
			throw mismatch(account.key(), "musician profile fields were not persisted");
		}

		if (plan.populateLocation()) {
			SimulationResolvedLocation location = locationResolver.resolve(account.location());
			MusicianFeedPreferencesResponse preferences = musicianFeedPreferencesService.get(userId);
			UUID currentCityId = preferences.opportunityCity() == null
					? null : preferences.opportunityCity().id();
			if (!location.city().getId().equals(currentCityId)) {
				MusicianFeedPreferencesUpdate update = new MusicianFeedPreferencesUpdate(
						location.city().getId(), preferences.version());
				validateDto(update, account.key());
				MusicianFeedPreferencesResponse updated = musicianFeedPreferencesService.update(userId, update);
				if (updated.opportunityCity() == null
						|| !location.city().getId().equals(updated.opportunityCity().id())) {
					throw mismatch(account.key(), "opportunity city was not persisted");
				}
			}
		}

		succeeded(ledger, PROFILE_PHASE, "musician", account.key(),
				profileChange ? "updated" : "already current");
		return reference(account, userId, current.id(), null, State.MATERIALIZED);
	}

	private SimulationProfileReference materializeListener(
			Account account,
			UUID userId,
			SimulationRunLedger ledger
	) {
		ListenerVisibilityState desired = Objects.requireNonNull(
				account.listenerVisibility(), "listenerVisibility for " + account.key());
		ListenerProfileOwnerResponseDto current = listenerProfileService.getMyProfile(userId);
		assertProfileIdentity(account, userId, current.userId(), current.id());

		if (desired == ListenerVisibilityState.VISIBILITY_PENDING) {
			if (current.visibilityChoiceCompleted()) {
				throw mismatch(account.key(), "listener visibility is already completed");
			}
			skipped(ledger, PROFILE_PHASE, "listener", account.key(), "visibility-pending control");
			return reference(account, userId, current.id(), null, State.VISIBILITY_PENDING_CONTROL);
		}

		ListenerVisibilityMode desiredMode = desired == ListenerVisibilityState.GHOST
				? ListenerVisibilityMode.GHOST : ListenerVisibilityMode.STANDARD;
		if (!current.visibilityChoiceCompleted() || current.visibilityMode() != desiredMode) {
			ListenerVisibilityUpdateRequestDto visibility = new ListenerVisibilityUpdateRequestDto(
					desiredMode, current.version());
			validateDto(visibility, account.key());
			current = listenerProfileService.updateVisibility(userId, visibility);
		}

		if (desired == ListenerVisibilityState.STANDARD && !Objects.equals(current.bio(), account.bio())) {
			ListenerSaveRequestDto content = new ListenerSaveRequestDto(account.bio(), null);
			validateDto(content, account.key());
			current = listenerProfileService.updateMyProfile(userId, content);
		}
		assertProfileIdentity(account, userId, current.userId(), current.id());
		if (!current.visibilityChoiceCompleted() || current.visibilityMode() != desiredMode) {
			throw mismatch(account.key(), "listener visibility was not persisted");
		}
		if (desired == ListenerVisibilityState.STANDARD && !Objects.equals(current.bio(), account.bio())) {
			throw mismatch(account.key(), "listener bio was not persisted");
		}

		succeeded(ledger, PROFILE_PHASE, "listener", account.key(), desiredMode.name());
		return reference(account, userId, current.id(), null, State.MATERIALIZED);
	}

	private SimulationProfileReference materializeVenue(
			Account account,
			UUID userId,
			SimulationRunLedger ledger
	) {
		InstitutionState institutionState = Objects.requireNonNull(
				account.institutionState(), "institutionState for " + account.key());
		if (institutionState != InstitutionState.APPROVED) {
			State state = institutionState == InstitutionState.PENDING
					? State.INSTITUTION_PENDING_CONTROL : State.INSTITUTION_REJECTED_CONTROL;
			skipped(ledger, PROFILE_PHASE, "venue", account.key(), institutionState.name());
			return reference(account, userId, null, null, state);
		}

		VenueProfileResponseDto current = requireSingleVenueProfile(account, userId);
		String desiredInstagram = instagramUrl(account.username());
		String desiredWebsite = websiteUrl(account.username());
		if (!Objects.equals(current.bio(), account.bio())
				|| !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !Objects.equals(current.websiteUrl(), desiredWebsite)) {
			VenueProfileSaveRequestDto update = new VenueProfileSaveRequestDto(
					account.bio(), null, desiredInstagram, null, desiredWebsite);
			validateDto(update, account.key());
			current = venueProfileService.updateProfileByVenueId(userId, current.venueId(), update);
		}
		assertVenueIdentity(account, current);
		if (!Objects.equals(current.bio(), account.bio())
				|| !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !Objects.equals(current.websiteUrl(), desiredWebsite)) {
			throw mismatch(account.key(), "venue profile fields were not persisted");
		}
		succeeded(ledger, PROFILE_PHASE, "venue", account.key(), "approved");
		return reference(account, userId, current.id(), current.venueId(), State.MATERIALIZED);
	}

	private SimulationProfileReference materializeStudio(
			Account account,
			UUID userId,
			SimulationRunLedger ledger
	) {
		InstitutionState institutionState = Objects.requireNonNull(
				account.institutionState(), "institutionState for " + account.key());
		if (institutionState != InstitutionState.APPROVED) {
			State state = institutionState == InstitutionState.PENDING
					? State.INSTITUTION_PENDING_CONTROL : State.INSTITUTION_REJECTED_CONTROL;
			skipped(ledger, PROFILE_PHASE, "studio", account.key(), institutionState.name());
			return reference(account, userId, null, null, state);
		}

		StudioProfileResponseDto current = studioProfileService.getProfileByUserId(userId);
		assertProfileIdentity(account, userId, current.userId(), current.id());
		String desiredInstagram = instagramUrl(account.username());
		String desiredWebsite = websiteUrl(account.username());
		if (!Objects.equals(current.description(), account.bio())
				|| !Objects.equals(current.website(), desiredWebsite)
				|| !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !sameNormalizedStrings(current.facilities(), STUDIO_FACILITIES)) {
			StudioProfileSaveRequestDto update = new StudioProfileSaveRequestDto(
					null, account.bio(), null, null, null, desiredWebsite,
					STUDIO_FACILITIES, desiredInstagram, null,
					null, current.version(), null, null);
			validateDto(update, account.key());
			current = studioProfileService.updateProfile(userId, update);
		}
		assertProfileIdentity(account, userId, current.userId(), current.id());
		if (!Objects.equals(current.description(), account.bio())
				|| !Objects.equals(current.website(), desiredWebsite)
				|| !Objects.equals(current.instagramUrl(), desiredInstagram)
				|| !sameNormalizedStrings(current.facilities(), STUDIO_FACILITIES)) {
			throw mismatch(account.key(), "studio profile fields were not persisted");
		}
		succeeded(ledger, PROFILE_PHASE, "studio", account.key(), "approved");
		return reference(account, userId, current.id(), null, State.MATERIALIZED);
	}

	private Outcome applyProfilePicture(
			Account account,
			UUID userId,
			SimulationProfileReference reference,
			UUID mediaAssetId
	) {
		if (reference.state() == State.UNVERIFIED_CONTROL
				|| reference.state() == State.INSTITUTION_PENDING_CONTROL
				|| reference.state() == State.INSTITUTION_REJECTED_CONTROL
				|| account.role() == AccountRole.MUSICIAN
				&& !account.musicianProfile().populateProfilePhoto()) {
			return Outcome.INELIGIBLE_CONTROL;
		}

		return switch (account.role()) {
			case MUSICIAN -> applyMusicianPicture(account, userId, reference, mediaAssetId);
			case LISTENER -> applyListenerPicture(account, userId, reference, mediaAssetId);
			case VENUE -> applyVenuePicture(account, userId, reference, mediaAssetId);
			case STUDIO -> applyStudioPicture(account, userId, reference, mediaAssetId);
		};
	}

	private Outcome applyMusicianPicture(
			Account account, UUID userId, SimulationProfileReference reference, UUID mediaAssetId) {
		MusicianProfileResponseDto current = musicianProfileService.getProfileByUserId(userId);
		assertReferenceIdentity(account, reference, current.id(), current.userId());
		if (mediaAssetId.equals(current.profilePictureMediaId())) return Outcome.ALREADY_PRESENT;
		MusicianProfileSaveRequestDto update = new MusicianProfileSaveRequestDto(
				null, null, mediaAssetId, null, null, null, null, null, null, null, null);
		validateDto(update, account.key());
		MusicianProfileResponseDto updated = musicianProfileService.updateProfile(userId, update);
		assertPictureApplied(account, reference, updated.id(), updated.userId(),
				updated.profilePictureMediaId(), mediaAssetId);
		return Outcome.APPLIED;
	}

	private Outcome applyListenerPicture(
			Account account, UUID userId, SimulationProfileReference reference, UUID mediaAssetId) {
		ListenerProfileOwnerResponseDto current = listenerProfileService.getMyProfile(userId);
		assertReferenceIdentity(account, reference, current.id(), current.userId());
		if (mediaAssetId.equals(current.profilePictureMediaId())) return Outcome.ALREADY_PRESENT;
		ListenerAvatarUpdateRequestDto update = new ListenerAvatarUpdateRequestDto(
				mediaAssetId, current.version());
		validateDto(update, account.key());
		ListenerProfileOwnerResponseDto updated = listenerProfileService.updateAvatar(userId, update);
		assertPictureApplied(account, reference, updated.id(), updated.userId(),
				updated.profilePictureMediaId(), mediaAssetId);
		return Outcome.APPLIED;
	}

	private Outcome applyVenuePicture(
			Account account, UUID userId, SimulationProfileReference reference, UUID mediaAssetId) {
		VenueProfileResponseDto current = venueProfileService.getProfileByVenueId(reference.venueAggregateId());
		assertVenueReferenceIdentity(account, reference, current);
		if (mediaAssetId.equals(current.profilePictureMediaId())) return Outcome.ALREADY_PRESENT;
		VenueProfileSaveRequestDto update = new VenueProfileSaveRequestDto(
				null, mediaAssetId, null, null, null);
		validateDto(update, account.key());
		VenueProfileResponseDto updated = venueProfileService.updateProfileByVenueId(
				userId, reference.venueAggregateId(), update);
		assertVenueReferenceIdentity(account, reference, updated);
		if (!mediaAssetId.equals(updated.profilePictureMediaId())) {
			throw mismatch(account.key(), "venue profile picture was not persisted");
		}
		return Outcome.APPLIED;
	}

	private Outcome applyStudioPicture(
			Account account, UUID userId, SimulationProfileReference reference, UUID mediaAssetId) {
		StudioProfileResponseDto current = studioProfileService.getProfileByUserId(userId);
		assertReferenceIdentity(account, reference, current.id(), current.userId());
		if (mediaAssetId.equals(current.profilePictureMediaId())) return Outcome.ALREADY_PRESENT;
		StudioProfileSaveRequestDto update = new StudioProfileSaveRequestDto(
				null, null, mediaAssetId, null, null, null,
				null, null, null, null, current.version(), null, null);
		validateDto(update, account.key());
		StudioProfileResponseDto updated = studioProfileService.updateProfile(userId, update);
		assertPictureApplied(account, reference, updated.id(), updated.userId(),
				updated.profilePictureMediaId(), mediaAssetId);
		return Outcome.APPLIED;
	}

	private Set<UUID> resolveInstrumentIds(Account account) {
		LinkedHashSet<UUID> ids = new LinkedHashSet<>();
		for (String requestedName : account.instrumentNames()) {
			Instrument instrument = instrumentRepository.findByNameIgnoreCase(requestedName)
					.orElseThrow(() -> new IllegalStateException(
							"Simulation instrument is absent from catalog: " + requestedName));
			if (instrument.getId() == null || !ids.add(instrument.getId())) {
				throw mismatch(account.key(), "instrument catalog mapping is ambiguous");
			}
		}
		return Set.copyOf(ids);
	}

	private VenueProfileResponseDto requireSingleVenueProfile(Account account, UUID userId) {
		List<VenueProfileResponseDto> profiles = venueProfileService.getProfilesByUserId(userId);
		if (profiles == null || profiles.size() != 1) {
			throw mismatch(account.key(), "approved venue must own exactly one profile");
		}
		VenueProfileResponseDto profile = profiles.getFirst();
		assertVenueIdentity(account, profile);
		return profile;
	}

	private void assertIncompleteFieldsRemainEmpty(
			Account account,
			MusicianProfileResponseDto current,
			MusicianProfilePlan plan
	) {
		if (!plan.populateBio() && hasText(current.bio())) {
			throw mismatch(account.key(), "incomplete observer unexpectedly has a bio");
		}
		if (!plan.populateSocialLinks() && (hasText(current.instagramUrl())
				|| hasText(current.youtubeUrl()) || hasText(current.soundcloudUrl()))) {
			throw mismatch(account.key(), "incomplete observer unexpectedly has social links");
		}
		if (!plan.populateProfilePhoto() && current.profilePictureMediaId() != null) {
			throw mismatch(account.key(), "incomplete observer unexpectedly has a profile picture");
		}
	}

	private Map<String, Account> validateInputs(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		LinkedHashMap<String, Account> accounts = new LinkedHashMap<>();
		for (Account account : manifest.accounts()) {
			if (account == null || account.key() == null || account.key().isBlank()
					|| accounts.putIfAbsent(account.key(), account) != null) {
				throw new IllegalArgumentException("Manifest contains an invalid or duplicate account key");
			}
			if (account.emailVerification() == null) {
				throw new IllegalArgumentException("Account emailVerification is required: " + account.key());
			}
			if (account.role() == AccountRole.MUSICIAN && account.musicianProfile() == null) {
				throw new IllegalArgumentException("Musician profile plan is required: " + account.key());
			}
			if (account.role() != AccountRole.MUSICIAN
					&& account.emailVerification() != EmailVerificationState.VERIFIED) {
				throw new IllegalArgumentException("Only a musician may be an unverified control: " + account.key());
			}
			if (account.role() == AccountRole.LISTENER && account.listenerVisibility() == null) {
				throw new IllegalArgumentException("Listener visibility is required: " + account.key());
			}
			if ((account.role() == AccountRole.VENUE || account.role() == AccountRole.STUDIO)
					&& account.institutionState() == null) {
				throw new IllegalArgumentException("Institution state is required: " + account.key());
			}
			if (account.role() != AccountRole.VENUE && account.role() != AccountRole.STUDIO
					&& account.institutionState() != null) {
				throw new IllegalArgumentException("Institution state is incompatible with role: " + account.key());
			}
		}
		if (!accountUserIds.keySet().equals(accounts.keySet())) {
			throw new IllegalArgumentException("Account id map must exactly match the simulation manifest");
		}
		HashSet<UUID> uniqueIds = new HashSet<>();
		accountUserIds.forEach((key, id) -> {
			if (id == null || !uniqueIds.add(id)) {
				throw new IllegalArgumentException("Account id map contains a null or duplicate user id: " + key);
			}
		});
		return accounts;
	}

	private void validateProfileReferences(
			Map<String, Account> accounts,
			Map<String, UUID> accountUserIds,
			SimulationProfileMaterializationResult profiles
	) {
		if (!profiles.profilesByAccountKey().keySet().equals(accounts.keySet())) {
			throw new IllegalArgumentException("Profile result must exactly match the simulation manifest");
		}
		accounts.forEach((key, account) -> {
			SimulationProfileReference reference = profiles.require(key);
			if (reference.role() != account.role()
					|| !reference.userId().equals(accountUserIds.get(key))) {
				throw mismatch(key, "profile result identity does not match the account map");
			}
		});
	}

	private <T> void validateDto(T dto, String accountKey) {
		Set<ConstraintViolation<T>> violations = validator.validate(dto);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(
					"Invalid simulation profile DTO for " + accountKey, violations);
		}
	}

	private SimulationProfileReference reference(
			Account account, UUID userId, UUID profileId, UUID venueId, State state) {
		return new SimulationProfileReference(
				account.key(), account.role(), userId, profileId, venueId, state);
	}

	private void assertProfileIdentity(Account account, UUID expectedUserId, UUID actualUserId, UUID profileId) {
		if (profileId == null || !expectedUserId.equals(actualUserId)) {
			throw mismatch(account.key(), "profile identity does not match the registered account");
		}
	}

	private void assertReferenceIdentity(
			Account account,
			SimulationProfileReference reference,
			UUID actualProfileId,
			UUID actualUserId
	) {
		if (!reference.profileId().equals(actualProfileId) || !reference.userId().equals(actualUserId)) {
			throw mismatch(account.key(), "profile identity changed between materialization phases");
		}
	}

	private void assertPictureApplied(
			Account account,
			SimulationProfileReference reference,
			UUID profileId,
			UUID userId,
			UUID actualMediaId,
			UUID expectedMediaId
	) {
		assertReferenceIdentity(account, reference, profileId, userId);
		if (!expectedMediaId.equals(actualMediaId)) {
			throw mismatch(account.key(), "profile picture was not persisted");
		}
	}

	private void assertVenueIdentity(Account account, VenueProfileResponseDto profile) {
		if (profile == null || profile.id() == null || profile.venueId() == null) {
			throw mismatch(account.key(), "venue profile identity is incomplete");
		}
	}

	private void assertVenueReferenceIdentity(
			Account account,
			SimulationProfileReference reference,
			VenueProfileResponseDto profile
	) {
		assertVenueIdentity(account, profile);
		if (!reference.profileId().equals(profile.id())
				|| !reference.venueAggregateId().equals(profile.venueId())) {
			throw mismatch(account.key(), "venue aggregate identity changed between phases");
		}
	}

	private boolean sameNormalizedStrings(Collection<String> left, Collection<String> right) {
		return normalizedStrings(left).equals(normalizedStrings(right));
	}

	private Set<String> normalizedStrings(Collection<String> values) {
		if (values == null) return Set.of();
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String value : values) {
			if (value != null && !value.isBlank()) normalized.add(value.strip().toLowerCase(java.util.Locale.ROOT));
		}
		return Set.copyOf(normalized);
	}

	private static String instagramUrl(String username) {
		return "https://www.instagram.com/" + username;
	}

	private static String websiteUrl(String username) {
		return "https://soundconnect.invalid/profiles/" + username;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static IllegalStateException mismatch(String accountKey, String detail) {
		return new IllegalStateException("Simulation profile mismatch for " + accountKey + ": " + detail);
	}

	private static void succeeded(
			SimulationRunLedger ledger, String phase, String action, String key, String detail) {
		if (ledger != null) ledger.succeeded(phase, action, key, null, detail);
	}

	private static void skipped(
			SimulationRunLedger ledger, String phase, String action, String key, String detail) {
		if (ledger != null) ledger.skipped(phase, action, key, null, detail);
	}

	private static void failed(
			SimulationRunLedger ledger, String phase, String action, String key, RuntimeException failure) {
		if (ledger != null) ledger.failed(phase, action, key, null, failure);
	}
}
