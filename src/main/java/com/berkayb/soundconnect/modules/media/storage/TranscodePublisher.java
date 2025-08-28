package com.berkayb.soundconnect.modules.media.storage;

import java.util.UUID;

public interface TranscodePublisher {
	// video HLS isi icin kuyruga mesaj at
	void publishVideoHls(UUID assetId, String sourceKey, String hlsPrefix);
}