// src/test/java/com/berkayb/soundconnect/modules/media/transcode/MediaAssetStatusUpdaterTest.java
package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MediaAssetStatusUpdaterTest {
	
	@Mock
	MediaAssetRepository mediaRepo;
	
	MediaAssetStatusUpdater updater;
	
	@BeforeEach
	void setup() {
		updater = new MediaAssetStatusUpdater(mediaRepo);
	}
	
	// -------- markProcessing --------
	
	@Test
	void markProcessing_when_nonTerminal_setsProcessing() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.UPLOADING);
		givenFindById(id, asset);
		
		when(mediaRepo.save(any(MediaAsset.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		
		MediaAsset saved = updater.markProcessing(id);
		
		assertThat(saved.getStatus()).isEqualTo(MediaStatus.PROCESSING);
		verify(mediaRepo, times(1)).save(asset);
	}
	
	@Test
	void markProcessing_when_terminal_ignored_idempotent() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.READY);
		givenFindById(id, asset);
		
		MediaAsset result = updater.markProcessing(id);
		
		assertThat(result.getStatus()).isEqualTo(MediaStatus.READY);
		verify(mediaRepo, never()).save(any());
	}
	
	// -------- markReadyHls --------
	
	@Test
	void markReadyHls_happyPath_setsFields_andReady_andHls() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.PROCESSING);
		givenFindById(id, asset);
		when(mediaRepo.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
		
		String playback = "https://cdn.test/media/" + id + "/hls/master.m3u8";
		String thumb = "https://cdn.test/media/" + id + "/hls/thumbnail.jpg";
		
		MediaAsset saved = updater.markReadyHls(id, playback, thumb, 123, 1920, 1080);
		
		assertThat(saved.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(saved.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.HLS);
		assertThat(saved.getPlaybackUrl()).isEqualTo(playback);
		assertThat(saved.getThumbnailUrl()).isEqualTo(thumb);
		assertThat(saved.getDurationSeconds()).isEqualTo(123);
		assertThat(saved.getWidth()).isEqualTo(1920);
		assertThat(saved.getHeight()).isEqualTo(1080);
		verify(mediaRepo, times(1)).save(asset);
	}
	
	@Test
	void markReadyHls_missingPlaybackUrl_throws() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.PROCESSING);
		givenFindById(id, asset);
		
		assertThatThrownBy(() -> updater.markReadyHls(id, "  ", null, null, null, null))
				.isInstanceOf(SoundConnectException.class);
		
		verify(mediaRepo, never()).save(any());
	}
	
	@Test
	void markReadyHls_onNonVideo_stillSetsFields_andReady() {
		UUID id = UUID.randomUUID();
		MediaAsset image = newAsset(MediaKind.IMAGE, MediaStatus.PROCESSING);
		givenFindById(id, image);
		when(mediaRepo.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
		
		String playback = "https://cdn.test/media/" + id + "/source.png";
		
		MediaAsset saved = updater.markReadyHls(id, playback, null, null, null, null);
		
		assertThat(saved.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(saved.getPlaybackUrl()).isEqualTo(playback);
		assertThat(saved.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.HLS);
		verify(mediaRepo, times(1)).save(image);
	}
	
	@Test
	void markReadyHls_when_terminal_is_idempotent() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.READY);
		givenFindById(id, asset);
		
		MediaAsset res = updater.markReadyHls(id, "x", "y", 1, 2, 3);
		
		assertThat(res.getStatus()).isEqualTo(MediaStatus.READY);
		verify(mediaRepo, never()).save(any());
	}
	
	// -------- markFailed --------
	
	@Test
	void markFailed_setsFailed_when_nonTerminal() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.PROCESSING);
		givenFindById(id, asset);
		when(mediaRepo.save(any(MediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));
		
		MediaAsset saved = updater.markFailed(id);
		
		assertThat(saved.getStatus()).isEqualTo(MediaStatus.FAILED);
		verify(mediaRepo, times(1)).save(asset);
	}
	
	@Test
	void markFailed_when_terminal_is_idempotent() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = newVideoAsset(MediaStatus.FAILED);
		givenFindById(id, asset);
		
		MediaAsset res = updater.markFailed(id);
		
		assertThat(res.getStatus()).isEqualTo(MediaStatus.FAILED);
		verify(mediaRepo, never()).save(any());
	}
	
	// -------- helpers --------
	
	private void givenFindById(UUID id, MediaAsset asset) {
		ReflectionTestUtils.setField(asset, "id", id); // BaseEntity.id'yi set et
		when(mediaRepo.findById(eq(id))).thenReturn(Optional.of(asset));
	}
	
	private static MediaAsset newVideoAsset(MediaStatus status) {
		return newAsset(MediaKind.VIDEO, status);
	}
	
	private static MediaAsset newAsset(MediaKind kind, MediaStatus status) {
		return MediaAsset.builder()
		                 .kind(kind)
		                 .status(status)
		                 .visibility(MediaVisibility.PUBLIC)
		                 .ownerType(MediaOwnerType.USER)
		                 .ownerId(UUID.randomUUID())
		                 .mimeType(kind == MediaKind.IMAGE ? "image/png" :
				                           kind == MediaKind.AUDIO ? "audio/mpeg" : "video/mp4")
		                 .size(123L)
		                 .build();
	}
}