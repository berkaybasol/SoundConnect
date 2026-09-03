package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OverthinkingRevealRequestMapperGhostIdentityTest {

	private final OverthinkingRevealRequestMapper mapper =
			Mappers.getMapper(OverthinkingRevealRequestMapper.class);

	@Test
	void ghostRequesterUsesCanonicalListenerIdentityAndMarker() {
		UUID requesterId = UUID.randomUUID();
		OverthinkingRevealRequest request = request(requesterId, "stale-profile-name");
		GhostListenerIdentity ghostIdentity = new GhostListenerIdentity(
				requesterId,
				"listener-handle",
				"https://cdn.example/listener-avatar.jpg",
				ListenerVisibilityMode.GHOST
		);

		OverthinkingRevealRequestResponseDto dto = mapper.toDto(request, ghostIdentity);

		assertThat(dto.requesterId()).isEqualTo(requesterId);
		assertThat(dto.requesterUsername()).isEqualTo("listener-handle");
		assertThat(dto.requesterAvatarUrl()).isEqualTo("https://cdn.example/listener-avatar.jpg");
		assertThat(dto.requesterVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void standardRequesterKeepsExistingWireShapeWithoutGhostMarker() {
		OverthinkingRevealRequestResponseDto dto = mapper.toDto(
				request(UUID.randomUUID(), "standard-listener"),
				null
		);

		assertThat(dto.requesterUsername()).isEqualTo("standard-listener");
		assertThat(dto.requesterAvatarUrl()).isNull();
		assertThat(dto.requesterVisibilityMode()).isNull();
	}

	private OverthinkingRevealRequest request(UUID requesterId, String username) {
		User requester = User.builder().id(requesterId).username(username).build();
		User author = User.builder().id(UUID.randomUUID()).username("author").build();
		OverthinkingPost post = OverthinkingPost.builder()
				.id(UUID.randomUUID())
				.author(author)
				.title("Gece düşüncesi")
				.content("İçerik")
				.visibilityType(OverthinkingVisibilityType.ANONYMOUS)
				.build();
		return OverthinkingRevealRequest.builder()
				.id(UUID.randomUUID())
				.post(post)
				.requester(requester)
				.author(author)
				.build();
	}
}
