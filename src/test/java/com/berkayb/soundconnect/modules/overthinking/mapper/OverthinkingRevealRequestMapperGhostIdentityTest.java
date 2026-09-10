package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;
import java.util.List;

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
	void standardRequesterUsesResolvedProfileAvatarAndGhostNeverFallsBackToIt() {
		UUID id = UUID.randomUUID();
		var request = request(id, "standard-name");
		var standard = new UserSummaryDto(id, "standard-name", "https://cdn.test/current-profile.jpg");
		var dto = mapper.toDto(request, null, standard);
		assertThat(dto.requesterAvatarUrl()).isEqualTo(standard.avatarUrl());
		assertThat(dto.requesterVisibilityMode()).isNull();
		var ghost = new GhostListenerIdentity(id, "ghost-handle", null, ListenerVisibilityMode.GHOST);
		var hidden = mapper.toDto(request, ghost, standard);
		assertThat(hidden.requesterUsername()).isEqualTo("ghost-handle");
		assertThat(hidden.requesterAvatarUrl()).isNull();
		assertThat(hidden.requesterVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
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

	@Test
	void pendingAndRejectedRequestsNeverExposeTheAuthorEvenWithGhostProjection() {
		OverthinkingRevealRequest request = request(UUID.randomUUID(), "requester");
		GhostListenerIdentity ghost = new GhostListenerIdentity(
				request.getRequester().getId(), "ghost-requester", "ghost.jpg", ListenerVisibilityMode.GHOST);
		for (var status : List.of(OverthinkingRevealRequestStatus.PENDING, OverthinkingRevealRequestStatus.REJECTED)) {
			request.setStatus(status);
			assertThat(mapper.toDto(request).authorId()).isNull();
			assertThat(mapper.toDto(request, ghost).authorId()).isNull();
			assertThat(mapper.toDto(request, ghost).requesterUsername()).isEqualTo("ghost-requester");
			// Alternative projection call sites cannot bypass the record's consent boundary.
			var direct = new OverthinkingRevealRequestResponseDto(request.getId(), request.getPost().getId(),
					request.getPost().getTitle(), request.getRequester().getId(), "requester",
					request.getAuthor().getId(), status, null);
			assertThat(direct.authorId()).isNull();
		}
	}

	@Test
	void approvedRequestPreservesAuthorConsentAndContextualGhostRequester() {
		OverthinkingRevealRequest request = request(UUID.randomUUID(), "stale-requester");
		request.approve();
		GhostListenerIdentity ghost = new GhostListenerIdentity(
				request.getRequester().getId(), "ghost-requester", "ghost.jpg", ListenerVisibilityMode.GHOST);
		var result = mapper.toDto(request, ghost);
		assertThat(result.authorId()).isEqualTo(request.getAuthor().getId());
		assertThat(result.requesterUsername()).isEqualTo("ghost-requester");
		assertThat(result.requesterVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
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
