package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingRevealRequestMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
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

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OverthinkingRevealRequestServiceImpl implements OverthinkingRevealRequestService {
	
	private final OverthinkingRevealRequestRepository revealRequestRepository;
	private final OverthinkingPostRepository postRepository;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingRevealRequestMapper revealRequestMapper;
	private final OverthinkingNotificationService notificationService;
	
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
			return revealRequestMapper.toDto(existing.get());
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
		
		return revealRequestMapper.toDto(request);
	}
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto approveRevealRequest(UUID authorId, UUID requestId) {
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = revealRequestRepository.findByIdAndAuthorId(requestId, authorId)
		                                                           .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		
		if (request.isApproved()) {
			return revealRequestMapper.toDto(request);
		}
		
		if (!request.isPending()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
		}
		
		request.approve();
		
		OverthinkingRevealRequest saved = revealRequestRepository.save(request);
		
		notificationService.sendRevealRequestApprovedNotification(saved);
		
		log.info("[OverthinkingReveal] Reveal request approved. request={}, author={}", requestId, authorId);
		
		return revealRequestMapper.toDto(saved);
	}
	
	@Override
	@Transactional
	public OverthinkingRevealRequestResponseDto rejectRevealRequest(UUID authorId, UUID requestId) {
		userEntityFinder.getUser(authorId);
		
		OverthinkingRevealRequest request = revealRequestRepository.findByIdAndAuthorId(requestId, authorId)
		                                                           .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_NOT_FOUND));
		
		if (request.isRejected()) {
			return revealRequestMapper.toDto(request);
		}
		
		if (!request.isPending()) {
			throw new SoundConnectException(ErrorType.OVERTHINKING_REVEAL_REQUEST_INVALID_STATUS);
		}
		
		request.reject();
		
		OverthinkingRevealRequest saved = revealRequestRepository.save(request);
		
		notificationService.sendRevealRequestRejectedNotification(saved);
		
		log.info("[OverthinkingReveal] Reveal request rejected. request={}, author={}", requestId, authorId);
		
		return revealRequestMapper.toDto(saved);
	}
	
	@Override
	public Page<OverthinkingRevealRequestResponseDto> getIncomingRequests(UUID authorId, Pageable pageable) {
		userEntityFinder.getUser(authorId);
		
		return revealRequestRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable)
		                              .map(revealRequestMapper::toDto);
	}
	
	@Override
	public Page<OverthinkingRevealRequestResponseDto> getMySentRequests(UUID requesterId, Pageable pageable) {
		userEntityFinder.getUser(requesterId);
		
		return revealRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable)
		                              .map(revealRequestMapper::toDto);
	}
}