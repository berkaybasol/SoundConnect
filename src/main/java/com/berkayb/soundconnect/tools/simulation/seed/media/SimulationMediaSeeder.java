package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Band;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.seed.media.SimulationMediaSeedResult.MediaTarget;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Builds the canonical local media story through production application ports.
 *
 * <p>There is intentionally no repository/status shortcut here. Every asset is
 * initialized by {@link MediaAssetService}, uploaded through the opaque local
 * presigned capability, completed by the real verification coordinator, and
 * observed in {@link MediaStatus#READY} before the real Track/ProfileMedia
 * service accepts it.</p>
 */
public final class SimulationMediaSeeder {

	private static final int MAX_TRACKS = 50;
	private static final int MAX_PROFILE_PUBLICATIONS = 100;
	private static final int MAX_AVATARS = 64;
	private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(25);

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationProperties simulationProperties;
	private final SimulationMediaCheckpointStore checkpointStore;
	private final Runnable freshStorageReset;
	private final MediaAssetService mediaAssetService;
	private final SimulationPresignedUploadSink uploadSink;
	private final BandService bandService;
	private final TrackService trackService;
	private final ProfileMediaService profileMediaService;
	private final SimulationMediaFixtures fixtures;
	private final Duration readyTimeout;
	private final Duration pollInterval;
	private final ReentrantLock runLock = new ReentrantLock();
	private boolean freshMaterializationAttempted;

	public SimulationMediaSeeder(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties simulationProperties,
			SimulationMediaCheckpointStore checkpointStore,
			MediaAssetService mediaAssetService,
			SimulationFileStorageClient storageClient,
			BandService bandService,
			TrackService trackService,
			ProfileMediaService profileMediaService
	) {
		this(
				runtimeGuard,
				simulationProperties,
				checkpointStore,
				storageClient::resetStorageForFresh,
				mediaAssetService,
				storageClient,
				bandService,
				trackService,
				profileMediaService,
				new SimulationMediaFixtures(),
				DEFAULT_READY_TIMEOUT,
				DEFAULT_POLL_INTERVAL);
	}

	SimulationMediaSeeder(
			SimulationRuntimeGuard runtimeGuard,
			SimulationProperties simulationProperties,
			SimulationMediaCheckpointStore checkpointStore,
			Runnable freshStorageReset,
			MediaAssetService mediaAssetService,
			SimulationPresignedUploadSink uploadSink,
			BandService bandService,
			TrackService trackService,
			ProfileMediaService profileMediaService,
			SimulationMediaFixtures fixtures,
			Duration readyTimeout,
			Duration pollInterval
	) {
		this.runtimeGuard = require(runtimeGuard, "runtimeGuard");
		this.simulationProperties = require(simulationProperties, "simulationProperties");
		this.checkpointStore = require(checkpointStore, "checkpointStore");
		this.freshStorageReset = require(freshStorageReset, "freshStorageReset");
		this.mediaAssetService = require(mediaAssetService, "mediaAssetService");
		this.uploadSink = require(uploadSink, "uploadSink");
		this.bandService = require(bandService, "bandService");
		this.trackService = require(trackService, "trackService");
		this.profileMediaService = require(profileMediaService, "profileMediaService");
		this.fixtures = require(fixtures, "fixtures");
		this.readyTimeout = positive(readyTimeout, "readyTimeout");
		this.pollInterval = positive(pollInterval, "pollInterval");
	}

	/**
	 * @param accountUserIds account logical key to User id
	 * @param profileIds account logical key to its concrete public profile id;
	 *                   for venues this is {@code VenueProfile.id}, not Venue.id
	 * @param bandIds band logical key to Band id
	 */
	public SimulationMediaSeedResult seed(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds
	) {
		return seedOrResume(
				manifest,
				accountUserIds,
				profileIds,
				bandIds,
				simulationProperties.getMode());
	}

	/**
	 * Materializes in FRESH mode or performs an exact, read-only checkpoint
	 * restore in RESUME/FAST_FORWARD. The explicit mode must equal the guarded
	 * runtime configuration and therefore cannot be used to bypass it.
	 *
	 * <p>The FRESH orchestration contract is deliberately destructive and ordered:
	 * the caller first completes its guarded database reset, then this method
	 * removes the old media checkpoint, clears the fixed simulation storage
	 * namespaces, runs the real media lifecycle, and finally atomically commits a
	 * new checkpoint. A FRESH run never reuses an existing checkpoint.</p>
	 */
	public SimulationMediaSeedResult seedOrResume(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds,
			SimulationMode mode
	) {
		runtimeGuard.assertRuntimeAllowed();
		if (mode == null || mode != simulationProperties.getMode()) {
			throw new SimulationMediaSeedException(
					"Media seed mode must match the active simulation runtime mode");
		}
		if (mode == SimulationMode.PAUSE) {
			throw new SimulationMediaSeedException("PAUSE mode cannot materialize simulation media");
		}
		if (!runLock.tryLock()) {
			throw new SimulationMediaSeedException("A simulation media seed run is already active");
		}
		try {
			SeedPlan plan = plan(manifest, accountUserIds, profileIds, bandIds);
			String fingerprint = checkpointStore.identityFingerprint(
					manifest, accountUserIds, profileIds, bandIds);
			if (mode == SimulationMode.RESUME || mode == SimulationMode.FAST_FORWARD) {
				SimulationMediaCheckpoint checkpoint = checkpointStore.loadIfPresent(manifest)
						.orElseThrow(() ->
						new SimulationMediaSeedException(
								"RESUME/FAST_FORWARD requires a completed media checkpoint"));
				assertCheckpointIdentity(checkpoint, manifest, fingerprint);
				return validateCheckpointResult(plan, checkpoint.result());
			}

			if (freshMaterializationAttempted) {
				throw new SimulationMediaSeedException(
						"FRESH media materialization was already attempted in this runtime; reset first");
			}
			freshMaterializationAttempted = true;
			runtimeGuard.assertRuntimeAllowed();
			checkpointStore.resetForFresh(manifest);
			runtimeGuard.assertRuntimeAllowed();
			freshStorageReset.run();
			SimulationMediaSeedResult result = materialize(plan);
			checkpointStore.save(manifest, fingerprint, result);
			return result;
		} finally {
			runLock.unlock();
		}
	}

	private void assertCheckpointIdentity(
			SimulationMediaCheckpoint checkpoint,
			SimulationWorldManifest manifest,
			String fingerprint
	) {
		if (!checkpointStore.matches(checkpoint, manifest, fingerprint)) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint does not match the current world identities");
		}
	}

	private SimulationMediaSeedResult validateCheckpointResult(
			SeedPlan plan,
			SimulationMediaSeedResult result
	) {
		runtimeGuard.assertRuntimeAllowed();
		if (result == null
				|| result.avatarAssetIds().size() != plan.avatarOwners().size()
				|| result.profileMediaTargets().size() != plan.profilePublicationCount()
				|| result.trackMediaTargets().size() != plan.trackCount()) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint counts do not match the seed plan");
		}

		Map<String, OwnerTarget> ownersByKey = ownerTargetsByKey(plan);
		Set<String> expectedAvatarKeys = plan.avatarOwners().stream()
				.map(OwnerTarget::key)
				.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
		if (!expectedAvatarKeys.equals(result.avatarAssetIds().keySet())) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint avatar owners do not match the seed plan");
		}
		for (Map.Entry<String, UUID> avatar : result.avatarAssetIds().entrySet()) {
			OwnerTarget owner = requiredOwner(ownersByKey, avatar.getKey());
			assertCheckpointAsset(avatar.getValue(), owner, MediaKind.IMAGE, "avatar");
			if (owner.profileType() == ProfileType.BAND) {
				assertBandAvatar(owner, avatar.getValue());
			}
		}

		Map<String, Map<UUID, ProfileMedia>> publicationsByOwner = new LinkedHashMap<>();
		Map<String, Integer> publicationOrder = new LinkedHashMap<>();
		for (int index = 0; index < result.profileMediaTargets().size(); index++) {
			MediaTarget target = result.profileMediaTargets().get(index);
			OwnerTarget expectedOwner = plan.publicationOwners()
					.get(index % plan.publicationOwners().size());
			assertTargetPlan(target, expectedOwner, "profile-media", index + 1);
			assertCheckpointAsset(target.assetId(), expectedOwner, MediaKind.IMAGE, target.logicalKey());

			Map<UUID, ProfileMedia> ownerPublications = publicationsByOwner.computeIfAbsent(
					expectedOwner.key(), ignored -> loadOwnerPublications(expectedOwner));
			ProfileMedia publication = ownerPublications.get(target.publicationId());
			int expectedOrder = publicationOrder.merge(expectedOwner.key(), 1, Integer::sum) - 1;
			if (publication == null
					|| publication.getProfileType() != expectedOwner.profileType()
					|| !expectedOwner.ownerId().equals(publication.getProfileId())
					|| !target.assetId().equals(publication.getMediaAssetId())
					|| publication.getRole() != ProfileMediaRole.GALLERY
					|| !Objects.equals(publication.getOrderIndex(), expectedOrder)) {
				throw new SimulationMediaSeedException(
						"Profile-media checkpoint binding is no longer valid: " + target.logicalKey());
			}
		}

		for (int index = 0; index < result.trackMediaTargets().size(); index++) {
			MediaTarget target = result.trackMediaTargets().get(index);
			OwnerTarget expectedOwner = plan.trackOwners().get(index % plan.trackOwners().size());
			assertTargetPlan(target, expectedOwner, "track", index + 1);
			assertCheckpointAsset(target.assetId(), expectedOwner, MediaKind.AUDIO, target.logicalKey());
			runtimeGuard.assertRuntimeAllowed();
			TrackResponseDto track;
			try {
				track = trackService.getTrackById(target.publicationId());
			} catch (RuntimeException missingTrack) {
				throw new SimulationMediaSeedException(
						"Track checkpoint binding cannot be loaded: " + target.logicalKey(), missingTrack);
			}
			if (track == null
					|| !target.publicationId().equals(track.id())
					|| !target.assetId().equals(track.mediaAssetId())
					|| track.title() == null
					|| track.title().isBlank()
					|| track.playbackUrl() == null
					|| track.playbackUrl().isBlank()) {
				throw new SimulationMediaSeedException(
						"Track checkpoint binding is no longer valid: " + target.logicalKey());
			}
		}

		runtimeGuard.assertRuntimeAllowed();
		return result;
	}

	private Map<UUID, ProfileMedia> loadOwnerPublications(OwnerTarget owner) {
		runtimeGuard.assertRuntimeAllowed();
		List<ProfileMedia> publications;
		try {
			publications = profileMediaService.getMediaList(
					owner.profileType(), owner.ownerId(), ProfileMediaRole.GALLERY);
		} catch (RuntimeException readFailure) {
			throw new SimulationMediaSeedException(
					"Profile-media checkpoint cannot be loaded for " + owner.key(), readFailure);
		}
		if (publications == null || publications.size() > MAX_PROFILE_PUBLICATIONS) {
			throw new SimulationMediaSeedException(
					"Profile-media checkpoint lookup exceeded its hard bound for " + owner.key());
		}
		Map<UUID, ProfileMedia> byId = new LinkedHashMap<>();
		for (ProfileMedia publication : publications) {
			if (publication == null || publication.getId() == null
					|| byId.putIfAbsent(publication.getId(), publication) != null) {
				throw new SimulationMediaSeedException(
						"Profile-media checkpoint lookup returned invalid identities");
			}
		}
		return Map.copyOf(byId);
	}

	private void assertCheckpointAsset(
			UUID assetId,
			OwnerTarget owner,
			MediaKind kind,
			String logicalKey
	) {
		runtimeGuard.assertRuntimeAllowed();
		MediaAsset asset;
		try {
			asset = mediaAssetService.getById(assetId);
		} catch (RuntimeException readFailure) {
			throw new SimulationMediaSeedException(
					"Media checkpoint asset cannot be loaded: " + logicalKey, readFailure);
		}
		assertReadyContract(asset, assetId, owner, kind);
	}

	private static void assertTargetPlan(
			MediaTarget target,
			OwnerTarget expectedOwner,
			String prefix,
			int oneBasedIndex
	) {
		String expectedLogicalKey = String.format(
				java.util.Locale.ROOT, "%s-%03d", prefix, oneBasedIndex);
		if (target == null
				|| !expectedLogicalKey.equals(target.logicalKey())
				|| !expectedOwner.key().equals(target.ownerKey())) {
			throw new SimulationMediaSeedException(
					"Simulation media checkpoint target order does not match the seed plan");
		}
	}

	private static Map<String, OwnerTarget> ownerTargetsByKey(SeedPlan plan) {
		Map<String, OwnerTarget> owners = new LinkedHashMap<>();
		for (OwnerTarget owner : plan.avatarOwners()) putOwner(owners, owner);
		for (OwnerTarget owner : plan.publicationOwners()) putOwner(owners, owner);
		for (OwnerTarget owner : plan.trackOwners()) putOwner(owners, owner);
		return Map.copyOf(owners);
	}

	private static void putOwner(Map<String, OwnerTarget> owners, OwnerTarget owner) {
		OwnerTarget existing = owners.putIfAbsent(owner.key(), owner);
		if (existing != null && !existing.equals(owner)) {
			throw new SimulationMediaSeedException(
					"Logical media owner resolves to conflicting identities: " + owner.key());
		}
	}

	private static OwnerTarget requiredOwner(Map<String, OwnerTarget> owners, String key) {
		OwnerTarget owner = owners.get(key);
		if (owner == null) {
			throw new SimulationMediaSeedException("Unknown media owner in checkpoint: " + key);
		}
		return owner;
	}

	private SimulationMediaSeedResult materialize(SeedPlan plan) {
		Map<String, UUID> avatarAssetIds = new LinkedHashMap<>();
		List<UUID> publicationIds = new ArrayList<>(plan.profilePublicationCount());
		List<UUID> trackIds = new ArrayList<>(plan.trackCount());
		List<MediaTarget> profileMediaTargets = new ArrayList<>(plan.profilePublicationCount());
		List<MediaTarget> trackMediaTargets = new ArrayList<>(plan.trackCount());

		for (OwnerTarget owner : plan.avatarOwners()) {
			MediaAsset avatar = createReadyAsset(owner, fixtures.image(), "avatar");
			if (avatarAssetIds.put(owner.key(), avatar.getId()) != null) {
				throw new SimulationMediaSeedException("Duplicate avatar owner in seed plan: " + owner.key());
			}
			if (owner.profileType() == ProfileType.BAND) {
				attachBandAvatar(owner, avatar.getId());
			}
		}

		Map<String, Integer> publicationOrder = new LinkedHashMap<>();
		for (int index = 0; index < plan.profilePublicationCount(); index++) {
			OwnerTarget owner = plan.publicationOwners().get(index % plan.publicationOwners().size());
			String logicalKey = String.format(java.util.Locale.ROOT, "profile-media-%03d", index + 1);
			MediaAsset asset = createReadyAsset(owner, fixtures.image(), logicalKey);
			int orderIndex = publicationOrder.merge(owner.key(), 1, Integer::sum) - 1;
			runtimeGuard.assertRuntimeAllowed();
			ProfileMedia publication = profileMediaService.addMedia(
					owner.actingUserId(),
					owner.profileType(),
					owner.ownerId(),
					asset.getId(),
					ProfileMediaRole.GALLERY,
					orderIndex);
			if (publication == null || publication.getId() == null) {
				throw new SimulationMediaSeedException(
						"Profile media service returned no publication id for " + owner.key());
			}
			publicationIds.add(publication.getId());
			profileMediaTargets.add(new MediaTarget(
					logicalKey, owner.key(), asset.getId(), publication.getId()));
		}

		Map<String, Integer> ownerTrackOrder = new LinkedHashMap<>();
		for (int index = 0; index < plan.trackCount(); index++) {
			OwnerTarget owner = plan.trackOwners().get(index % plan.trackOwners().size());
			String logicalKey = String.format(java.util.Locale.ROOT, "track-%03d", index + 1);
			MediaAsset asset = createReadyAsset(owner, fixtures.audio(), logicalKey);
			int ownerIndex = ownerTrackOrder.merge(owner.key(), 1, Integer::sum);
			String title = owner.displayName() + " · Simulation Demo " + ownerIndex;
			runtimeGuard.assertRuntimeAllowed();
			TrackResponseDto track = trackService.createTrack(
					owner.ownerId(),
					owner.actingUserId(),
					new TrackCreateRequestDto(
							asset.getId(),
							title,
							1,
							80 + Math.floorMod(index * 7, 81)));
			if (track == null || track.id() == null) {
				throw new SimulationMediaSeedException(
						"Track service returned no track id for " + owner.key());
			}
			trackIds.add(track.id());
			trackMediaTargets.add(new MediaTarget(
					logicalKey, owner.key(), asset.getId(), track.id()));
		}

		runtimeGuard.assertRuntimeAllowed();
		return new SimulationMediaSeedResult(
				avatarAssetIds,
				trackIds,
				publicationIds,
				profileMediaTargets,
				trackMediaTargets);
	}

	private void attachBandAvatar(OwnerTarget owner, UUID assetId) {
		runtimeGuard.assertRuntimeAllowed();
		BandResponseDto updated;
		try {
			updated = bandService.updateBand(
					owner.ownerId(),
					owner.actingUserId(),
					new BandCreateRequestDto(
							null,
							null,
							assetId,
							null,
							null,
							null,
							null,
							null,
							null));
		} catch (RuntimeException updateFailure) {
			throw new SimulationMediaSeedException(
					"Band avatar could not be attached through BandService: " + owner.key(),
					updateFailure);
		}
		assertBandAvatarResponse(owner, assetId, updated);
	}

	private void assertBandAvatar(OwnerTarget owner, UUID assetId) {
		runtimeGuard.assertRuntimeAllowed();
		BandResponseDto current;
		try {
			current = bandService.getBandById(owner.ownerId(), owner.actingUserId());
		} catch (RuntimeException readFailure) {
			throw new SimulationMediaSeedException(
					"Band avatar checkpoint cannot be loaded: " + owner.key(), readFailure);
		}
		assertBandAvatarResponse(owner, assetId, current);
	}

	private static void assertBandAvatarResponse(
			OwnerTarget owner,
			UUID assetId,
			BandResponseDto response
	) {
		if (response == null
				|| !owner.ownerId().equals(response.id())
				|| !assetId.equals(response.profilePictureMediaId())
				|| response.profilePictureUrl() == null
				|| response.profilePictureUrl().isBlank()) {
			throw new SimulationMediaSeedException(
					"Band avatar binding is no longer valid: " + owner.key());
		}
	}

	private MediaAsset createReadyAsset(
			OwnerTarget owner,
			SimulationMediaFixtures.Fixture fixture,
			String logicalPurpose
	) {
		runtimeGuard.assertRuntimeAllowed();
		byte[] bytes = fixture.bytes();
		UploadInitResultResponseDto initialized;
		try {
			initialized = mediaAssetService.initUpload(
					owner.actingUserId(),
					owner.mediaOwnerType(),
					owner.ownerId(),
					fixture.kind(),
					MediaVisibility.PUBLIC,
					fixture.contentType(),
					bytes.length,
					logicalPurpose + "-" + fixture.fileName());
		} catch (RuntimeException exception) {
			throw new SimulationMediaSeedException(
					"Media init failed for " + owner.key() + " (" + logicalPurpose + ")", exception);
		}
		if (initialized == null || initialized.assetId() == null
				|| initialized.uploadUrl() == null || initialized.uploadUrl().isBlank()) {
			throw new SimulationMediaSeedException(
					"Media init returned an incomplete upload contract for " + owner.key());
		}

		try {
			uploadSink.upload(initialized.uploadUrl(), fixture.contentType(), bytes);
		} catch (RuntimeException exception) {
			throw new SimulationMediaSeedException(
					"Media upload failed for " + owner.key() + " (" + logicalPurpose + ")", exception);
		}

		MediaAsset observed = null;
		try {
			observed = mediaAssetService.completeUpload(owner.actingUserId(), initialized.assetId());
		} catch (SoundConnectException exception) {
			if (exception.getErrorType() != ErrorType.MEDIA_ASSET_NOT_READY) {
				throw new SimulationMediaSeedException(
						"Media completion failed for " + owner.key() + " (" + logicalPurpose + ")",
						exception);
			}
		} catch (RuntimeException exception) {
			throw new SimulationMediaSeedException(
					"Media completion failed for " + owner.key() + " (" + logicalPurpose + ")",
					exception);
		}

		MediaAsset ready = isReady(observed)
				? observed
				: awaitTerminal(initialized.assetId(), owner.key(), logicalPurpose);
		assertReadyContract(ready, initialized.assetId(), owner, fixture.kind());
		return ready;
	}

	private MediaAsset awaitTerminal(UUID assetId, String ownerKey, String logicalPurpose) {
		long timeoutNanos = readyTimeout.toNanos();
		long started = System.nanoTime();
		while (System.nanoTime() - started < timeoutNanos) {
			MediaAsset asset = mediaAssetService.getById(assetId);
			if (isReady(asset)) return asset;
			if (asset != null && asset.getStatus() == MediaStatus.FAILED) {
				throw new SimulationMediaSeedException(
						"Media verification reached FAILED for " + ownerKey + " (" + logicalPurpose + ")");
			}
			LockSupport.parkNanos(Math.min(
					pollInterval.toNanos(),
					Math.max(1L, timeoutNanos - (System.nanoTime() - started))));
			if (Thread.currentThread().isInterrupted()) {
				Thread.currentThread().interrupt();
				throw new SimulationMediaSeedException("Media seed was interrupted while awaiting READY");
			}
		}
		throw new SimulationMediaSeedException(
				"Timed out after " + readyTimeout.toSeconds()
						+ "s awaiting READY for " + ownerKey + " (" + logicalPurpose + ")");
	}

	private static boolean isReady(MediaAsset asset) {
		return asset != null && asset.getStatus() == MediaStatus.READY;
	}

	private static void assertReadyContract(
			MediaAsset asset,
			UUID expectedAssetId,
			OwnerTarget owner,
			MediaKind expectedKind
	) {
		if (asset == null
				|| !expectedAssetId.equals(asset.getId())
				|| asset.getStatus() != MediaStatus.READY
				|| asset.getVisibility() != MediaVisibility.PUBLIC
				|| asset.getKind() != expectedKind
				|| asset.getOwnerType() != owner.mediaOwnerType()
				|| !owner.ownerId().equals(asset.getOwnerId())
				|| ((asset.getPlaybackUrl() == null || asset.getPlaybackUrl().isBlank())
						&& (asset.getSourceUrl() == null || asset.getSourceUrl().isBlank()))) {
			throw new SimulationMediaSeedException(
					"READY media contract mismatch for " + owner.key());
		}
	}

	private static SeedPlan plan(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			Map<String, UUID> profileIds,
			Map<String, UUID> bandIds
	) {
		if (manifest == null || manifest.contentTargets() == null) {
			throw new SimulationMediaSeedException("Simulation manifest/content targets are required");
		}
		Map<String, UUID> users = validatedIds(accountUserIds, "accountUserIds");
		Map<String, UUID> profiles = validatedIds(profileIds, "profileIds");
		Map<String, UUID> bands = validatedIds(bandIds, "bandIds");
		int trackCount = bounded(
				manifest.contentTargets().tracks(), 0, MAX_TRACKS, "tracks");
		int publicationCount = bounded(
				manifest.contentTargets().profileMediaPublications(),
				0,
				MAX_PROFILE_PUBLICATIONS,
				"profileMediaPublications");

		List<OwnerTarget> avatarOwners = new ArrayList<>();
		List<OwnerTarget> publicationOwners = new ArrayList<>();
		List<OwnerTarget> trackOwners = new ArrayList<>();
		Map<String, Account> accountsByKey = new LinkedHashMap<>();

		for (Account account : manifest.accounts()) {
			if (account == null || account.key() == null || account.role() == null) {
				throw new SimulationMediaSeedException("Manifest contains an incomplete account");
			}
			if (accountsByKey.putIfAbsent(account.key(), account) != null) {
				throw new SimulationMediaSeedException("Duplicate account key: " + account.key());
			}
			if (!isPubliclyEligible(account)) continue;
			UUID userId = requiredId(users, account.key(), "accountUserIds");
			UUID profileId = requiredId(profiles, account.key(), "profileIds");
			OwnerTarget target = accountOwner(account, userId, profileId);
			if (target.avatarEligible()) avatarOwners.add(target);
			if (target.portfolioEligible()) publicationOwners.add(target);
			if (target.trackEligible() && target.portfolioEligible()) trackOwners.add(target);
		}

		for (Band band : manifest.bands()) {
			if (band == null || band.key() == null || band.ownerAccountKey() == null) {
				throw new SimulationMediaSeedException("Manifest contains an incomplete band");
			}
			Account ownerAccount = accountsByKey.get(band.ownerAccountKey());
			if (ownerAccount == null || !isPubliclyEligible(ownerAccount)) {
				throw new SimulationMediaSeedException(
						"Band has no eligible owner account: " + band.key());
			}
			OwnerTarget target = new OwnerTarget(
					band.key(),
					requiredId(users, band.ownerAccountKey(), "accountUserIds"),
					requiredId(bands, band.key(), "bandIds"),
					MediaOwnerType.BAND,
					ProfileType.BAND,
					cleanDisplayName(band.name(), band.key()),
					true,
					true,
					true);
			avatarOwners.add(target);
			publicationOwners.add(target);
			trackOwners.add(target);
		}

		Comparator<OwnerTarget> byKey = Comparator.comparing(OwnerTarget::key);
		avatarOwners.sort(byKey);
		publicationOwners.sort(byKey);
		trackOwners.sort(byKey);
		if (avatarOwners.size() > MAX_AVATARS) {
			throw new SimulationMediaSeedException("Avatar seed plan exceeds its hard bound");
		}
		if (publicationCount > 0 && publicationOwners.isEmpty()) {
			throw new SimulationMediaSeedException("No eligible profile media owners are available");
		}
		if (trackCount > 0 && trackOwners.isEmpty()) {
			throw new SimulationMediaSeedException("No eligible track owners are available");
		}
		return new SeedPlan(
				List.copyOf(avatarOwners),
				List.copyOf(publicationOwners),
				List.copyOf(trackOwners),
				publicationCount,
				trackCount);
	}

	private static boolean isPubliclyEligible(Account account) {
		if (account.emailVerification() != EmailVerificationState.VERIFIED) return false;
		return switch (account.role()) {
			case MUSICIAN -> account.musicianProfile() != null;
			case LISTENER -> account.listenerVisibility() == ListenerVisibilityState.STANDARD;
			case VENUE, STUDIO -> account.institutionState() == InstitutionState.APPROVED;
		};
	}

	private static OwnerTarget accountOwner(Account account, UUID userId, UUID profileId) {
		return switch (account.role()) {
			case MUSICIAN -> new OwnerTarget(
					account.key(), userId, profileId,
					MediaOwnerType.MUSICIAN_PROFILE, ProfileType.MUSICIAN,
					cleanDisplayName(account.displayName(), account.key()),
					account.musicianProfile().populateProfilePhoto(),
					account.musicianProfile().populatePortfolio(),
					true);
			case LISTENER -> new OwnerTarget(
					account.key(), userId, profileId,
					MediaOwnerType.LISTENER_PROFILE, ProfileType.LISTENER,
					cleanDisplayName(account.displayName(), account.key()),
					true, true, false);
			case VENUE -> new OwnerTarget(
					account.key(), userId, profileId,
					MediaOwnerType.VENUE_PROFILE, ProfileType.VENUE,
					cleanDisplayName(account.displayName(), account.key()),
					true, true, false);
			case STUDIO -> new OwnerTarget(
					account.key(), userId, profileId,
					MediaOwnerType.STUDIO_PROFILE, ProfileType.STUDIO,
					cleanDisplayName(account.displayName(), account.key()),
					true, true, true);
		};
	}

	private static Map<String, UUID> validatedIds(Map<String, UUID> source, String field) {
		if (source == null) throw new SimulationMediaSeedException(field + " is required");
		Map<String, UUID> copy = new LinkedHashMap<>();
		Set<UUID> seen = new HashSet<>();
		for (Map.Entry<String, UUID> entry : source.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
				throw new SimulationMediaSeedException(field + " contains a blank key or null id");
			}
			if (copy.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
				throw new SimulationMediaSeedException(field + " contains a duplicate key");
			}
			if (!seen.add(entry.getValue())) {
				throw new SimulationMediaSeedException(field + " contains a duplicate id");
			}
		}
		return Map.copyOf(copy);
	}

	private static UUID requiredId(Map<String, UUID> ids, String key, String field) {
		UUID id = ids.get(key);
		if (id == null) {
			throw new SimulationMediaSeedException(field + " is missing required key " + key);
		}
		return id;
	}

	private static int bounded(int value, int minimum, int maximum, String field) {
		if (value < minimum || value > maximum) {
			throw new SimulationMediaSeedException(
					field + " must be between " + minimum + " and " + maximum);
		}
		return value;
	}

	private static String cleanDisplayName(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static Duration positive(Duration value, String field) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(field + " must be positive");
		}
		return value;
	}

	private static <T> T require(T value, String field) {
		return Objects.requireNonNull(value, field + " is required");
	}

	private record OwnerTarget(
			String key,
			UUID actingUserId,
			UUID ownerId,
			MediaOwnerType mediaOwnerType,
			ProfileType profileType,
			String displayName,
			boolean avatarEligible,
			boolean portfolioEligible,
			boolean trackEligible
	) {
	}

	private record SeedPlan(
			List<OwnerTarget> avatarOwners,
			List<OwnerTarget> publicationOwners,
			List<OwnerTarget> trackOwners,
			int profilePublicationCount,
			int trackCount
	) {
	}
}
