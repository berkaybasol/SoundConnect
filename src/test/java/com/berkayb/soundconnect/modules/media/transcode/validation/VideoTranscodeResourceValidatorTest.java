package com.berkayb.soundconnect.modules.media.transcode.validation;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.VideoProbeMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoTranscodeResourceValidatorTest {

	@TempDir Path tempDir;
	@Mock TranscodeDiskSpaceInspector diskSpaceInspector;

	private TranscodeProperties properties;
	private VideoTranscodeResourceValidator validator;
	private Path source;

	@BeforeEach
	void setUp() throws Exception {
		properties = properties();
		validator = new VideoTranscodeResourceValidator(properties, diskSpaceInspector);
		source = tempDir.resolve("source.mp4");
		Files.write(source, new byte[1024]);
	}

	@Test
	void admitsBoundedVideoAndReturnsConservativeLadderEstimate() throws Exception {
		when(diskSpaceInspector.usableBytes(any(Path.class))).thenReturn(10_000_000_000L);

		long estimate = validator.validate(
				source, new VideoProbeMetadata(60.0, 1920, 1080, 59.94));

		assertThat(estimate).isPositive().isLessThan(properties.getMaxEstimatedOutputBytes());
	}

	@Test
	void rejectsMissingOrFailedProbeMetadata() {
		assertThatThrownBy(() -> validator.validate(source, null))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("ffprobe");
	}

	@Test
	void rejectsDurationBeyondProfileVideoLimit() {
		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(900.01, 1920, 1080, 30.0)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("duration");
	}

	@Test
	void rejectsResolutionBeyond4kPixelBudgetIncludingPortraitVideo() {
		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(30.0, 3840, 3840, 30.0)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("pixel count");
	}

	@Test
	void rejectsFrameRateAbove60Fps() {
		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(30.0, 1920, 1080, 60.01)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("frame rate");
	}

	@Test
	void rejectsEstimatedOutputBeyondConfiguredBudget() {
		properties.setMaxEstimatedOutputBytes(1_000_000L);

		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(60.0, 1920, 1080, 30.0)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("output");
	}

	@Test
	void rejectsWorkingSetBeyondConfiguredTempBudget() throws Exception {
		properties.setMaxTempWorkBytes(5_000_000L);
		properties.setMaxEstimatedOutputBytes(100_000_000L);

		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(60.0, 1920, 1080, 30.0)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("working-set");
	}

	@Test
	void rejectsWhenActualTempFilesystemCannotFitOutputAndReserve() throws Exception {
		when(diskSpaceInspector.usableBytes(any(Path.class))).thenReturn(1_000_000L);

		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(60.0, 1920, 1080, 30.0)))
				.isInstanceOf(TranscodeCapacityUnavailableException.class)
				.hasMessageContaining("disk space");
	}

	@Test
	void rejectsMalformedLadderBitrateInsteadOfUnderestimating() {
		properties.getLadder().getFirst().setVideoBitrate("not-a-rate");

		assertThatThrownBy(() -> validator.validate(
				source, new VideoProbeMetadata(60.0, 1920, 1080, 30.0)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("bitrate");
	}

	private static TranscodeProperties properties() {
		TranscodeVariant variant = new TranscodeVariant();
		variant.setHeight(1080);
		variant.setVideoBitrate("1000k");
		variant.setAudioBitrate("128k");
		TranscodeProperties properties = new TranscodeProperties();
		properties.setLadder(List.of(variant));
		return properties;
	}
}
