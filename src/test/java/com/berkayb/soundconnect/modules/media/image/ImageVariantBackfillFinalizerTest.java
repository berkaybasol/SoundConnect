package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageVariantBackfillFinalizerTest {

	@Mock MediaAssetRepository repository;

	@Test
	void attachIfStillEligible_appliesVariantWithColumnScopedCompareAndSet() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.jpg";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("image/jpeg")
				.size(10L)
				.storageKey(sourceKey)
				.build();
		ImageThumbnailResult result = new ImageThumbnailResult(
				"media/id/thumbnail.jpg", "https://cdn.test/thumbnail.jpg",
				1200, 800, 960, 640);
		when(repository.attachImageThumbnailIfEligible(
				assetId, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY,
				sourceKey, result.thumbnailUrl(), 1200, 800)).thenReturn(1);
		ImageVariantBackfillFinalizer finalizer = new ImageVariantBackfillFinalizer(repository);

		assertThat(finalizer.attachIfStillEligible(assetId, sourceKey, result)).isTrue();
		verify(repository, never()).save(asset);
	}

	@Test
	void attachIfStillEligible_rejectsDeletionPendingRace_withoutReattachingPublicVariant() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "media/" + assetId + "/source.jpg";
		MediaAsset deleting = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.DELETION_PENDING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("image/jpeg")
				.size(10L)
				.storageKey(sourceKey)
				.build();
		ImageThumbnailResult result = new ImageThumbnailResult(
				"media/id/thumbnail.jpg", "https://cdn.test/thumbnail.jpg",
				1200, 800, 960, 640);
		when(repository.findById(assetId)).thenReturn(Optional.of(deleting));
		ImageVariantBackfillFinalizer finalizer = new ImageVariantBackfillFinalizer(repository);

		assertThat(finalizer.attachIfStillEligible(assetId, sourceKey, result)).isFalse();
		assertThat(deleting.getThumbnailUrl()).isNull();
		verify(repository, never()).save(deleting);
	}
}
