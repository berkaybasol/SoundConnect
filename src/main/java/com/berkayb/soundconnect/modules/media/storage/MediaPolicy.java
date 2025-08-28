package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;

import java.util.UUID;

public interface MediaPolicy {
	
	// tur + mime + boyut dogrulama (limitler)
	void validate(MediaKind kind, String mimeType, long sizeBytes);
	
	// soruce dosyasi icin object key or: media/{assetId}source.mp4
	String buildSourceKey(UUID assetId, String originalFileName);
	
	// HLS ciktilari icin klasor prefix(orn: media/{assetId}/hls
	String buildHlsPrefix(UUID assetId);
}