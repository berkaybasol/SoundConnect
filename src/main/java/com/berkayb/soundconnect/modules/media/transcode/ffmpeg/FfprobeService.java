package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeResourceValidator;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs one bounded ffprobe process and parses only trusted scalar metadata. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FfprobeService {

	private final ObjectMapper mapper;
	private final TranscodeProperties transcodeProperties;
	private final VideoTranscodeResourceValidator videoResourceValidator;

	/** Compatibility metadata used by the image processor. */
	public Map<String, Integer> probe(Path input) throws IOException, InterruptedException {
		JsonNode root = executeProbe(input);
		List<JsonNode> videoStreams = videoStreams(root);
		JsonNode videoStream = videoStreams.isEmpty() ? null : videoStreams.getFirst();

		Map<String, Integer> metadata = new HashMap<>();
		metadata.put("durationSeconds", roundedDownDuration(durationSeconds(root, videoStreams)));
		metadata.put("width", videoStream == null ? null : asPositiveIntOrNull(videoStream.get("width")));
		metadata.put("height", videoStream == null ? null : asPositiveIntOrNull(videoStream.get("height")));
		log.debug("[ffprobe] generic metadata file={} -> {}", input, metadata);
		return metadata;
	}

	/**
	 * Strict video path. Probe failure, incomplete metadata and resource-budget
	 * rejection all propagate; callers must not invoke FFmpeg after an exception.
	 */
	public VideoProbeMetadata probeVideo(Path input) throws IOException, InterruptedException {
		JsonNode root = executeProbe(input);
		VideoProbeMetadata metadata = aggregateVideoMetadata(root, videoStreams(root));
		videoResourceValidator.validate(input, metadata);
		log.debug("[ffprobe] validated video metadata file={} -> {}", input, metadata);
		return metadata;
	}

	private JsonNode executeProbe(Path input) throws IOException, InterruptedException {
		if (input == null) throw new SoundConnectException(ErrorType.MEDIA_INPUT_PATH_REQUIRED);

		List<String> command = List.of(
				transcodeProperties.getFfprobeBinary(),
				"-v", "error",
				"-print_format", "json",
				"-show_format",
				"-show_streams",
				"-show_entries",
				"format=duration:format_tags=:stream=codec_type,width,height,duration,avg_frame_rate,r_frame_rate:stream_tags=:stream_disposition=attached_pic",
				input.toAbsolutePath().toString()
		);

		ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(false);
		NativeProcessEnvironment.sanitize(processBuilder);
		Process process = processBuilder.start();
		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread stdoutReader = readerThread(process, true, stdout, "ffprobe-stdout");
		Thread stderrReader = readerThread(process, false, stderr, "ffprobe-stderr");
		stdoutReader.start();
		stderrReader.start();

		int timeoutSeconds = transcodeProperties.getFfprobeTimeoutSec();
		boolean finished;
		try {
			finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw interrupted;
		}
		if (!finished) {
			process.destroyForcibly();
			joinReader(stdoutReader);
			joinReader(stderrReader);
			throw new IOException("ffprobe timed out after " + timeoutSeconds + " seconds");
		}
		joinReader(stdoutReader);
		joinReader(stderrReader);

		int exitCode = process.exitValue();
		if (exitCode != 0) {
			log.error("[ffprobe] exit={} stderrTail={}", exitCode, safeTail(stderr, 2000));
			throw new IOException("ffprobe failed with exit=" + exitCode);
		}
		return mapper.readTree(stdout.toString());
	}

	private static Thread readerThread(
			Process process,
			boolean stdout,
			StringBuilder destination,
			String name
	) {
		Thread reader = new Thread(() -> readStream(process, stdout, destination), name);
		reader.setDaemon(true);
		return reader;
	}

	private static void joinReader(Thread reader) throws InterruptedException {
		reader.join(TimeUnit.SECONDS.toMillis(5));
		if (reader.isAlive()) reader.interrupt();
	}

	static List<JsonNode> videoStreams(JsonNode root) {
		JsonNode streams = root == null ? null : root.get("streams");
		if (streams == null || !streams.isArray()) return List.of();
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode stream : streams) {
			if ("video".equalsIgnoreCase(stream.path("codec_type").asText())
					&& stream.path("disposition").path("attached_pic").asInt(0) != 1) {
				result.add(stream);
			}
		}
		return List.copyOf(result);
	}

	static VideoProbeMetadata aggregateVideoMetadata(
			JsonNode root,
			List<JsonNode> videoStreams
	) {
		Integer primaryWidth = null;
		Integer primaryHeight = null;
		long maxPixelCount = 0;
		int maxDimension = 0;
		Double maxFrameRate = null;
		for (JsonNode stream : videoStreams) {
			Integer width = asPositiveIntOrNull(stream.get("width"));
			Integer height = asPositiveIntOrNull(stream.get("height"));
			if (width != null && height != null) {
				long pixels = (long) width * height;
				if (pixels > maxPixelCount) {
					maxPixelCount = pixels;
					primaryWidth = width;
					primaryHeight = height;
				}
				maxDimension = Math.max(maxDimension, Math.max(width, height));
			}
			Double frameRate = parseFrameRate(stream);
			if (frameRate != null && (maxFrameRate == null || frameRate > maxFrameRate)) {
				maxFrameRate = frameRate;
			}
		}
		return new VideoProbeMetadata(
				durationSeconds(root, videoStreams),
				primaryWidth,
				primaryHeight,
				maxFrameRate,
				maxPixelCount == 0 ? null : maxPixelCount,
				maxDimension == 0 ? null : maxDimension
		);
	}

	private static Double durationSeconds(JsonNode root, List<JsonNode> videoStreams) {
		Double formatDuration = null;
		JsonNode format = root == null ? null : root.get("format");
		if (format != null && format.hasNonNull("duration")) {
			formatDuration = parsePositiveDouble(format.get("duration").asText(null));
		}
		Double streamDuration = null;
		for (JsonNode videoStream : videoStreams) {
			if (!videoStream.hasNonNull("duration")) continue;
			Double candidate = parsePositiveDouble(videoStream.get("duration").asText(null));
			if (candidate != null && (streamDuration == null || candidate > streamDuration)) {
				streamDuration = candidate;
			}
		}
		if (formatDuration == null) return streamDuration;
		if (streamDuration == null) return formatDuration;
		return Math.max(formatDuration, streamDuration);
	}

	private static Double parseFrameRate(JsonNode videoStream) {
		if (videoStream == null) return null;
		Double average = parseRational(videoStream.path("avg_frame_rate").asText(null));
		Double nominal = parseRational(videoStream.path("r_frame_rate").asText(null));
		if (average == null) return nominal;
		if (nominal == null) return average;
		return Math.max(average, nominal);
	}

	static Double parseRational(String raw) {
		if (raw == null || raw.isBlank() || "N/A".equalsIgnoreCase(raw)) return null;
		try {
			String[] parts = raw.trim().split("/", -1);
			double value;
			if (parts.length == 1) {
				value = Double.parseDouble(parts[0]);
			} else if (parts.length == 2) {
				double denominator = Double.parseDouble(parts[1]);
				if (denominator == 0) return null;
				value = Double.parseDouble(parts[0]) / denominator;
			} else {
				return null;
			}
			return Double.isFinite(value) && value > 0 ? value : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Integer asPositiveIntOrNull(JsonNode node) {
		if (node == null || !node.canConvertToInt()) return null;
		int value = node.asInt();
		return value > 0 ? value : null;
	}

	private static Double parsePositiveDouble(String raw) {
		if (raw == null) return null;
		try {
			double value = Double.parseDouble(raw.trim());
			return Double.isFinite(value) && value > 0 ? value : null;
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static Integer roundedDownDuration(Double duration) {
		if (duration == null || duration > Integer.MAX_VALUE) return null;
		return (int) Math.floor(duration);
	}

	private static String safeTail(StringBuilder buffer, int maxLength) {
		int length = buffer.length();
		return length <= maxLength ? buffer.toString() : buffer.substring(length - maxLength);
	}

	private static void readStream(Process process, boolean stdout, StringBuilder destination) {
		try (var reader = new BufferedReader(new InputStreamReader(
				stdout ? process.getInputStream() : process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (destination.length() < 32_000) destination.append(line).append('\n');
			}
		} catch (IOException ignored) {
			// Process exit/timeout is authoritative; reader shutdown is best effort.
		}
	}
}
