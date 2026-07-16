package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FfmpegImageThumbnailProcessorTest {

	@Mock FfprobeService ffprobeService;
	@TempDir Path tempDirectory;

	@Test
	void createThumbnail_rejectsPixelBombBeforeStartingDecoder() throws Exception {
		Path source = tempDirectory.resolve("source.webp");
		Path target = tempDirectory.resolve("thumbnail.jpg");
		when(ffprobeService.probe(source)).thenReturn(Map.of(
				"width", 20_000,
				"height", 20_000
		));
		MediaImageVariantProperties properties = new MediaImageVariantProperties();
		properties.setMaxSourcePixels(60_000_000L);
		FfmpegImageThumbnailProcessor processor = new FfmpegImageThumbnailProcessor(
				ffprobeService, new TranscodeProperties(), properties);

		assertThatThrownBy(() -> processor.createThumbnail(source, target))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("pixel count");
	}
}
