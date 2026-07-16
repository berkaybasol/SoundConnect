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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageVariantBackfillServiceTest {

	@Mock MediaAssetRepository repository;
	@Mock ImageThumbnailService thumbnailService;
	@Mock ImageVariantBackfillFinalizer finalizer;

	@Test
	void backfillOne_updatesOnlyReadyPublicImageMissingThumbnail() throws Exception {
		MediaAsset asset = eligibleAsset();
		when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
		ImageThumbnailResult generated = new ImageThumbnailResult(
				"media/id/thumbnail.jpg", "https://cdn.test/media/id/thumbnail.jpg",
				1600, 900, 960, 540);
		when(thumbnailService.generateAndStoreDetached(asset.getId(), asset.getStorageKey()))
				.thenReturn(generated);
		when(finalizer.attachIfStillEligible(asset.getId(), asset.getStorageKey(), generated))
				.thenReturn(true);
		ImageVariantBackfillService service = new ImageVariantBackfillService(
				repository, thumbnailService, finalizer);

		assertThat(service.backfillOne(asset.getId())).isTrue();
		verify(finalizer).attachIfStillEligible(asset.getId(), asset.getStorageKey(), generated);
	}

	@Test
	void backfillOne_isIdempotentWhenThumbnailAlreadyExists() throws Exception {
		MediaAsset asset = eligibleAsset();
		asset.setThumbnailUrl("https://cdn.test/existing.jpg");
		when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
		ImageVariantBackfillService service = new ImageVariantBackfillService(
				repository, thumbnailService, finalizer);

		assertThat(service.backfillOne(asset.getId())).isFalse();
		verifyNoInteractions(thumbnailService);
		verifyNoInteractions(finalizer);
	}

	@Test
	void backfillOne_deletesGeneratedObject_whenFinalizerRejectsStaleSnapshot() throws Exception {
		MediaAsset asset = eligibleAsset();
		when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
		ImageThumbnailResult generated = new ImageThumbnailResult(
				"media/id/thumbnail.jpg", "https://cdn.test/media/id/thumbnail.jpg",
				1600, 900, 960, 540);
		when(thumbnailService.generateAndStoreDetached(asset.getId(), asset.getStorageKey()))
				.thenReturn(generated);
		when(finalizer.attachIfStillEligible(asset.getId(), asset.getStorageKey(), generated))
				.thenReturn(false);
		ImageVariantBackfillService service = new ImageVariantBackfillService(
				repository, thumbnailService, finalizer);

		assertThat(service.backfillOne(asset.getId())).isFalse();
		verify(thumbnailService).deleteStoredVariant(generated.thumbnailKey());
	}

	@Test
	void backfillOne_doesNotDeleteSharedDeterministicObject_onAmbiguousFinalizerFailure() throws Exception {
		MediaAsset asset = eligibleAsset();
		when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
		ImageThumbnailResult generated = new ImageThumbnailResult(
				"media/id/thumbnail.jpg", "https://cdn.test/media/id/thumbnail.jpg",
				1600, 900, 960, 540);
		when(thumbnailService.generateAndStoreDetached(asset.getId(), asset.getStorageKey()))
				.thenReturn(generated);
		when(finalizer.attachIfStillEligible(asset.getId(), asset.getStorageKey(), generated))
				.thenThrow(new IllegalStateException("ambiguous commit outcome"));
		ImageVariantBackfillService service = new ImageVariantBackfillService(
				repository, thumbnailService, finalizer);

		assertThatThrownBy(() -> service.backfillOne(asset.getId()))
				.isInstanceOf(IllegalStateException.class);
		verify(thumbnailService, never()).deleteStoredVariant(generated.thumbnailKey());
	}

	private static MediaAsset eligibleAsset() {
		return MediaAsset.builder()
				.id(UUID.randomUUID())
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("image/jpeg")
				.size(100L)
				.storageKey("media/id/source.jpg")
				.playbackUrl("https://cdn.test/media/id/source.jpg")
				.build();
	}
}
