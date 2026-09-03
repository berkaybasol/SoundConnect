package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingRevealRequestMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverthinkingRevealRequestServiceGhostIdentityTest {

	@Mock OverthinkingRevealRequestRepository revealRequestRepository;
	@Mock OverthinkingPostRepository postRepository;
	@Mock UserEntityFinder userEntityFinder;
	@Mock OverthinkingRevealRequestMapper revealRequestMapper;
	@Mock OverthinkingNotificationService notificationService;
	@Mock GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	@InjectMocks OverthinkingRevealRequestServiceImpl service;

	@Test
	void incomingPageResolvesAllRequesterGhostIdentitiesInOneBatch() {
		UUID authorId = UUID.randomUUID();
		UUID ghostRequesterId = UUID.randomUUID();
		UUID standardRequesterId = UUID.randomUUID();
		OverthinkingRevealRequest ghostRequest = request(authorId, ghostRequesterId);
		OverthinkingRevealRequest standardRequest = request(authorId, standardRequesterId);
		PageRequest pageable = PageRequest.of(0, 20);
		GhostListenerIdentity ghostIdentity = new GhostListenerIdentity(
				ghostRequesterId,
				"ghost-listener",
				"ghost-avatar.jpg",
				ListenerVisibilityMode.GHOST
		);
		OverthinkingRevealRequestResponseDto ghostDto = dto(ghostRequest, "ghost-listener");
		OverthinkingRevealRequestResponseDto standardDto = dto(standardRequest, "standard-listener");

		when(revealRequestRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable))
				.thenReturn(new PageImpl<>(List.of(ghostRequest, standardRequest), pageable, 2));
		when(ghostIdentityBatchResolver.resolve(argThat(ids -> containsExactly(
				ids,
				ghostRequesterId,
				standardRequesterId
		)))).thenReturn(Map.of(ghostRequesterId, ghostIdentity));
		when(revealRequestMapper.toDto(ghostRequest, ghostIdentity)).thenReturn(ghostDto);
		when(revealRequestMapper.toDto(standardRequest, null)).thenReturn(standardDto);

		Page<OverthinkingRevealRequestResponseDto> result =
				service.getIncomingRequests(authorId, pageable);

		assertThat(result.getContent()).containsExactly(ghostDto, standardDto);
		verify(ghostIdentityBatchResolver).resolve(argThat(ids -> containsExactly(
				ids,
				ghostRequesterId,
				standardRequesterId
		)));
	}

	private boolean containsExactly(Collection<UUID> ids, UUID first, UUID second) {
		return ids != null && ids.size() == 2 && ids.contains(first) && ids.contains(second);
	}

	private OverthinkingRevealRequest request(UUID authorId, UUID requesterId) {
		User author = User.builder().id(authorId).username("author").build();
		User requester = User.builder().id(requesterId).username("standard-listener").build();
		OverthinkingPost post = OverthinkingPost.builder()
				.id(UUID.randomUUID())
				.author(author)
				.title("Anonim paylaşım")
				.content("İçerik")
				.build();
		return OverthinkingRevealRequest.builder()
				.id(UUID.randomUUID())
				.post(post)
				.requester(requester)
				.author(author)
				.status(OverthinkingRevealRequestStatus.PENDING)
				.build();
	}

	private OverthinkingRevealRequestResponseDto dto(
			OverthinkingRevealRequest request,
			String requesterUsername
	) {
		return new OverthinkingRevealRequestResponseDto(
				request.getId(),
				request.getPost().getId(),
				request.getPost().getTitle(),
				request.getRequester().getId(),
				requesterUsername,
				request.getAuthor().getId(),
				request.getStatus(),
				request.getCreatedAt()
		);
	}
}
