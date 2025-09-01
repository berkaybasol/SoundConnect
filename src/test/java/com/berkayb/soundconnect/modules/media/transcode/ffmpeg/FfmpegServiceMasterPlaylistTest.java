// src/test/java/com/berkayb/soundconnect/modules/media/transcode/ffmpeg/FfmpegServiceMasterPlaylistTest.java
package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class FfmpegServiceMasterPlaylistTest {
	
	TranscodeProperties props;
	FfmpegService ffmpeg;
	
	@BeforeEach
	void setup() {
		props = new TranscodeProperties();
		// Ladder'ı büyükten küçüğe olacak şekilde set etmiyoruz; writeMasterPlaylist sıralamaya bakmaz,
		// biz beklenen path'leri doğrulayacağız.
		TranscodeVariant v1080 = new TranscodeVariant();
		v1080.setHeight(1080);
		v1080.setVideoBitrate("6000k"); // 6_000_000 bps
		v1080.setAudioBitrate("192k");  //   192_000 bps
		
		TranscodeVariant v720 = new TranscodeVariant();
		v720.setHeight(720);
		v720.setVideoBitrate("3500k"); // 3_500_000 bps
		v720.setAudioBitrate("128k");  //   128_000 bps
		
		props.setLadder(List.of(v1080, v720));
		props.setContainer(Container.FMP4);
		
		ffmpeg = new FfmpegService(props);
	}
	
	@Test
	void writeMasterPlaylist_writes_expected_hls_master_contents() throws Exception {
		Path outDir = Files.createTempDirectory("ffmpeg-master-test-");
		try {
			// private metodu reflection ile çağırıyoruz
			ReflectionTestUtils.invokeMethod(
					ffmpeg,
					"writeMasterPlaylist",
					outDir,
					props.getLadder(),
					props.getContainer()
			);
			
			Path master = outDir.resolve("master.m3u8");
			assertThat(Files.exists(master)).isTrue();
			
			String text = Files.readString(master, StandardCharsets.UTF_8);
			
			// Başlık ve sürüm
			assertThat(text).contains("#EXTM3U");
			assertThat(text).contains("#EXT-X-VERSION:7");
			
			// 1080p varyant satırı
			long bw1080 = 6_000_000L + 192_000L; // video+audio
			// width hesap: round(1080*16/9) → 1920, ardından en yakın çift (zaten çift).
			assertThat(text).contains("#EXT-X-STREAM-INF:BANDWIDTH=" + bw1080 + ",RESOLUTION=1920x1080");
			assertThat(text).contains("1080p/index.m3u8");
			
			// 720p varyant satırı
			long bw720 = 3_500_000L + 128_000L;
			// width hesap: round(720*16/9)=1280, çift'e yuvarla → 1280
			assertThat(text).contains("#EXT-X-STREAM-INF:BANDWIDTH=" + bw720 + ",RESOLUTION=1280x720");
			assertThat(text).contains("720p/index.m3u8");
			
			// CODECS deklarasyonu her varyantta bekleniyor
			assertThat(text).contains(",CODECS=\"avc1.42E01E,mp4a.40.2\"");
		} finally {
			// Temizlik
			try { Files.walk(outDir).sorted(java.util.Comparator.reverseOrder())
			           .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); }
			catch (Exception ignored) {}
		}
	}
	
	@Test
	void kbpsToBps_parses_k_suffix_and_plain_numbers() {
		// private yardımcı metodu reflection ile test ediyoruz
		Long v1 = ReflectionTestUtils.invokeMethod(ffmpeg, "kbpsToBps", "6000k");
		Long v2 = ReflectionTestUtils.invokeMethod(ffmpeg, "kbpsToBps", "192k");
		Long v3 = ReflectionTestUtils.invokeMethod(ffmpeg, "kbpsToBps", "128000"); // düz sayı
		Long v4 = ReflectionTestUtils.invokeMethod(ffmpeg, "kbpsToBps", (String) null);
		
		assertThat(v1).isEqualTo(6_000_000L);
		assertThat(v2).isEqualTo(192_000L);
		assertThat(v3).isEqualTo(128_000L);
		assertThat(v4).isEqualTo(0L);
	}
}