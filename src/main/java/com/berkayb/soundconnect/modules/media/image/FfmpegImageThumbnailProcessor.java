package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.NativeProcessEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnImageVariantWorker
@RequiredArgsConstructor
@Slf4j
public class FfmpegImageThumbnailProcessor implements ImageThumbnailProcessor {

	private final FfprobeService ffprobeService;
	private final TranscodeProperties transcodeProperties;
	private final MediaImageVariantProperties properties;

	@Override
	public ProcessedImageThumbnail createThumbnail(Path source, Path target)
			throws IOException, InterruptedException {
		Map<String, Integer> sourceMetadata = ffprobeService.probe(source);
		int sourceWidth = requiredDimension(sourceMetadata, "width");
		int sourceHeight = requiredDimension(sourceMetadata, "height");
		long sourcePixels = Math.multiplyExact((long) sourceWidth, (long) sourceHeight);
		if (sourcePixels > properties.getMaxSourcePixels()) {
			throw new IOException("Image pixel count exceeds the configured safe limit");
		}

		Files.createDirectories(target.getParent());
		int maxDimension = properties.getThumbnailMaxDimension();
		String scale = "scale=w='min(" + maxDimension + ",iw)':h='min("
				+ maxDimension + ",ih)':force_original_aspect_ratio=decrease:flags=lanczos,setsar=1";
		List<String> command = List.of(
				transcodeProperties.getFfmpegBinary(),
				"-hide_banner",
				"-loglevel", "error",
				"-y",
				"-i", source.toAbsolutePath().toString(),
				"-map_metadata", "-1",
				"-frames:v", "1",
				"-an",
				"-sn",
				"-vf", scale,
				"-q:v", Integer.toString(jpegQualityScale(properties.getJpegQuality())),
				target.toAbsolutePath().toString()
		);
		run(command, target.getParent());

		Map<String, Integer> thumbnailMetadata = ffprobeService.probe(target);
		int thumbnailWidth = requiredDimension(thumbnailMetadata, "width");
		int thumbnailHeight = requiredDimension(thumbnailMetadata, "height");
		if (thumbnailWidth > maxDimension || thumbnailHeight > maxDimension) {
			throw new IOException("Generated thumbnail exceeds configured dimensions");
		}
		return new ProcessedImageThumbnail(
				sourceWidth, sourceHeight, thumbnailWidth, thumbnailHeight);
	}

	private void run(List<String> command, Path workDirectory) throws IOException, InterruptedException {
		ProcessBuilder processBuilder = processBuilder(command, workDirectory);
		Process process = processBuilder.start();
		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread stdoutReader = streamReader(process.getInputStream(), stdout, "image-thumbnail-stdout");
		Thread stderrReader = streamReader(process.getErrorStream(), stderr, "image-thumbnail-stderr");
		stdoutReader.start();
		stderrReader.start();

		boolean completed;
		try {
			completed = process.waitFor(properties.getProcessTimeoutSeconds(), TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			terminate(process);
			Thread.currentThread().interrupt();
			throw interrupted;
		}
		if (!completed) {
			terminate(process);
			throw new IOException("Image thumbnail process timed out");
		}
		join(stdoutReader);
		join(stderrReader);
		if (process.exitValue() != 0 || !Files.isRegularFile(commandTarget(command))) {
			log.warn("[media-image] thumbnail process failed exit={} stderrTail={}",
					process.exitValue(), safeTail(stderr, 1500));
			throw new IOException("Image thumbnail process failed with exit=" + process.exitValue());
		}
	}

	ProcessBuilder processBuilder(List<String> command, Path workDirectory) {
		ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(workDirectory.toFile());
		NativeProcessEnvironment.sanitize(processBuilder);
		return processBuilder;
	}

	private static Path commandTarget(List<String> command) {
		return Path.of(command.get(command.size() - 1));
	}

	private static int requiredDimension(Map<String, Integer> metadata, String key) throws IOException {
		Integer value = metadata.get(key);
		if (value == null || value <= 0) {
			throw new IOException("Image metadata is missing a valid " + key);
		}
		return value;
	}

	private static int jpegQualityScale(double quality) {
		return Math.max(2, Math.min(10, 2 + (int) Math.round((0.95d - quality) / 0.45d * 8d)));
	}

	private static Thread streamReader(InputStream input, StringBuilder buffer, String name) {
		Thread reader = new Thread(() -> {
			try (BufferedReader lines = new BufferedReader(
					new InputStreamReader(input, StandardCharsets.UTF_8))) {
				String line;
				while ((line = lines.readLine()) != null && buffer.length() < 16_000) {
					buffer.append(line).append('\n');
				}
			} catch (IOException ignored) {
				// Process exit/timeout remains the authoritative failure signal.
			}
		}, name);
		reader.setDaemon(true);
		return reader;
	}

	private static void terminate(Process process) {
		process.destroy();
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException interrupted) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void join(Thread reader) throws InterruptedException {
		reader.join(TimeUnit.SECONDS.toMillis(2));
		if (reader.isAlive()) {
			reader.interrupt();
		}
	}

	private static String safeTail(StringBuilder value, int limit) {
		return value.length() <= limit ? value.toString() : value.substring(value.length() - limit);
	}
}
