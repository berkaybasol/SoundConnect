package com.berkayb.soundconnect.modules.media.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaDeletionStateServiceTest {

	@Test
	void finish_removesRowOnlyThroughPendingStatusCompareAndDelete() {
		MediaAssetRepository repository = mock(MediaAssetRepository.class);
		MediaDeletionStateService service = new MediaDeletionStateService(repository);
		UUID assetId = UUID.randomUUID();
		when(repository.deleteIfStatus(assetId, MediaStatus.DELETION_PENDING)).thenReturn(1);

		assertThat(service.finish(assetId)).isTrue();

		verify(repository).deleteIfStatus(assetId, MediaStatus.DELETION_PENDING);
	}
}
