package com.berkayb.soundconnect.modules.media.recovery;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaPublicPromotionRecoveryRepositoryTest {

	@Mock EntityManager entityManager;
	@Mock TypedQuery<MediaPublicPromotionRecoveryTarget> query;

	@Test
	void pagesOnlyReadyPublicProgressiveImageAndAudioProjection() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		var target = new MediaPublicPromotionRecoveryTarget(
				assetId, "media/" + assetId + "/source.jpg", createdAt);
		ArgumentCaptor<String> queryText = ArgumentCaptor.forClass(String.class);
		when(entityManager.createQuery(
				queryText.capture(), eq(MediaPublicPromotionRecoveryTarget.class)))
				.thenReturn(query);
		when(query.setParameter("readyStatus", MediaStatus.READY)).thenReturn(query);
		when(query.setParameter("publicVisibility", MediaVisibility.PUBLIC)).thenReturn(query);
		when(query.setParameter("progressiveProtocol", MediaStreamingProtocol.PROGRESSIVE))
				.thenReturn(query);
		when(query.setParameter("progressiveKinds", List.of(MediaKind.IMAGE, MediaKind.AUDIO)))
				.thenReturn(query);
		when(query.setFirstResult(200)).thenReturn(query);
		when(query.setMaxResults(50)).thenReturn(query);
		when(query.getResultList()).thenReturn(List.of(target));

		var repository = new MediaPublicPromotionRecoveryRepository(entityManager);
		var result = repository.findReadyBatch(200, 50);

		assertThat(result).containsExactly(target);
		assertThat(queryText.getValue())
				.contains("asset.status = :readyStatus")
				.contains("asset.visibility = :publicVisibility")
				.contains("asset.streamingProtocol = :progressiveProtocol")
				.contains("asset.kind in :progressiveKinds")
				.contains("order by asset.createdAt asc, asset.id asc");
		verify(query).setFirstResult(200);
		verify(query).setMaxResults(50);
	}
}
