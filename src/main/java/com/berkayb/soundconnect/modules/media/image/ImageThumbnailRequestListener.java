package com.berkayb.soundconnect.modules.media.image;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnImageVariantWorker
@RequiredArgsConstructor
@Slf4j
public class ImageThumbnailRequestListener {

	private final ImageVariantJobDispatcher dispatcher;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRequested(ImageThumbnailRequestedEvent event) {
		if (event == null || event.assetId() == null) return;
		if (!dispatcher.submit(event.assetId())) {
			log.warn("[media-image] thumbnail queue saturated; durable retry retained assetId={}",
					event.assetId());
		}
	}
}
