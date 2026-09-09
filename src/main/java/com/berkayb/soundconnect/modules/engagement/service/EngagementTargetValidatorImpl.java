package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EngagementTargetValidatorImpl implements EngagementTargetValidator{
	
	private final CommentTargetAccessGuard targetAccess;
	
	/** Likes follow the same public visibility and deletion fences as comments. */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void validateExists(EngagementTargetType targetType, UUID targetId) {
		// Shared media locks conflict with deletion but allow concurrent likes;
		// listener privacy is locked before media, matching public media readers.
		targetAccess.requireReadable(targetType, targetId);
	}
}
