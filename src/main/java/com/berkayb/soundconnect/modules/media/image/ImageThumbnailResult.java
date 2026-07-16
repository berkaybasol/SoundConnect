package com.berkayb.soundconnect.modules.media.image;

public record ImageThumbnailResult(
		String thumbnailKey,
		String thumbnailUrl,
		int sourceWidth,
		int sourceHeight,
		int thumbnailWidth,
		int thumbnailHeight
) {
}
