package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerProfileContributorTest {

	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock MediaAssetService mediaAssetService;

	private ListenerProfileContributor contributor;

	@BeforeEach
	void setUp() {
		contributor = new ListenerProfileContributor(listenerProfileRepository, mediaAssetService);
	}

	@Test
	void ghostIdentityUsesUsernameAndKeepsAvatarWithoutExposingProfileCopy() {
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID avatarId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(profileId)
				.user(User.builder().id(userId).username("ghosthandle").build())
				.name("Hidden Display Name")
				.description("Hidden biography")
				.profilePictureMediaId(avatarId)
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.visibilityChoiceCompleted(true)
				.build();
		when(listenerProfileRepository.findForPublicIdentityByUserId(userId))
				.thenReturn(Optional.of(profile));
		when(mediaAssetService.getDisplayUrl(avatarId))
				.thenReturn("https://cdn.example/ghost-avatar.jpg");

		assertThat(contributor.resolve(userId))
				.singleElement()
				.satisfies(target -> {
					assertThat(target.type()).isEqualTo("LISTENER");
					assertThat(target.profileId()).isEqualTo(profileId);
					assertThat(target.displayName()).isEqualTo("ghosthandle");
					assertThat(target.profilePictureUrl())
							.isEqualTo("https://cdn.example/ghost-avatar.jpg");
					assertThat(target.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
				});
	}

	@Test
	void standardIdentityPreservesExistingDisplayNameBehavior() {
		UUID userId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(User.builder().id(userId).username("listenerhandle").build())
				.name("Listener Name")
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(true)
				.build();
		when(listenerProfileRepository.findForPublicIdentityByUserId(userId))
				.thenReturn(Optional.of(profile));

		assertThat(contributor.resolve(userId))
				.singleElement()
				.extracting(UserProfileTargetDto::displayName, UserProfileTargetDto::visibilityMode)
				.containsExactly("Listener Name", null);
	}

	@Test
	void pendingChoiceHasNoPublicIdentityEvenIfARepositoryDoubleReturnsIt() {
		UUID userId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(User.builder().id(userId).username("pending").build())
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build();
		when(listenerProfileRepository.findForPublicIdentityByUserId(userId))
				.thenReturn(Optional.of(profile));

		assertThat(contributor.resolve(userId)).isEmpty();
	}
}
