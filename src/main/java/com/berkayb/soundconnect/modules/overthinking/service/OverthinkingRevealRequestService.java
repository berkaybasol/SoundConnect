package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OverthinkingRevealRequestService {
	
	// kullanici anonim postun sahibini gormek icin istek atar
	OverthinkingRevealRequestResponseDto createRevealRequest(UUID requesterId, UUID postId);

	void cancelRevealRequest(UUID requesterId, UUID postId);
	
	// post sahibi istegi kabul eder
	OverthinkingRevealRequestResponseDto approveRevealRequest(UUID authorId, UUID requestId);
	
	// post sahibi istegi reddeder
	OverthinkingRevealRequestResponseDto rejectRevealRequest(UUID authorId, UUID requestId);
	
	// post sahibi gelen istekleri listeler
	Page<OverthinkingRevealRequestResponseDto> getIncomingRequests(UUID authorId, Pageable pageable);

	long getIncomingPendingRequestCount(UUID authorId);
	
	// kullanici anonim postlara gonderdigi istekleri listeler
	Page<OverthinkingRevealRequestResponseDto> getMySentRequests(UUID requesterId, Pageable pageable);
}
