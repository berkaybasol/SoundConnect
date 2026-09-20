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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageVariantBackfillFinalizerTest {

	@Mock MediaAssetRepository repository;

	@Test
	void legacyPublicVerifiedSource_keepsTheConcurrentWinnersThumbnail() {
		UUID id = UUID.randomUUID();
		String source = "verified/media/" + id + "/source.jpg";
		ImageThumbnailResult result = new ImageThumbnailResult("media/" + id + "/thumbnail.jpg",
				"https://cdn.invalid/thumbnail.jpg", 1200, 800, 960, 640);
		when(repository.findById(id)).thenReturn(Optional.of(MediaAsset.builder().id(id)
				.kind(MediaKind.IMAGE).visibility(MediaVisibility.PUBLIC).status(MediaStatus.READY)
				.storageKey(source).thumbnailUrl(result.thumbnailUrl()).build()));
		assertThat(new ImageVariantBackfillFinalizer(repository)
				.attachIfStillEligible(id, source, result)).isTrue();
	}

	@Test
	void protectedVariant_compareAndSetAndConcurrentWinnerKeepTheSameObject() {
		UUID id = UUID.randomUUID();
		String source = "protected/private-verified/media/" + id + "/source.jpg";
		String key = ImageThumbnailService.protectedThumbnailKeyFor(source);
		ImageThumbnailResult result = new ImageThumbnailResult(key, null, 1200, 800, 960, 640);
		when(repository.attachProtectedImageThumbnailIfEligible(id, source, key, 1200, 800))
				.thenReturn(1, 0);
		when(repository.findById(id)).thenReturn(Optional.of(MediaAsset.builder().id(id)
				.kind(MediaKind.IMAGE).visibility(MediaVisibility.PRIVATE).status(MediaStatus.READY)
				.storageKey(source).thumbnailStorageKey(key).build()));
		var finalizer = new ImageVariantBackfillFinalizer(repository);

		assertThat(finalizer.attachIfStillEligible(id, source, result)).isTrue();
		assertThat(finalizer.attachIfStillEligible(id, source, result)).isTrue();
	}

	@Test
	void protectedVariant_cannotAttachAnotherAssetsKeyOrStablePublicUrl() {
		String source = "protected/private-verified/media/one/source.jpg";
		var finalizer = new ImageVariantBackfillFinalizer(repository);
		assertThatThrownBy(() -> finalizer.attachIfStillEligible(UUID.randomUUID(), source,
				new ImageThumbnailResult("protected/private-verified/media/other/thumbnail.jpg", null, 1, 1, 1, 1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> finalizer.attachIfStillEligible(UUID.randomUUID(), source,
				new ImageThumbnailResult(ImageThumbnailService.protectedThumbnailKeyFor(source),
						"https://public.invalid/thumbnail.jpg", 1, 1, 1, 1)))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

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
