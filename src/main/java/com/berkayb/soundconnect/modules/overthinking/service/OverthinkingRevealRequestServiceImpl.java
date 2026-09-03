package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingRevealRequestMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
// Ghost identity resolution takes a shared database lock so these projections
// linearize with a concurrent visibility switch. PostgreSQL forbids that lock
// inside a read-only transaction.
@Transactional
public class OverthinkingRevealRequestServiceImpl implements OverthinkingRevealRequestService {
	
	private final OverthinkingRevealRequestRepository revealRequestRepository;
	private final OverthinkingPostRepository postRepository;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingRevealRequestMapper revealRequestMapper;
	private final OverthinkingNotificationService notificationService;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto createRevealRequest(UUID requesterId, UUID postId) {
		User requester = userEntityFinder.getUser(requesterId);
		
		OverthinkingPost post = postRepository.findById(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.isAnonymous()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_ANONYMOUS);
		}
		
		if (post.getAuthor().getId().equals(requester.getId())) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_SELF_NOT_ALLOWED);
		}
		
		var existing = revealRequestRepository.findByPostIdAndRequesterId(postId, requesterId);
		if (existing.isPresent()) {
			return toDto(existing.get());
		}
		
		OverthinkingRevealRequest request = OverthinkingRevealRequest.builder()
		                                                             .post(post)
		                                                             .requester(requester)
		                                                             .author(post.getAuthor())
		                                                             .build();
		
		request = revealRequestRepository.save(request);
		
		notificationService.sendRevealRequestReceivedNotification(request);
		
		log.info("[OverthinkingReveal] Reveal request created. post={}, requester={}, author={}, request={}",
		         postId, requesterId, post.getAuthor().getId(), request.getId());
		
		return toDto(request);
	}
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto approveRevealRequest(UUID authorId, UUID requestId) {
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = revealRequestRepository.findByIdAndAuthorId(requestId, authorId)
		                                                           .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		
		if (request.isApproved()) {
			return toDto(request);
		}
		
		if (!request.isPending()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
		}
		
		request.approve();
		
		OverthinkingRevealRequest saved = revealRequestRepository.save(request);
		
		notificationService.sendRevealRequestApprovedNotification(saved);
		
		log.info("[OverthinkingReveal] Reveal request approved. request={}, author={}", requestId, authorId);
		
		return toDto(saved);
	}
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto rejectRevealRequest(UUID authorId, UUID requestId) {
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = revealRequestRepository.findByIdAndAuthorId(requestId, authorId)
		                                                           .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		
		if (request.isRejected()) {
			return toDto(request);
		}
		
		if (!request.isPending()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
		}
		
		request.reject();
		
		OverthinkingRevealRequest saved = revealRequestRepository.save(request);
		
		notificationService.sendRevealRequestRejectedNotification(saved);
		
		log.info("[OverthinkingReveal] Reveal request rejected. request={}, author={}", requestId, authorId);
		
		return toDto(saved);
	}
	
	@Override
	public Page<OverthinkingRevealRequestResponseDto> getIncomingRequests(UUID authorId, Pageable pageable) {
		userEntityFinder.getUser(authorId);
		
		return mapPage(revealRequestRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable));
	}
	
	@Override
	public Page<OverthinkingRevealRequestResponseDto> getMySentRequests(UUID requesterId, Pageable pageable) {
		userEntityFinder.getUser(requesterId);
		
		return mapPage(revealRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable));
	}

	private OverthinkingRevealRequestResponseDto toDto(OverthinkingRevealRequest request) {
		UUID requesterId = requesterId(request);
		Map<UUID, GhostListenerIdentity> ghostIdentities = requesterId == null
				? Map.of()
				: ghostIdentityBatchResolver.resolve(Set.of(requesterId));
		return revealRequestMapper.toDto(request, ghostIdentities.get(requesterId));
	}

	private Page<OverthinkingRevealRequestResponseDto> mapPage(Page<OverthinkingRevealRequest> page) {
		List<OverthinkingRevealRequest> requests = page.getContent();
		LinkedHashSet<UUID> requesterIds = new LinkedHashSet<>();
		for (OverthinkingRevealRequest request : requests) {
			UUID requesterId = requesterId(request);
			if (requesterId != null) requesterIds.add(requesterId);
		}
		Map<UUID, GhostListenerIdentity> ghostIdentities = requesterIds.isEmpty()
				? Map.of()
				: ghostIdentityBatchResolver.resolve(requesterIds);
		return page.map(request -> {
			UUID requesterId = requesterId(request);
			return revealRequestMapper.toDto(request, ghostIdentities.get(requesterId));
		});
	}

	private UUID requesterId(OverthinkingRevealRequest request) {
		return request == null || request.getRequester() == null
				? null
				: request.getRequester().getId();
	}
}
