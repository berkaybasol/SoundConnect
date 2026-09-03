package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OverthinkingPostMapperGhostIdentityTest {

	private final OverthinkingPostMapper mapper = Mappers.getMapper(OverthinkingPostMapper.class);

	@Test
	void visibleGhostAuthorUsesCanonicalListenerUsernameAndAvatar() {
		UUID authorId = UUID.randomUUID();
		User author = User.builder()
				.id(authorId)
				.username("alternate-profile-name")
				.profilePicture("alternate-avatar.png")
				.build();
		OverthinkingPost post = post(author, OverthinkingVisibilityType.VISIBLE);
		GhostListenerIdentity identity = ghostIdentity(authorId);

		OverthinkingPostResponseDto dto = mapper.toDto(
				post,
				true,
				3,
				2,
				true,
				null,
				identity
		);

		assertThat(dto.authorId()).isEqualTo(authorId);
		assertThat(dto.authorUsername()).isEqualTo("listener-handle");
		assertThat(dto.authorAvatarUrl()).isEqualTo("listener-avatar.png");
		assertThat(dto.authorVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void maskedAuthorNeverLeaksGhostIdentityFields() {
		UUID authorId = UUID.randomUUID();
		User author = User.builder().id(authorId).username("real-handle").build();
		OverthinkingPost post = post(author, OverthinkingVisibilityType.ANONYMOUS);

		OverthinkingPostResponseDto dto = mapper.toDto(
				post,
				false,
				0,
				0,
				false,
				null,
				ghostIdentity(authorId)
		);

		assertThat(dto.canViewAuthor()).isFalse();
		assertThat(dto.authorId()).isNull();
		assertThat(dto.authorUsername()).isEqualTo("Anonymous");
		assertThat(dto.authorAvatarUrl()).isNull();
		assertThat(dto.authorVisibilityMode()).isNull();
	}

	private OverthinkingPost post(User author, OverthinkingVisibilityType visibilityType) {
		return OverthinkingPost.builder()
				.id(UUID.randomUUID())
				.author(author)
				.title("Gece düşüncesi")
				.content("Bir şarkının ilk saniyesi bütün günü anlatabilir.")
				.visibilityType(visibilityType)
				.build();
	}

	private GhostListenerIdentity ghostIdentity(UUID userId) {
		return new GhostListenerIdentity(
				userId,
				"listener-handle",
				"listener-avatar.png",
				ListenerVisibilityMode.GHOST
		);
	}
}
