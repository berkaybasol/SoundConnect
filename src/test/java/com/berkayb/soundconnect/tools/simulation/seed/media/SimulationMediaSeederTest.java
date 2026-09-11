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
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Band;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ContentTargets;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.MusicianProfilePlan;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ObserverProfile;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Scene;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulationMediaSeederTest {

	@TempDir
	Path temporaryDirectory;

	private SimulationRuntimeGuard runtimeGuard;
	private SimulationProperties simulationProperties;
	private SimulationMediaCheckpointStore checkpointStore;
	private Runnable freshStorageReset;
	private MediaAssetService mediaAssetService;
	private SimulationPresignedUploadSink uploadSink;
	private BandService bandService;
	private TrackService trackService;
	private ProfileMediaService profileMediaService;
	private AtomicLong ids;
	private Map<UUID, AssetSpec> assets;
	private Map<UUID, ProfileMedia> profilePublications;
	private Map<UUID, TrackResponseDto> tracks;
	private Map<UUID, UUID> bandAvatarAssets;
	private List<String> lifecycle;

	@BeforeEach
	void setUp() {
		runtimeGuard = mock(SimulationRuntimeGuard.class);
		simulationProperties = new SimulationProperties();
		simulationProperties.setEnabled(true);
		simulationProperties.setMode(SimulationMode.FRESH);
		simulationProperties.setReportDirectory(temporaryDirectory.resolve("reports"));
		checkpointStore = new SimulationMediaCheckpointStore(
				runtimeGuard, simulationProperties, new ObjectMapper(), Clock.systemUTC());
		freshStorageReset = mock(Runnable.class);
		mediaAssetService = mock(MediaAssetService.class);
		uploadSink = mock(SimulationPresignedUploadSink.class);
		bandService = mock(BandService.class);
		trackService = mock(TrackService.class);
		profileMediaService = mock(ProfileMediaService.class);
		ids = new AtomicLong(1);
		assets = new ConcurrentHashMap<>();
		profilePublications = new ConcurrentHashMap<>();
		tracks = new ConcurrentHashMap<>();
		bandAvatarAssets = new ConcurrentHashMap<>();
		lifecycle = java.util.Collections.synchronizedList(new ArrayList<>());
		stubHappyLifecycle();
	}

	@Test
	void canonicalManifestCreatesSeparateAvatarsSixtyPublicationsAndThirtyFourTracks() {
		SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
				.loadDefault();
		Map<String, UUID> userIds = idsForAccounts(manifest);
		Map<String, UUID> profileIds = idsForAccounts(manifest);
		Map<String, UUID> bandIds = idsForBands(manifest);
		SimulationMediaSeeder seeder = seeder(Duration.ofSeconds(1), Duration.ofMillis(1));

		SimulationMediaSeedResult result = seeder.seed(manifest, userIds, profileIds, bandIds);

		assertThat(result.avatarAssetIds()).hasSize(45);
		assertThat(result.avatarAssetIds())
				.doesNotContainKeys("musician-ank-01", "musician-ist-10")
				.containsKeys("musician-ist-01", "listener-ist-01", "venue-ist-01", "studio-ist-01");
		assertThat(result.createdTrackIds()).hasSize(34).doesNotHaveDuplicates();
		assertThat(result.profilePublicationIds()).hasSize(60).doesNotHaveDuplicates();
		assertThat(result.profileMediaAssetIds()).hasSize(60).doesNotHaveDuplicates();
		assertThat(result.trackMediaAssetIds()).hasSize(34).doesNotHaveDuplicates();
		assertThat(result.profileMediaTargets())
				.extracting(SimulationMediaSeedResult.MediaTarget::logicalKey)
				.startsWith("profile-media-001", "profile-media-002")
				.endsWith("profile-media-060");
		assertThat(result.trackMediaTargets())
				.extracting(SimulationMediaSeedResult.MediaTarget::logicalKey)
				.startsWith("track-001")
				.endsWith("track-034");
		assertThat(assets).hasSize(45 + 60 + 34);

		for (UUID assetId : assets.keySet()) {
			int init = lifecycle.indexOf("init:" + assetId);
			int upload = lifecycle.indexOf("upload:" + assetId);
			int complete = lifecycle.indexOf("complete:" + assetId);
			assertThat(init).isGreaterThanOrEqualTo(0);
			assertThat(upload).isGreaterThan(init);
			assertThat(complete).isGreaterThan(upload);
		}

		ArgumentCaptor<ProfileType> profileTypes = ArgumentCaptor.forClass(ProfileType.class);
		ArgumentCaptor<MediaOwnerType> ownerTypes = ArgumentCaptor.forClass(MediaOwnerType.class);
		verify(profileMediaService, org.mockito.Mockito.times(60)).addMedia(
				any(), profileTypes.capture(), any(), any(), any(), anyInt());
		verify(mediaAssetService, org.mockito.Mockito.times(139)).initUpload(
				any(), ownerTypes.capture(), any(), any(), eq(MediaVisibility.PUBLIC),
				anyString(), anyLong(), anyString());
		assertThat(profileTypes.getAllValues()).contains(ProfileType.VENUE, ProfileType.BAND);
		assertThat(ownerTypes.getAllValues()).contains(
				MediaOwnerType.VENUE_PROFILE,
				MediaOwnerType.BAND,
				MediaOwnerType.MUSICIAN_PROFILE,
				MediaOwnerType.STUDIO_PROFILE,
				MediaOwnerType.LISTENER_PROFILE);
		verify(bandService, org.mockito.Mockito.times(manifest.bands().size()))
				.updateBand(any(), any(), any());
		assertThat(bandAvatarAssets.keySet()).containsExactlyInAnyOrderElementsOf(bandIds.values());
	}

	@Test
	void resumeStrictlyValidatesAndReturnsExactCheckpointWithoutNewMediaWrites() {
		SimulationWorldManifest manifest = oneTrackManifest();
		Map<String, UUID> userIds = Map.of("musician-test", id());
		Map<String, UUID> profileIds = Map.of("musician-test", id());
		SimulationMediaSeeder fresh = seeder(Duration.ofSeconds(1), Duration.ofMillis(1));
		SimulationMediaSeedResult created = fresh.seed(manifest, userIds, profileIds, Map.of());

		clearInvocations(
				runtimeGuard,
				mediaAssetService,
				uploadSink,
				bandService,
				trackService,
				profileMediaService,
				freshStorageReset);
		simulationProperties.setMode(SimulationMode.RESUME);
		SimulationMediaSeeder resumed = seeder(Duration.ofSeconds(1), Duration.ofMillis(1));

		SimulationMediaSeedResult loaded = resumed.seedOrResume(
				manifest, userIds, profileIds, Map.of(), SimulationMode.RESUME);

		assertThat(loaded).isEqualTo(created);
		verify(mediaAssetService, never()).initUpload(
				any(), any(), any(), any(), any(), any(), anyLong(), any());
		verify(uploadSink, never()).upload(anyString(), anyString(), any(byte[].class));
		verify(trackService, never()).createTrack(any(), any(), any());
		verify(profileMediaService, never()).addMedia(
				any(), any(), any(), any(), any(), anyInt());
		verify(bandService, never()).updateBand(any(), any(), any());
		verify(freshStorageReset, never()).run();
		verify(mediaAssetService).getById(created.trackMediaAssetIds().getFirst());
		verify(trackService).getTrackById(created.createdTrackIds().getFirst());
	}

	@Test
	void freshAlwaysDiscardsStaleCheckpointAndRematerializesAfterCallerDatabaseReset()
			throws Exception {
		SimulationWorldManifest manifest = oneTrackManifest();
		Map<String, UUID> userIds = Map.of("musician-test", id());
		Map<String, UUID> profileIds = Map.of("musician-test", id());
		SimulationMediaSeedResult first = seeder(Duration.ofSeconds(1), Duration.ofMillis(1))
				.seed(manifest, userIds, profileIds, Map.of());

		// A FRESH runner truncates the database before invoking the media slice.
		assets.clear();
		profilePublications.clear();
		tracks.clear();
		bandAvatarAssets.clear();
		Files.writeString(
				checkpointStore.checkpointPath(manifest),
				"not-a-readable-checkpoint",
				StandardCharsets.UTF_8);
		doAnswer(invocation -> {
			assertThat(checkpointStore.checkpointPath(manifest)).doesNotExist();
			return null;
		}).when(freshStorageReset).run();

		SimulationMediaSeedResult rebuilt = seeder(Duration.ofSeconds(1), Duration.ofMillis(1))
				.seed(manifest, userIds, profileIds, Map.of());

		assertThat(rebuilt).isNotEqualTo(first);
		assertThat(checkpointStore.loadIfPresent(manifest).orElseThrow().result())
				.isEqualTo(rebuilt);
		verify(freshStorageReset, org.mockito.Mockito.times(2)).run();
		verify(mediaAssetService, org.mockito.Mockito.times(2)).initUpload(
				any(), any(), any(), any(), any(), any(), anyLong(), any());
	}

	@Test
	void freshCannotRunTwiceInTheSameSeederRuntime() {
		SimulationWorldManifest manifest = oneTrackManifest();
		Map<String, UUID> userIds = Map.of("musician-test", id());
		Map<String, UUID> profileIds = Map.of("musician-test", id());
		SimulationMediaSeeder seeder = seeder(Duration.ofSeconds(1), Duration.ofMillis(1));
		seeder.seed(manifest, userIds, profileIds, Map.of());

		assertThatThrownBy(() -> seeder.seed(manifest, userIds, profileIds, Map.of()))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("already attempted");
		verify(freshStorageReset).run();
		verify(mediaAssetService).initUpload(
				any(), any(), any(), any(), any(), any(), anyLong(), any());
	}

	@Test
	void resumeFailsClosedWhenCheckpointAssetIsNoLongerReady() {
		SimulationWorldManifest manifest = oneTrackManifest();
		Map<String, UUID> userIds = Map.of("musician-test", id());
		Map<String, UUID> profileIds = Map.of("musician-test", id());
		SimulationMediaSeedResult created = seeder(Duration.ofSeconds(1), Duration.ofMillis(1))
				.seed(manifest, userIds, profileIds, Map.of());
		UUID assetId = created.trackMediaAssetIds().getFirst();
		when(mediaAssetService.getById(assetId))
				.thenReturn(asset(assetId, assets.get(assetId), MediaStatus.FAILED));
		simulationProperties.setMode(SimulationMode.RESUME);

		assertThatThrownBy(() -> seeder(Duration.ofSeconds(1), Duration.ofMillis(1))
				.seed(manifest, userIds, profileIds, Map.of()))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("READY media contract mismatch");
		verify(freshStorageReset, org.mockito.Mockito.times(1)).run();
	}

	@Test
	void bandAvatarIsAttachedThroughBandServiceAndStrictlyValidatedOnResume() {
		SimulationWorldManifest manifest = oneBandAvatarManifest();
		UUID userId = id();
		UUID profileId = id();
		UUID bandId = id();
		Map<String, UUID> userIds = Map.of("musician-test", userId);
		Map<String, UUID> profileIds = Map.of("musician-test", profileId);
		Map<String, UUID> bandIds = Map.of("band-test", bandId);
		SimulationMediaSeedResult created = seeder(Duration.ofSeconds(1), Duration.ofMillis(1))
				.seed(manifest, userIds, profileIds, bandIds);
		UUID avatarId = created.avatarAssetIds().get("band-test");

		assertThat(avatarId).isNotNull();
		verify(bandService).updateBand(eq(bandId), eq(userId), any());
		clearInvocations(bandService, mediaAssetService, freshStorageReset);
		simulationProperties.setMode(SimulationMode.RESUME);
		SimulationMediaSeeder resumed = seeder(Duration.ofSeconds(1), Duration.ofMillis(1));

		assertThat(resumed.seed(manifest, userIds, profileIds, bandIds)).isEqualTo(created);
		verify(bandService).getBandById(bandId, userId);
		verify(bandService, never()).updateBand(any(), any(), any());
		verify(freshStorageReset, never()).run();

		bandAvatarAssets.put(bandId, id());
		assertThatThrownBy(() -> resumed.seed(manifest, userIds, profileIds, bandIds))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("Band avatar binding");
	}

	@Test
	void missingRequiredIdentityFailsPreflightBeforeAnyMediaSideEffect() {
		SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
				.loadDefault();
		Map<String, UUID> userIds = idsForAccounts(manifest);
		Map<String, UUID> profileIds = new LinkedHashMap<>(idsForAccounts(manifest));
		profileIds.remove("venue-ist-01");

		assertThatThrownBy(() -> seeder(Duration.ofSeconds(1), Duration.ofMillis(1)).seed(
				manifest, userIds, profileIds, idsForBands(manifest)))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("profileIds")
				.hasMessageContaining("venue-ist-01");
		verifyNoInteractions(
				mediaAssetService, uploadSink, bandService, trackService, profileMediaService);
	}

	@Test
	void asynchronousVerificationTimeoutResponseIsPolledUntilReadyBeforeTrackAttach() {
		resetHappyLifecycleStubs();
		SimulationWorldManifest manifest = oneTrackManifest();
		UUID userId = id();
		UUID profileId = id();
		UUID assetId = id();
		AssetSpec spec = new AssetSpec(
				userId, MediaOwnerType.MUSICIAN_PROFILE, profileId, MediaKind.AUDIO);
		assets.put(assetId, spec);
		when(mediaAssetService.initUpload(any(), any(), any(), any(), any(), any(), anyLong(), any()))
				.thenReturn(new UploadInitResultResponseDto(assetId, "simulation-upload://local/" + assetId));
		when(mediaAssetService.completeUpload(userId, assetId))
				.thenThrow(new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY));
		when(mediaAssetService.getById(assetId))
				.thenReturn(asset(assetId, spec, MediaStatus.VERIFYING), readyAsset(assetId, spec));
		when(trackService.createTrack(eq(profileId), eq(userId), any()))
				.thenReturn(new TrackResponseDto(id(), assetId, "demo", "simulation-media://local/x", 1, 80));

		SimulationMediaSeedResult result = seeder(Duration.ofSeconds(1), Duration.ofMillis(1)).seed(
				manifest,
				Map.of("musician-test", userId),
				Map.of("musician-test", profileId),
				Map.of());

		assertThat(result.createdTrackIds()).hasSize(1);
		assertThat(result.avatarAssetIds()).isEmpty();
		assertThat(result.profilePublicationIds()).isEmpty();
		assertThat(result.trackMediaTargets()).singleElement()
				.satisfies(target -> {
					assertThat(target.assetId()).isEqualTo(assetId);
					assertThat(target.ownerKey()).isEqualTo("musician-test");
				});
		verify(mediaAssetService, org.mockito.Mockito.times(2)).getById(assetId);
		verify(trackService).createTrack(eq(profileId), eq(userId), any());
	}

	@Test
	void failedVerificationNeverCreatesPublicationOrTrack() {
		resetHappyLifecycleStubs();
		SimulationWorldManifest manifest = oneTrackManifest();
		UUID userId = id();
		UUID profileId = id();
		UUID assetId = id();
		AssetSpec spec = new AssetSpec(
				userId, MediaOwnerType.MUSICIAN_PROFILE, profileId, MediaKind.AUDIO);
		when(mediaAssetService.initUpload(any(), any(), any(), any(), any(), any(), anyLong(), any()))
				.thenReturn(new UploadInitResultResponseDto(assetId, "simulation-upload://local/" + assetId));
		when(mediaAssetService.completeUpload(userId, assetId))
				.thenReturn(asset(assetId, spec, MediaStatus.FAILED));
		when(mediaAssetService.getById(assetId)).thenReturn(asset(assetId, spec, MediaStatus.FAILED));

		assertThatThrownBy(() -> seeder(Duration.ofMillis(20), Duration.ofMillis(1)).seed(
				manifest,
				Map.of("musician-test", userId),
				Map.of("musician-test", profileId),
				Map.of()))
				.isInstanceOf(SimulationMediaSeedException.class)
				.hasMessageContaining("FAILED");
		verify(trackService, never()).createTrack(any(), any(), any());
		verifyNoInteractions(profileMediaService);
	}

	private void stubHappyLifecycle() {
		when(mediaAssetService.initUpload(any(), any(), any(), any(), any(), any(), anyLong(), any()))
				.thenAnswer(invocation -> {
					UUID assetId = id();
					AssetSpec spec = new AssetSpec(
							invocation.getArgument(0),
							invocation.getArgument(1),
							invocation.getArgument(2),
							invocation.getArgument(3));
					assets.put(assetId, spec);
					lifecycle.add("init:" + assetId);
					return new UploadInitResultResponseDto(
							assetId, "simulation-upload://local/" + assetId);
				});
		doAnswer(invocation -> {
			String url = invocation.getArgument(0);
			lifecycle.add("upload:" + url.substring(url.lastIndexOf('/') + 1));
			return null;
		}).when(uploadSink).upload(anyString(), anyString(), any(byte[].class));
		when(mediaAssetService.completeUpload(any(), any())).thenAnswer(invocation -> {
			UUID assetId = invocation.getArgument(1);
			lifecycle.add("complete:" + assetId);
			return readyAsset(assetId, assets.get(assetId));
		});
		when(mediaAssetService.getById(any())).thenAnswer(invocation -> {
			UUID assetId = invocation.getArgument(0);
			AssetSpec spec = assets.get(assetId);
			return spec == null ? null : readyAsset(assetId, spec);
		});
		when(profileMediaService.addMedia(any(), any(), any(), any(), any(), anyInt()))
				.thenAnswer(invocation -> {
					ProfileMedia media = ProfileMedia.builder()
							.profileType(invocation.getArgument(1))
							.profileId(invocation.getArgument(2))
							.mediaAssetId(invocation.getArgument(3))
							.role(invocation.getArgument(4))
							.orderIndex(invocation.getArgument(5))
							.build();
					media.setId(id());
					profilePublications.put(media.getId(), media);
					return media;
				});
		when(profileMediaService.getMediaList(any(), any(), any())).thenAnswer(invocation ->
				profilePublications.values().stream()
						.filter(media -> media.getProfileType() == invocation.getArgument(0))
						.filter(media -> media.getProfileId().equals(invocation.getArgument(1)))
						.filter(media -> media.getRole() == invocation.getArgument(2))
						.toList());
		when(trackService.createTrack(any(), any(), any())).thenAnswer(invocation -> {
			var dto = (com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto)
					invocation.getArgument(2);
			TrackResponseDto track = new TrackResponseDto(
					id(), dto.mediaAssetId(), dto.title(), "simulation-media://local/track",
					dto.durationSeconds(), dto.bpm());
			tracks.put(track.id(), track);
			return track;
		});
		when(trackService.getTrackById(any())).thenAnswer(invocation ->
				tracks.get(invocation.<UUID>getArgument(0)));
		when(bandService.updateBand(any(), any(), any())).thenAnswer(invocation -> {
			UUID bandId = invocation.getArgument(0);
			BandCreateRequestDto update = invocation.getArgument(2);
			bandAvatarAssets.put(bandId, update.profilePicture());
			return bandResponse(bandId, update.profilePicture());
		});
		when(bandService.getBandById(any(), any())).thenAnswer(invocation -> {
			UUID bandId = invocation.getArgument(0);
			UUID avatarId = bandAvatarAssets.get(bandId);
			return avatarId == null ? null : bandResponse(bandId, avatarId);
		});
	}

	private void resetHappyLifecycleStubs() {
		org.mockito.Mockito.reset(
				mediaAssetService, uploadSink, bandService, trackService, profileMediaService);
		doAnswer(invocation -> null).when(uploadSink)
				.upload(anyString(), anyString(), any(byte[].class));
	}

	private SimulationMediaSeeder seeder(Duration timeout, Duration poll) {
		return new SimulationMediaSeeder(
				runtimeGuard,
				simulationProperties,
				checkpointStore,
				freshStorageReset,
				mediaAssetService,
				uploadSink,
				bandService,
				trackService,
				profileMediaService,
				new SimulationMediaFixtures(),
				timeout,
				poll);
	}

	private Map<String, UUID> idsForAccounts(SimulationWorldManifest manifest) {
		Map<String, UUID> result = new LinkedHashMap<>();
		manifest.accounts().forEach(account -> result.put(account.key(), id()));
		return result;
	}

	private Map<String, UUID> idsForBands(SimulationWorldManifest manifest) {
		Map<String, UUID> result = new LinkedHashMap<>();
		manifest.bands().forEach(band -> result.put(band.key(), id()));
		return result;
	}

	private UUID id() {
		return new UUID(0L, ids.getAndIncrement());
	}

	private static MediaAsset readyAsset(UUID assetId, AssetSpec spec) {
		return asset(assetId, spec, MediaStatus.READY);
	}

	private static BandResponseDto bandResponse(UUID bandId, UUID avatarId) {
		return new BandResponseDto(
				bandId,
				"Simulation Band",
				null,
				avatarId,
				"http://10.0.2.2:8080/api/v1/public/simulation-media/avatar",
				null,
				null,
				null,
				null,
				null,
				List.of(),
				Set.of(),
				true);
	}

	private static MediaAsset asset(UUID assetId, AssetSpec spec, MediaStatus status) {
		MediaAsset asset = MediaAsset.builder()
				.kind(spec.kind())
				.status(status)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(spec.ownerType())
				.ownerId(spec.ownerId())
				.mimeType(spec.kind() == MediaKind.IMAGE ? "image/png" : "audio/wav")
				.size(128L)
				.sourceUrl(status == MediaStatus.READY ? "simulation-media://local/source" : null)
				.playbackUrl(status == MediaStatus.READY ? "simulation-media://local/source" : null)
				.build();
		asset.setId(assetId);
		return asset;
	}

	private static SimulationWorldManifest oneTrackManifest() {
		Account musician = new Account(
				"musician-test",
				AccountRole.MUSICIAN,
				Scene.ISTANBUL_ALTERNATIVE_ROCK,
				"musiciantest",
				"musician-test@soundconnect.invalid",
				EmailVerificationState.VERIFIED,
				"Test",
				"Musician",
				"Test Musician",
				"A simulation musician with one real lifecycle track.",
				"Focused media lifecycle test account",
				List.of("Gitar"),
				new MusicianProfilePlan(true, true, true, false, true, true),
				ObserverProfile.NONE,
				null,
				null,
				new SimulationWorldManifest.Location("İstanbul", "Kadıköy", "Caferağa", null),
				null);
		return new SimulationWorldManifest(
				1,
				"test-world",
				1L,
				List.of(),
				new ContentTargets(0, 0, 1, 0, 0, 0, 0, 0, 0, 0),
				List.of(musician),
				List.of());
	}

	private static SimulationWorldManifest oneBandAvatarManifest() {
		Account musician = new Account(
				"musician-test",
				AccountRole.MUSICIAN,
				Scene.ISTANBUL_ALTERNATIVE_ROCK,
				"musiciantest",
				"musician-test@soundconnect.invalid",
				EmailVerificationState.VERIFIED,
				"Test",
				"Musician",
				"Test Musician",
				"A simulation band founder.",
				"Focused band avatar lifecycle test account",
				List.of("Gitar"),
				new MusicianProfilePlan(true, true, true, false, false, true),
				ObserverProfile.NONE,
				null,
				null,
				new SimulationWorldManifest.Location("İstanbul", "Kadıköy", "Caferağa", null),
				null);
		Band band = new Band(
				"band-test",
				"Simulation Band",
				"A deterministic test band.",
				"band-avatar-control",
				Scene.ISTANBUL_ALTERNATIVE_ROCK,
				"musician-test",
				List.of());
		return new SimulationWorldManifest(
				1,
				"band-avatar-test-world",
				2L,
				List.of(),
				new ContentTargets(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				List.of(musician),
				List.of(band));
	}

	private record AssetSpec(
			UUID actingUserId,
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind kind
	) {
	}
}
