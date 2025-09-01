// src/test/java/com/berkayb/soundconnect/modules/media/transcode/upload/HlsUploaderTest.java
package com.berkayb.soundconnect.modules.media.transcode.upload;

import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class HlsUploaderTest {
	
	@TempDir
	Path tmp;
	
	@Captor ArgumentCaptor<Path> pathCaptor;
	@Captor ArgumentCaptor<String> keyCaptor;
	@Captor ArgumentCaptor<String> ctCaptor;
	@Captor ArgumentCaptor<String> cacheCaptor;
	
	private static String join(String prefix, String rel) {
		return prefix.endsWith("/") ? prefix + rel : prefix + "/" + rel;
	}
	
	@Test
	void uploadHlsTree_thumbnailOutside_uploadsAll_withCorrectHeaders_andReturnsUrlsAndCount() throws Exception {
		// Arrange
		StorageClient storage = mock(StorageClient.class);
		HlsUploader uploader = new HlsUploader(storage);
		
		Path out = Files.createDirectory(tmp.resolve("out"));
		// master + iki varyant
		Path master = Files.writeString(out.resolve("master.m3u8"), "#EXTM3U\n");
		Path v720 = Files.createDirectory(out.resolve("720p"));
		Path v360 = Files.createDirectory(out.resolve("360p"));
		
		Path v720Index = Files.writeString(v720.resolve("index.m3u8"), "#EXTM3U\n");
		Path v720Init  = Files.writeString(v720.resolve("init.mp4"), "init");
		Path v720Seg   = Files.writeString(v720.resolve("seg_00001.m4s"), "seg");
		
		Path v360Index = Files.writeString(v360.resolve("index.m3u8"), "#EXTM3U\n");
		Path v360Seg   = Files.writeString(v360.resolve("seg_00001.m4s"), "seg");
		
		// Thumbnail ağacın dışında
		Path thumb = Files.writeString(tmp.resolve("thumbnail.jpg"), "jpgdata");
		
		String prefix = "media/abc123/hls";
		
		// publicUrl taklidi
		when(storage.publicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0, String.class));
		
		// Act
		HlsUploadResult res = uploader.uploadHlsTree(out, prefix, thumb);
		
		// Assert: putFile çağrıları (ağaçtaki 6 dosya + dış thumb = 7)
		verify(storage, times(7)).putFile(pathCaptor.capture(), keyCaptor.capture(), ctCaptor.capture(), cacheCaptor.capture());
		
		// İçerik tipi + cache-control doğrulamaları
		// master.m3u8
		int masterIdx = keyCaptor.getAllValues().indexOf(join(prefix, "master.m3u8"));
		assertThat(ctCaptor.getAllValues().get(masterIdx)).isEqualTo("application/vnd.apple.mpegurl");
		assertThat(cacheCaptor.getAllValues().get(masterIdx)).contains("max-age=30");
		
		// 720p/index.m3u8
		int idx720 = keyCaptor.getAllValues().indexOf(join(prefix, "720p/index.m3u8"));
		assertThat(ctCaptor.getAllValues().get(idx720)).isEqualTo("application/vnd.apple.mpegurl");
		
		// 720p/init.mp4
		int initIdx = keyCaptor.getAllValues().indexOf(join(prefix, "720p/init.mp4"));
		assertThat(ctCaptor.getAllValues().get(initIdx)).isEqualTo("video/mp4");
		assertThat(cacheCaptor.getAllValues().get(initIdx)).contains("immutable");
		
		// 720p/seg_00001.m4s
		int fmp4Idx = keyCaptor.getAllValues().indexOf(join(prefix, "720p/seg_00001.m4s"));
		assertThat(ctCaptor.getAllValues().get(fmp4Idx)).isEqualTo("video/iso.segment");
		
		// 360p/index.m3u8 & 360p/seg_00001.m4s
		int idx360 = keyCaptor.getAllValues().indexOf(join(prefix, "360p/index.m3u8"));
		assertThat(ctCaptor.getAllValues().get(idx360)).isEqualTo("application/vnd.apple.mpegurl");
		int seg360 = keyCaptor.getAllValues().indexOf(join(prefix, "360p/seg_00001.m4s"));
		assertThat(ctCaptor.getAllValues().get(seg360)).isEqualTo("video/iso.segment");
		
		// dış thumbnail
		int thumbIdx = keyCaptor.getAllValues().indexOf(join(prefix, "thumbnail.jpg"));
		assertThat(ctCaptor.getAllValues().get(thumbIdx)).isEqualTo("image/jpeg");
		
		// playback & thumbnail URL’leri
		assertThat(res.playbackUrl()).isEqualTo("https://cdn.example/" + join(prefix, "master.m3u8"));
		assertThat(res.thumbnailUrl()).isEqualTo("https://cdn.example/" + join(prefix, "thumbnail.jpg"));
		
		// yüklenen obje sayısı
		assertThat(res.objectCount()).isEqualTo(7);
	}
	
	@Test
	void uploadHlsTree_thumbnailInsideTree_isNotDoubleUploaded_butUrlReturned() throws Exception {
		// Arrange
		StorageClient storage = mock(StorageClient.class);
		HlsUploader uploader = new HlsUploader(storage);
		
		Path out = Files.createDirectory(tmp.resolve("out2"));
		Files.writeString(out.resolve("master.m3u8"), "#EXTM3U\n");
		// thumbnail HLS ağacının içinde
		Path thumbInside = Files.writeString(out.resolve("thumbnail.jpg"), "jpg");
		
		String prefix = "media/xyz/hls";
		when(storage.publicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0, String.class));
		
		// Act
		HlsUploadResult res = uploader.uploadHlsTree(out, prefix, thumbInside);
		
		// Assert
		// Yürüyüşte 2 dosya var: master.m3u8 + thumbnail.jpg → 2 putFile çağrısı
		verify(storage, times(2)).putFile(any(Path.class), anyString(), anyString(), anyString());
		
		// Thumbnail ağacın içinde olduğundan ayrıca PUT yapılmaz,
		// ama URL yine döndürülür.
		assertThat(res.thumbnailUrl()).isEqualTo("https://cdn.example/" + join(prefix, "thumbnail.jpg"));
		assertThat(res.playbackUrl()).isEqualTo("https://cdn.example/" + join(prefix, "master.m3u8"));
		assertThat(res.objectCount()).isEqualTo(2);
	}
}