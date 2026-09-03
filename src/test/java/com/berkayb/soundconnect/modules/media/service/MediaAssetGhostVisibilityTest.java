package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetGhostVisibilityTest {

	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock ListenerVisibilityPolicy listenerVisibilityPolicy;
	@InjectMocks MediaAssetServiceImpl service;

	@Test
	void ghostListenerRawPublicListingsAreEmptyWithoutQueryingAssets() {
		UUID profileId = UUID.randomUUID();
		PageRequest pageable = PageRequest.of(1, 12);
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestrictedProfile(profileId))
				.thenReturn(true);

		Page<MediaAsset> all = service.listPublicByOwner(
				MediaOwnerType.LISTENER_PROFILE, profileId, pageable);
		Page<MediaAsset> images = service.listPublicByOwnerAndKind(
				MediaOwnerType.LISTENER_PROFILE, profileId, MediaKind.IMAGE, pageable);

		assertThat(all).isEmpty();
		assertThat(all.getPageable()).isEqualTo(pageable);
		assertThat(images).isEmpty();
		assertThat(images.getPageable()).isEqualTo(pageable);
		verifyNoInteractions(mediaAssetRepository);
	}

	@Test
	void ghostUserOwnedFallbackListingIsAlsoHidden() {
		UUID userId = UUID.randomUUID();
		PageRequest pageable = PageRequest.of(0, 10);
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestricted(userId)).thenReturn(true);

		assertThat(service.listPublicByOwner(MediaOwnerType.USER, userId, pageable)).isEmpty();
		assertThat(service.listPublicByOwnerAndKind(
				MediaOwnerType.USER, userId, MediaKind.IMAGE, pageable)).isEmpty();
		verifyNoInteractions(mediaAssetRepository);
	}

	@Test
	void standardListenerRawPublicListingPreservesExistingBehavior() {
		UUID profileId = UUID.randomUUID();
		PageRequest pageable = PageRequest.of(0, 10);
		MediaAsset image = publicImage(profileId);
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestrictedProfile(profileId))
				.thenReturn(false);
		when(mediaAssetRepository.findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
				MediaOwnerType.LISTENER_PROFILE,
				profileId,
				MediaVisibility.PUBLIC,
				MediaStatus.READY,
				pageable
		)).thenReturn(new PageImpl<>(List.of(image), pageable, 1));

		assertThat(service.listPublicByOwner(
				MediaOwnerType.LISTENER_PROFILE, profileId, pageable).getContent())
				.containsExactly(image);
	}

	@Test
	void identityCanStillResolveGhostAvatarByItsExactAssetId() {
		UUID profileId = UUID.randomUUID();
		MediaAsset avatar = publicImage(profileId);
		when(mediaAssetRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

		assertThat(service.getDisplayUrl(avatar.getId()))
				.isEqualTo("https://cdn.example/avatar-thumbnail.jpg");
		verifyNoInteractions(listenerVisibilityPolicy);
	}

	private MediaAsset publicImage(UUID profileId) {
		return MediaAsset.builder()
				.id(UUID.randomUUID())
				.ownerType(MediaOwnerType.LISTENER_PROFILE)
				.ownerId(profileId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.playbackUrl("https://cdn.example/avatar.jpg")
				.thumbnailUrl("https://cdn.example/avatar-thumbnail.jpg")
				.build();
	}
}
