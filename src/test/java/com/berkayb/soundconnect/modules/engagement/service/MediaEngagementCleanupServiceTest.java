package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class MediaEngagementCleanupServiceTest {

	@Mock
	LikeRepository likeRepository;

	@Mock
	CommentRepository commentRepository;

	@Test
	void purgesLikesThenRepliesThenRootsForMediaTarget() {
		UUID assetId = UUID.randomUUID();
		MediaEngagementCleanupService service = new MediaEngagementCleanupService(
				likeRepository, commentRepository);

		service.purgeForMedia(assetId);

		InOrder order = inOrder(likeRepository, commentRepository);
		order.verify(likeRepository).deleteMediaTargetReferences(assetId);
		order.verify(commentRepository).deleteRepliesByTarget(
				EngagementTargetType.MEDIA, assetId);
		order.verify(commentRepository).deleteRootsByTarget(
				EngagementTargetType.MEDIA, assetId);
		order.verifyNoMoreInteractions();
	}
}
