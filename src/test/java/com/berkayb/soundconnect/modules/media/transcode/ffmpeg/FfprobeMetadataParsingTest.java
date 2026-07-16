package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FfprobeMetadataParsingTest {

	@Test
	void parsesCommonFractionalAndDecimalFrameRates() {
		assertThat(FfprobeService.parseRational("30000/1001")).isCloseTo(
				29.970, org.assertj.core.data.Offset.offset(0.001));
		assertThat(FfprobeService.parseRational("60")).isEqualTo(60.0);
	}

	@Test
	void malformedOrZeroFrameRateFailsClosed() {
		assertThat(FfprobeService.parseRational("0/0")).isNull();
		assertThat(FfprobeService.parseRational("N/A")).isNull();
		assertThat(FfprobeService.parseRational("oops")).isNull();
	}

	@Test
	void roundsValidatedSubsecondDurationUpForPersistence() {
		assertThat(new VideoProbeMetadata(0.25, 640, 360, 30.0).roundedDurationSeconds())
				.isEqualTo(1);
	}

	@Test
	void aggregatesEveryRealVideoStreamConservativelyAndIgnoresCoverArt() throws Exception {
		var root = new ObjectMapper().readTree("""
				{
				  "format": {"duration": "100"},
				  "streams": [
				    {"codec_type":"video","width":640,"height":360,"duration":"100",
				     "avg_frame_rate":"30/1","r_frame_rate":"30/1"},
				    {"codec_type":"video","width":7680,"height":4320,"duration":"120",
				     "avg_frame_rate":"120/1","r_frame_rate":"120/1"},
				    {"codec_type":"video","width":10000,"height":10000,
				     "avg_frame_rate":"1/1","r_frame_rate":"1/1",
				     "disposition":{"attached_pic":1}}
				  ]
				}
				""");

		VideoProbeMetadata metadata = FfprobeService.aggregateVideoMetadata(
				root, FfprobeService.videoStreams(root));

		assertThat(metadata.durationSeconds()).isEqualTo(120.0);
		assertThat(metadata.width()).isEqualTo(7680);
		assertThat(metadata.height()).isEqualTo(4320);
		assertThat(metadata.maxDimension()).isEqualTo(7680);
		assertThat(metadata.maxPixelCount()).isEqualTo(33_177_600L);
		assertThat(metadata.frameRate()).isEqualTo(120.0);
	}
}
