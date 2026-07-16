package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
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
class MediaProtectedUploadRecoveryRepositoryTest {

	@Mock EntityManager entityManager;
	@Mock TypedQuery<MediaAsset> query;

	@Test
	void pagesOnlyDurableReadyPrivateVerifiedRowsInStableOrder() {
		UUID assetId = UUID.randomUUID();
		LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
		String storageKey = "protected/private-verified/media/" + assetId + "/source.mp3";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.status(MediaStatus.READY)
				.storageKey(storageKey)
				.createdAt(createdAt)
				.build();
		ArgumentCaptor<String> queryText = ArgumentCaptor.forClass(String.class);
		when(entityManager.createQuery(queryText.capture(), eq(MediaAsset.class))).thenReturn(query);
		when(query.setParameter("readyStatus", MediaStatus.READY)).thenReturn(query);
		when(query.setParameter("privateVerifiedPrefix", "protected/private-verified/%"))
				.thenReturn(query);
		when(query.setFirstResult(200)).thenReturn(query);
		when(query.setMaxResults(50)).thenReturn(query);
		when(query.getResultList()).thenReturn(List.of(asset));

		var repository = new MediaProtectedUploadRecoveryRepository(entityManager);
		var result = repository.findReadyBatch(200, 50);

		assertThat(result).containsExactly(
				new MediaProtectedUploadRecoveryRepository.RecoveryTarget(
						assetId, storageKey, createdAt));
		assertThat(queryText.getValue())
				.contains("asset.status = :readyStatus")
				.contains("asset.storageKey like :privateVerifiedPrefix")
				.contains("order by asset.createdAt asc, asset.id asc");
		verify(query).setFirstResult(200);
		verify(query).setMaxResults(50);
	}
}
