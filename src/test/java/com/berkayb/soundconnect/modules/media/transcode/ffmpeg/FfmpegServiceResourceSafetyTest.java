package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfmpegServiceResourceSafetyTest {

	@TempDir Path tempDir;

	@Test
	void variantCommandHardCapsDurationAndUsesVbvBounds() throws Exception {
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxDurationSeconds(900);
		TranscodeVariant variant = variant("6000k", "192k");
		FfmpegService service = new FfmpegService(properties);

		List<String> command = service.buildVariantCommand(
				tempDir.resolve("source.mp4"),
				tempDir.resolve("1080p"),
				variant,
				"scale=-2:1080",
				variant.getVideoBitrate(),
				variant.getAudioBitrate()
		);

		assertThat(command).containsSubsequence(
				"-b:v", "6000k", "-maxrate", "6000k", "-bufsize", "12000k");
		assertThat(command).containsSubsequence("-t", "900", "-i");
		int inputIndex = command.indexOf("-i");
		assertThat(command.subList(inputIndex + 1, command.size()))
				.containsSubsequence("-t", "900");
	}

	@Test
	void bitrateBufferParsingFailsClosed() {
		assertThatThrownBy(() -> FfmpegService.doubledBitrate("not-a-rate"))
				.isInstanceOf(java.io.IOException.class)
				.hasMessageContaining("invalid video bitrate");
	}

	@Test
	void rejectsActualGeneratedOutputAboveHardLimit() throws Exception {
		Path output = Files.createDirectories(tempDir.resolve("out"));
		Files.write(tempDir.resolve("source.mp4"), new byte[4]);
		Files.write(output.resolve("segment.m4s"), new byte[12]);
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxEstimatedOutputBytes(10);
		properties.setMaxTempWorkBytes(100);
		FfmpegService service = new FfmpegService(properties);

		assertThatThrownBy(() -> service.assertOutputBudgets(tempDir, List.of(output)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("actual HLS output");
	}

	@Test
	void rejectsActualWorkspaceAboveHardLimit() throws Exception {
		Path output = Files.createDirectories(tempDir.resolve("out"));
		Files.write(tempDir.resolve("source.mp4"), new byte[8]);
		Files.write(output.resolve("segment.m4s"), new byte[4]);
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxEstimatedOutputBytes(100);
		properties.setMaxTempWorkBytes(10);
		FfmpegService service = new FfmpegService(properties);

		assertThatThrownBy(() -> service.assertOutputBudgets(tempDir, List.of(output)))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("workspace");
	}

	private static TranscodeVariant variant(String videoBitrate, String audioBitrate) {
		TranscodeVariant variant = new TranscodeVariant();
		variant.setHeight(1080);
		variant.setVideoBitrate(videoBitrate);
		variant.setAudioBitrate(audioBitrate);
		return variant;
	}
}
