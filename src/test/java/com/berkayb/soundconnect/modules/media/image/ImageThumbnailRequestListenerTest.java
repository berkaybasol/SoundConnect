package com.berkayb.soundconnect.modules.media.image;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageThumbnailRequestListenerTest {

	@Test
	void saturatedExecutorLeavesTheDurableReadyAssetForBackfill() {
		ImageVariantJobDispatcher dispatcher = mock(ImageVariantJobDispatcher.class);
		UUID assetId = UUID.randomUUID();
		when(dispatcher.submit(assetId)).thenReturn(false);

		new ImageThumbnailRequestListener(dispatcher)
				.onRequested(new ImageThumbnailRequestedEvent(assetId));

		verify(dispatcher).submit(assetId);
	}
}
