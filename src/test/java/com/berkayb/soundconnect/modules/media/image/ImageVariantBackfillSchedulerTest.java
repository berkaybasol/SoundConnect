package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageVariantBackfillSchedulerTest {

	@Mock MediaAssetRepository repository;
	@Mock ImageVariantJobDispatcher dispatcher;

	@Test
	void fullFailingOldestPage_doesNotStarveNextPage() {
		MediaImageVariantProperties properties = new MediaImageVariantProperties();
		properties.getBackfill().setBatchSize(2);
		UUID oldestA = UUID.randomUUID();
		UUID oldestB = UUID.randomUUID();
		UUID newer = UUID.randomUUID();
		when(repository.findIdsMissingThumbnail(
				MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 2)))
				.thenReturn(List.of(oldestA, oldestB));
		when(repository.findIdsMissingThumbnail(
				MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(1, 2)))
				.thenReturn(List.of(newer));
		when(dispatcher.submit(oldestA)).thenReturn(true);
		when(dispatcher.submit(oldestB)).thenReturn(true);
		when(dispatcher.submit(newer)).thenReturn(true);
		ImageVariantBackfillScheduler scheduler = new ImageVariantBackfillScheduler(
				repository, dispatcher, properties);

		// Worker success/failure is deliberately not part of page advancement:
		// the first page remains missing, yet the next pass still rotates forward.
		scheduler.backfillMissingThumbnails();
		scheduler.backfillMissingThumbnails();

		verify(dispatcher).submit(oldestA);
		verify(dispatcher).submit(oldestB);
		verify(dispatcher).submit(newer);
		verify(repository).findIdsMissingThumbnail(
				MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(1, 2));
	}
}
