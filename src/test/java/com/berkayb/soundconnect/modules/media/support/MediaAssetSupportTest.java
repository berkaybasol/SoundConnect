// src/test/java/com/berkayb/soundconnect/modules/media/support/MediaAssetSupportTest.java
package com.berkayb.soundconnect.modules.media.support;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class MediaAssetSupportTest {
	
	// -------------------------------------------------
	// Helpers
	// -------------------------------------------------
	private static MediaAsset asset(
			MediaKind kind,
			MediaStatus status,
			MediaVisibility vis,
			String sourceUrl,
			String playbackUrl,
			String thumbUrl
	) {
		return MediaAsset.builder()
		                 .kind(kind)
		                 .status(status)
		                 .visibility(vis)
		                 .ownerType(MediaOwnerType.USER)
		                 .ownerId(UUID.randomUUID())
		                 .storageKey("media/" + UUID.randomUUID() + "/source.mp4") // NOT NULL alanı
		                 .mimeType(kind == MediaKind.IMAGE ? "image/png" : (kind == MediaKind.AUDIO ? "audio/mpeg" : "video/mp4"))
		                 .size(123L)
		                 .sourceUrl(sourceUrl)
		                 .playbackUrl(playbackUrl)
		                 .thumbnailUrl(thumbUrl)
		                 .build();
	}
	
	// -------------------------------------------------
	// isPubliclyListable
	// -------------------------------------------------
	@Test
	void isPubliclyListable_ready_and_public_true_otherwise_false() {
		var ok = asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.PUBLIC,
		               "https://cdn/x.png", "https://cdn/x.png", null);
		assertThat(MediaAssetSupport.isPubliclyListable(ok)).isTrue();
		
		// Not READY
		assertThat(MediaAssetSupport.isPubliclyListable(
				asset(MediaKind.IMAGE, MediaStatus.PROCESSING, MediaVisibility.PUBLIC, "u","u", null)
		)).isFalse();
		
		// Not PUBLIC
		assertThat(MediaAssetSupport.isPubliclyListable(
				asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.PRIVATE, "u","u", null)
		)).isFalse();
		assertThat(MediaAssetSupport.isPubliclyListable(
				asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.UNLISTED, "u","u", null)
		)).isFalse();
		
		// null safety
		assertThat(MediaAssetSupport.isPubliclyListable(null)).isFalse();
	}
	
	// -------------------------------------------------
	// Type helpers
	// -------------------------------------------------
	@Test
	void type_helpers_image_audio_video_and_hasKind() {
		var img = asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.PUBLIC, "u","u", null);
		var aud = asset(MediaKind.AUDIO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","u", null);
		var vid = asset(MediaKind.VIDEO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","u", null);
		
		assertThat(MediaAssetSupport.isImage(img)).isTrue();
		assertThat(MediaAssetSupport.isAudio(img)).isFalse();
		assertThat(MediaAssetSupport.isVideo(img)).isFalse();
		
		assertThat(MediaAssetSupport.isAudio(aud)).isTrue();
		assertThat(MediaAssetSupport.isImage(aud)).isFalse();
		assertThat(MediaAssetSupport.isVideo(aud)).isFalse();
		
		assertThat(MediaAssetSupport.isVideo(vid)).isTrue();
		assertThat(MediaAssetSupport.isImage(vid)).isFalse();
		assertThat(MediaAssetSupport.isAudio(vid)).isFalse();
		
		assertThat(MediaAssetSupport.hasKind(img, MediaKind.IMAGE)).isTrue();
		assertThat(MediaAssetSupport.hasKind(img, MediaKind.VIDEO)).isFalse();
		assertThat(MediaAssetSupport.hasKind(null, MediaKind.IMAGE)).isFalse();
	}
	
	// -------------------------------------------------
	// Playback & URLs
	// -------------------------------------------------
	@Test
	void canServePlayback_requires_ready() {
		var ready = asset(MediaKind.AUDIO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","p", null);
		var proc  = asset(MediaKind.AUDIO, MediaStatus.PROCESSING, MediaVisibility.PUBLIC, "u","p", null);
		var up    = asset(MediaKind.AUDIO, MediaStatus.UPLOADING, MediaVisibility.PUBLIC, "u","p", null);
		var fail  = asset(MediaKind.AUDIO, MediaStatus.FAILED, MediaVisibility.PUBLIC, "u","p", null);
		
		assertThat(MediaAssetSupport.canServePlayback(ready)).isTrue();
		assertThat(MediaAssetSupport.canServePlayback(proc)).isFalse();
		assertThat(MediaAssetSupport.canServePlayback(up)).isFalse();
		assertThat(MediaAssetSupport.canServePlayback(fail)).isFalse();
		assertThat(MediaAssetSupport.canServePlayback(null)).isFalse();
	}
	
	@Test
	void hasDisplayableThumbnail_requires_ready_and_nonBlank_thumbnail() {
		var ok = asset(MediaKind.VIDEO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","p","https://cdn/t.jpg");
		assertThat(MediaAssetSupport.hasDisplayableThumbnail(ok)).isTrue();
		
		var noThumb = asset(MediaKind.VIDEO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","p", null);
		assertThat(MediaAssetSupport.hasDisplayableThumbnail(noThumb)).isFalse();
		
		var blankThumb = asset(MediaKind.VIDEO, MediaStatus.READY, MediaVisibility.PUBLIC, "u","p", "   ");
		assertThat(MediaAssetSupport.hasDisplayableThumbnail(blankThumb)).isFalse();
		
		var notReady = asset(MediaKind.VIDEO, MediaStatus.PROCESSING, MediaVisibility.PUBLIC, "u","p","https://cdn/t.jpg");
		assertThat(MediaAssetSupport.hasDisplayableThumbnail(notReady)).isFalse();
	}
	
	@Test
	void hasSource_and_hasPlayback_check_nonBlank() {
		var a = asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.PUBLIC, "https://cdn/src", "https://cdn/pb", "t");
		
		assertThat(MediaAssetSupport.hasSource(a)).isTrue();
		assertThat(MediaAssetSupport.hasPlayback(a)).isTrue();
		
		var b = asset(MediaKind.IMAGE, MediaStatus.READY, MediaVisibility.PUBLIC, "   ", "", null);
		assertThat(MediaAssetSupport.hasSource(b)).isFalse();
		assertThat(MediaAssetSupport.hasPlayback(b)).isFalse();
		
		assertThat(MediaAssetSupport.hasSource(null)).isFalse();
		assertThat(MediaAssetSupport.hasPlayback(null)).isFalse();
	}
}