package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingRevealRequestMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingPagination;
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
import java.util.HashMap;
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
	private final LikeRepository actorRepository;
	private final CommentAuthorBatchResolver requesterIdentityResolver;
	private final OverthinkingRevealNotificationRetractionService notificationRetraction;
	private final OverthinkingRevealRateGuard rateGuard;
	private final OverthinkingRevealParticipantGuard participants;
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto createRevealRequest(UUID requesterId, UUID postId) {
		lockActiveActor(requesterId);
		participants.lockPostAuthor(postId);
		rateGuard.lockRequester(requesterId);
		User requester = userEntityFinder.getUser(requesterId);
		
		// Serialize the existence check/insert with retries, visibility changes and deletion.
		OverthinkingPost post = postRepository.findByIdForUpdate(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.isAnonymous()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_ANONYMOUS);
		}
		
		if (post.getAuthor().getId().equals(requester.getId())) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_SELF_NOT_ALLOWED);
		}
		
		var existing = revealRequestRepository.findByPostIdAndRequesterId(postId, requesterId);
		if (existing.isPresent()) {
			if (!existing.get().isPending()) throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED);
			return toDto(existing.get());
		}
		
		rateGuard.reserve(requesterId, post.getAuthor().getId());
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
	public void cancelRevealRequest(UUID requesterId, UUID postId) {
		lockActiveActor(requesterId);
		participants.lockPostAuthor(postId);
		userEntityFinder.getUser(requesterId);
		// Same actor -> parent -> child order as create/approve/delete. If the
		// parent was deleted, withdrawal is already complete.
		if (postRepository.findByIdForUpdate(postId).isEmpty()) return;
		var existing = revealRequestRepository.findByPostIdAndRequesterIdForUpdate(postId, requesterId);
		if (existing.isEmpty()) return;
		var request = existing.get();
		if (!request.isPending()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_ALREADY_DECIDED);
		}
		notificationRetraction.retract(request.getAuthor().getId(), request.getId());
		revealRequestRepository.delete(request);
	}

	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto approveRevealRequest(UUID authorId, UUID requestId) {
		lockActiveActor(authorId);
		participants.lockRequesterForDecision(authorId, requestId);
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = lockRequestForDecision(authorId, requestId);
		
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
		lockActiveActor(authorId);
		participants.lockRequesterForDecision(authorId, requestId);
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = lockRequestForDecision(authorId, requestId);
		
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
		
		return mapPage(revealRequestRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, OverthinkingPagination.newest(pageable)));
	}

	@Override
	@Transactional(readOnly = true)
	public long getIncomingPendingRequestCount(UUID authorId) {
		if (authorId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		userEntityFinder.getUser(authorId);
		return revealRequestRepository.countByAuthorIdAndStatus(authorId, OverthinkingRevealRequestStatus.PENDING);
	}
	
	@Override
	public Page<OverthinkingRevealRequestResponseDto> getMySentRequests(UUID requesterId, Pageable pageable) {
		userEntityFinder.getUser(requesterId);
		
		return mapPage(revealRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, OverthinkingPagination.newest(pageable)));
	}

	private void lockActiveActor(UUID actorId) {
		// Same actor -> content lock order as post, comment and like mutations.
		if (actorId == null || actorRepository.lockActiveActor(actorId).isEmpty()) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
	}

	private OverthinkingRevealRequest lockRequestForDecision(UUID authorId, UUID requestId) {
		UUID postId = revealRequestRepository.findPostIdByIdAndAuthorId(requestId, authorId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		// Never lock the child before the parent: deletion uses this same order.
		postRepository.findByIdForUpdate(postId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		return revealRequestRepository.findByIdAndAuthorIdForUpdate(requestId, authorId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
	}

	private OverthinkingRevealRequestResponseDto toDto(OverthinkingRevealRequest request) {
		UUID requesterId = requesterId(request);
		Map<UUID, GhostListenerIdentity> ghostIdentities = requesterId == null
				? Map.of()
				: ghostIdentityBatchResolver.resolve(Set.of(requesterId));
		var identities = resolveStandardIdentities(requesterId == null ? Set.of() : Set.of(requesterId), ghostIdentities);
		return revealRequestMapper.toDto(request, ghostIdentities.get(requesterId), identities.get(requesterId));
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
		var identities = resolveStandardIdentities(requesterIds, ghostIdentities);
		return page.map(request -> {
			UUID requesterId = requesterId(request);
			return revealRequestMapper.toDto(request, ghostIdentities.get(requesterId), identities.get(requesterId));
		});
	}

	private Map<UUID, UserSummaryDto> resolveStandardIdentities(Set<UUID> requesterIds, Map<UUID, GhostListenerIdentity> ghosts) {
		var standardIds = requesterIds.stream().filter(id -> !ghosts.containsKey(id)).toList();
		Map<UUID, UserSummaryDto> identities = new HashMap<>();
		for (int offset = 0; offset < standardIds.size(); offset += 50) {
			identities.putAll(requesterIdentityResolver.resolve(standardIds.subList(offset, Math.min(offset + 50, standardIds.size()))));
		}
		return identities;
	}

	private UUID requesterId(OverthinkingRevealRequest request) {
		return request == null || request.getRequester() == null
				? null
				: request.getRequester().getId();
	}
}
