package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Removes engagement that is owned by a media target.
 *
 * <p>Likes and comments are not first-party content references. Keeping them
 * after the target owner requests deletion would either expose an orphaned
 * thread or let another user permanently block deletion. MANDATORY propagation
 * guarantees that cleanup and the durable media deletion intent commit or roll
 * back together.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MediaEngagementCleanupService {

	private final LikeRepository likeRepository;
	private final CommentRepository commentRepository;

	@Transactional(propagation = Propagation.MANDATORY)
	public void purgeForMedia(UUID mediaAssetId) {
		int likes = likeRepository.deleteMediaTargetReferences(mediaAssetId);
		int replies = commentRepository.deleteRepliesByTarget(
				EngagementTargetType.MEDIA, mediaAssetId);
		int roots = commentRepository.deleteRootsByTarget(
				EngagementTargetType.MEDIA, mediaAssetId);

		log.info("[media-delete] engagement purged assetId={} likes={} comments={}",
				mediaAssetId, likes, replies + roots);
	}
}
