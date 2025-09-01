package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;

import java.util.UUID;

/**
 * MediaPolicy - Medya yukeleme kurallarini ve storage anahtar uretimini merkezi sekilde yoneten interface
 *
 */

public interface MediaPolicy {
	
	// yuklenen dosyanin gecerli olup olmadigini dogrular parametreleri goruyorsun uzun uzun yazmaya gerek yok
	void validate(MediaKind kind, String mimeType, long sizeBytes);
	
	// yuklenen dosyanin storage'da (S3/R2) tuttulacagi anahtar bilgisini uretir
	String buildSourceKey(UUID assetId, String originalFileName);
	
	// HLS ciktilari icin klasor prefix(orn: media/{assetId}/hls
	String buildHlsPrefix(UUID assetId);
}