package com.berkayb.soundconnect.modules.profile.StudioProfille.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.mapper.StudioProfileMapper;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileServiceImpl;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileTransactionExecutor;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("service")
class StudioProfileServiceImplTest {
	
	@Mock private StudioProfileRepository repository;
	@Mock private UserEntityFinder userEntityFinder;
	@Mock private StudioProfileMapper mapper;
	@Mock private MediaAssetService mediaAssetService;
	@Mock private StudioRoomRepository studioRoomRepository;
	@Mock private SpotifyService spotifyService;
	@Spy private StudioProfileTransactionExecutor transactionExecutor = new StudioProfileTransactionExecutor();
	
	@InjectMocks
	private StudioProfileServiceImpl service;
	
	private UUID userId;
	private User user;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		user = new User();
		user.setId(userId);
	}
	
	private StudioProfileSaveRequestDto sampleReq() {
		return new StudioProfileSaveRequestDto(
				"My Studio",
				"Great rooms",     // descpriction (DTO'da yazım böyle)
				UUID.randomUUID(),
				"Main Ave 42",     // adress (DTO'da yazım böyle)
				"05551234567",
				"https://studio.com",
				new HashSet<>(List.of("Piano","Drums")),
				"https://insta.com/x",
				"https://youtube.com/y"
		);
	}
	
	@Test
	void createProfile_ok() {
		var req = sampleReq();
		
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.empty());
		
		var saved = new StudioProfile();
		saved.setId(UUID.randomUUID());
		
		when(repository.saveAndFlush(any(StudioProfile.class))).thenReturn(saved);
		
		var resp = new StudioProfileResponseDto(
				saved.getId(),
				userId,
				req.name(),
				req.descpriction(),
				req.profilePicture(),
				null,
				req.adress(),
				req.phone(),
				req.website(),
				req.facilities(),
				req.instagramUrl(),
				req.youtubeUrl()
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		var result = service.createProfile(userId, req);
		
		assertThat(result).isEqualTo(resp);
		ArgumentCaptor<StudioProfile> profileCaptor = ArgumentCaptor.forClass(StudioProfile.class);
		verify(repository, times(2)).saveAndFlush(profileCaptor.capture());
		assertThat(profileCaptor.getAllValues().getFirst().getName()).isEqualTo("my studio");
		assertThat(profileCaptor.getAllValues().getFirst().getPhone()).isEqualTo("05551234567");
		assertThat(profileCaptor.getAllValues().getFirst().getWebsite()).isEqualTo("https://studio.com");
		assertThat(profileCaptor.getAllValues().getFirst().getInstagramUrl()).isEqualTo("https://insta.com/x");
	}
	
	@Test
	void createProfile_should_throw_when_duplicate() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.of(new StudioProfile()));
		
		assertThatThrownBy(() -> service.createProfile(userId, sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_ALREADY_EXISTS));
	}
	
	@Test
	void getProfileByUserId_ok() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		var profile = new StudioProfile();
		profile.setId(UUID.randomUUID());
		when(repository.findByUserId(userId)).thenReturn(Optional.of(profile));
		
		var resp = new StudioProfileResponseDto(
				profile.getId(), userId, "Name", "Desc", UUID.randomUUID(), null,
				"Addr", "05551234567", "https://site.example", Set.of("A"),
				"https://instagram.com/x", "https://youtube.com/y"
		);
		when(mapper.toDto(profile)).thenReturn(resp);
		
		var result = service.getProfileByUserId(userId);
		
		assertThat(result).isEqualTo(resp);
		verify(repository).findByUserId(userId);
	}
	
	@Test
	void getProfileByUserId_should_throw_when_not_found() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserId(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.getProfileByUserId(userId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Test
	void updateProfile_ok() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		
		var existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
		
		var req = sampleReq();
		
		var saved = new StudioProfile();
		saved.setId(existing.getId());
		when(repository.saveAndFlush(any(StudioProfile.class))).thenReturn(saved);
		
		var resp = new StudioProfileResponseDto(
				saved.getId(),
				userId,
				req.name(), req.descpriction(), req.profilePicture(),
				null,
				req.adress(), req.phone(), req.website(),
				req.facilities(), req.instagramUrl(), req.youtubeUrl()
		);
		when(mapper.toDto(saved)).thenReturn(resp);
		
		var result = service.updateProfile(userId, req);
		
		assertThat(result).isEqualTo(resp);
		ArgumentCaptor<StudioProfile> profileCaptor = ArgumentCaptor.forClass(StudioProfile.class);
		verify(repository).saveAndFlush(profileCaptor.capture());
		assertThat(profileCaptor.getValue().getName()).isEqualTo("my studio");
	}

	@Test
	void publicResponseOmitsUnsafeLegacyContactValuesAndNormalizesBareHosts() {
		UUID profileId = UUID.randomUUID();
		StudioProfile profile = new StudioProfile();
		profile.setId(profileId);
		when(repository.findById(profileId)).thenReturn(Optional.of(profile));
		when(mapper.toDto(profile)).thenReturn(new StudioProfileResponseDto(
				profileId, userId, "Studio", null, null, null,
				null, "*#06#", "javascript:alert(1)", Set.of(),
				"instagram.com/studio", "data:text/html,bad"
		));

		StudioProfileResponseDto result = service.getProfileByProfileId(profileId);

		assertThat(result.phone()).isNull();
		assertThat(result.website()).isNull();
		assertThat(result.instagramUrl()).isEqualTo("https://instagram.com/studio");
		assertThat(result.youtubeUrl()).isNull();
	}

	@Test
	void publicResponseUsesTheSameSafeTimeZoneFallbackAsStudioScheduling() {
		UUID profileId = UUID.randomUUID();
		StudioProfile profile = new StudioProfile();
		profile.setId(profileId);
		profile.setTimeZone("Mars/Olympus");
		when(repository.findById(profileId)).thenReturn(Optional.of(profile));
		when(mapper.toDto(profile)).thenReturn(new StudioProfileResponseDto(
				profileId, userId, "Studio", null, null, null,
				null, null, null, Set.of(), null, null
		));

		StudioProfileResponseDto result = service.getProfileByProfileId(profileId);

		assertThat(result.timeZone()).isEqualTo("Europe/Istanbul");
	}

	@Test
	void createProfile_requiresAStudioName() {
		var request = new StudioProfileSaveRequestDto(
				"  ", "Description", null, "Address", "555", null,
				Set.of(), null, null
		);

		assertThatThrownBy(() -> service.createProfile(userId, request))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(exception -> assertThat(((SoundConnectException) exception).getErrorType())
						.isEqualTo(ErrorType.BAD_REQUEST));
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void updateProfile_rejectsProvidedWhitespaceOnlyName() {
		StudioProfile existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
		var request = new StudioProfileSaveRequestDto(
				"  ", null, null, null, null, null,
				null, null, null
		);

		assertThatThrownBy(() -> service.updateProfile(userId, request))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(exception -> assertThat(((SoundConnectException) exception).getErrorType())
						.isEqualTo(ErrorType.BAD_REQUEST));
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void updateProfile_rejectsTimeZoneChangesAfterTheFirstRoomExists() {
		StudioProfile existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		existing.setTimeZone("Europe/Istanbul");
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
		when(studioRoomRepository.existsByStudioProfileId(existing.getId())).thenReturn(true);
		var request = new StudioProfileSaveRequestDto(
				null, null, null, null, null, null, null, null, null,
				"UTC", null, null, null
		);

		assertThatThrownBy(() -> service.updateProfile(userId, request))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(exception -> assertThat(((SoundConnectException) exception).getErrorType())
						.isEqualTo(ErrorType.STUDIO_TIME_ZONE_LOCKED));
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void updateProfileRejectsDialerAndWebSchemeInjectionBeforePersistence() {
		StudioProfile existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
		var maliciousPhone = new StudioProfileSaveRequestDto(
				null, null, null, null, "*#06#", null,
				null, null, null
		);

		assertThatThrownBy(() -> service.updateProfile(userId, maliciousPhone))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));

		var maliciousWebsite = new StudioProfileSaveRequestDto(
				null, null, null, null, null, "javascript:alert(1)",
				null, null, null
		);
		assertThatThrownBy(() -> service.updateProfile(userId, maliciousWebsite))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void updateProfile_ignoresClientMetadataAndStoresAuthoritativeSpotifySnapshot() {
		String trackId = "4uLU6hMCjMI75M1A2tKUQC";
		StudioProfile existing = new StudioProfile();
		existing.setId(UUID.randomUUID());
		existing.setTimeZone("Europe/Istanbul");
		SpotifyTrackItemDto malicious = new SpotifyTrackItemDto(
				trackId, "Fake title", 1, false, "javascript:alert(1)",
				"https://evil.example", "Fake album", "https://evil.example/image",
				List.of("Fake artist")
		);
		SpotifyTrackItemDto authoritative = new SpotifyTrackItemDto(
				trackId, "Never Gonna Give You Up", 213_000, false, null,
				"https://open.spotify.com/track/" + trackId, "Whenever You Need Somebody",
				"https://i.scdn.co/image/test", List.of("Rick Astley")
		);
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
		when(spotifyService.getTracksByIds(List.of(trackId))).thenReturn(List.of(authoritative));
		when(repository.saveAndFlush(existing)).thenReturn(existing);
		when(mapper.toDto(existing)).thenReturn(new StudioProfileResponseDto(
				existing.getId(), userId, "Studio", null, null, null,
				null, null, null, Set.of(), null, null
		));
		var request = new StudioProfileSaveRequestDto(
				null, null, null, null, null, null, null, null, null,
				null, null, List.of(trackId), List.of(malicious)
		);

		service.updateProfile(userId, request);

		assertThat(existing.getSpotifyTrackIds()).containsExactly(trackId);
		assertThat(existing.getSpotifyTracks()).containsExactly(authoritative);
		assertThat(existing.getSpotifyTracks()).doesNotContain(malicious);
		InOrder order = inOrder(spotifyService, transactionExecutor, repository);
		order.verify(spotifyService).getTracksByIds(List.of(trackId));
		order.verify(transactionExecutor).execute(any());
		order.verify(repository).findByUserIdForUpdate(userId);
	}
	
	@Test
	void updateProfile_should_throw_when_not_found() {
		when(userEntityFinder.getUser(userId)).thenReturn(user);
		when(repository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.updateProfile(userId, sampleReq()))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(ex -> assertThat(((SoundConnectException) ex).getErrorType())
						.isEqualTo(ErrorType.PROFILE_NOT_FOUND));
	}
}
