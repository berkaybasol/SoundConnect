package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs timeout-bounded FFmpeg processes and enforces actual on-disk output
 * budgets between each expensive encoding step.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FfmpegService {

	private final TranscodeProperties props;

	/** Generates every configured HLS variant and the master playlist. */
	public void generateHlsLadder(Path input, Path outDir) throws IOException, InterruptedException {
		Files.createDirectories(outDir);

		for (TranscodeVariant variant : props.getLadder()) {
			Path variantDir = outDir.resolve(variant.getHeight() + "p");
			Files.createDirectories(variantDir);
			runFfmpegForVariant(input, variantDir, variant);
			// Preflight is conservative but cannot predict every malformed input.
			// Measure actual output before starting the next expensive encode.
			assertOutputBudgets(input.getParent(), List.of(outDir));
		}

		writeMasterPlaylist(outDir, props.getLadder(), props.getContainer());
		assertOutputBudgets(input.getParent(), List.of(outDir));
		log.info("[ffmpeg] HLS ladder ready dir={}", outDir);
	}

	/** Extracts a single thumbnail and checks cumulative workspace usage. */
	public void generateThumbnail(Path input, Path outImage) throws IOException, InterruptedException {
		Files.createDirectories(outImage.getParent());

		List<String> command = new ArrayList<>();
		command.add(props.getFfmpegBinary());
		command.add("-y");
		command.add("-ss");
		command.add(String.valueOf(props.getThumbnailSecond()));
		command.add("-i");
		command.add(input.toAbsolutePath().toString());
		command.add("-frames:v");
		command.add("1");
		command.add("-q:v");
		command.add("2");
		command.add(outImage.toAbsolutePath().toString());

		List<Path> generatedRoots = new ArrayList<>();
		Path hlsOutput = outImage.getParent().resolve("out");
		if (Files.exists(hlsOutput)) generatedRoots.add(hlsOutput);
		generatedRoots.add(outImage);
		runCommand(command, outImage.getParent(), input.getParent(), generatedRoots);
		assertOutputBudgets(input.getParent(), generatedRoots);
		log.info("[ffmpeg] thumbnail ready path={}", outImage);
	}

	private void runFfmpegForVariant(Path input, Path variantDir, TranscodeVariant variant)
			throws IOException, InterruptedException {
		String scaleFilter = "scale=-2:" + variant.getHeight();
		List<String> command = buildVariantCommand(
				input,
				variantDir,
				variant,
				scaleFilter,
				variant.getVideoBitrate(),
				variant.getAudioBitrate()
		);
		runCommand(command, variantDir, input.getParent(), List.of(variantDir.getParent()));
		log.debug("[ffmpeg] variant ready height={} video={} audio={}",
				variant.getHeight(), variant.getVideoBitrate(), variant.getAudioBitrate());
	}

	/** Builds a bounded-VBR command so CRF cannot create an unbounded bitrate spike. */
	List<String> buildVariantCommand(
			Path input,
			Path variantDir,
			TranscodeVariant variant,
			String scaleFilter,
			String videoBitrate,
			String audioBitrate
	) throws IOException {
		Path playlist = variantDir.resolve("index.m3u8");
		Path segmentPattern;
		String bufferSize = doubledBitrate(videoBitrate);

		List<String> command = new ArrayList<>();
		command.add(props.getFfmpegBinary());
		command.add("-y");
		// Defense in depth against crafted/inconsistent container metadata: cap
		// both demux input and encoded output independently of ffprobe results.
		command.add("-t");
		command.add(String.valueOf(props.getMaxDurationSeconds()));
		command.add("-i");
		command.add(input.toAbsolutePath().toString());
		command.add("-t");
		command.add(String.valueOf(props.getMaxDurationSeconds()));
		command.add("-vf");
		command.add(scaleFilter);
		command.add("-c:v");
		command.add("libx264");
		command.add("-preset");
		command.add(props.getPreset());
		command.add("-crf");
		command.add(String.valueOf(props.getCrf()));
		command.add("-g");
		command.add(String.valueOf(props.getGop()));
		command.add("-keyint_min");
		command.add(String.valueOf(props.getGop()));
		command.add("-sc_threshold");
		command.add("0");
		command.add("-b:v");
		command.add(videoBitrate);
		command.add("-maxrate");
		command.add(videoBitrate);
		command.add("-bufsize");
		command.add(bufferSize);
		command.add("-c:a");
		command.add("aac");
		command.add("-b:a");
		command.add(audioBitrate);

		command.add("-f");
		command.add("hls");
		command.add("-hls_time");
		command.add(String.valueOf(props.getSegmentDurationSec()));
		command.add("-hls_list_size");
		command.add("0");
		command.add("-hls_playlist_type");
		command.add("vod");
		command.add("-hls_flags");
		command.add("independent_segments");

		if (props.getContainer() == Container.FMP4) {
			command.add("-hls_segment_type");
			command.add("fmp4");
			command.add("-hls_fmp4_init_filename");
			command.add("init.mp4");
			segmentPattern = variantDir.resolve("seg_%05d.m4s");
		} else {
			segmentPattern = variantDir.resolve("seg_%05d.ts");
		}

		command.add("-hls_segment_filename");
		command.add(segmentPattern.toAbsolutePath().toString());
		command.add(playlist.toAbsolutePath().toString());
		return command;
	}

	static String doubledBitrate(String raw) throws IOException {
		if (raw == null || raw.isBlank()) throw new IOException("video bitrate is missing");
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		String suffix = "";
		if (normalized.endsWith("k") || normalized.endsWith("m")) {
			suffix = normalized.substring(normalized.length() - 1);
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			BigDecimal value = new BigDecimal(normalized);
			if (value.signum() <= 0) throw new NumberFormatException("non-positive bitrate");
			return value.multiply(BigDecimal.valueOf(2))
					.stripTrailingZeros().toPlainString() + suffix;
		} catch (NumberFormatException invalid) {
			throw new IOException("invalid video bitrate: " + raw, invalid);
		}
	}

	/**
	 * Enforces both the published-output ceiling and the complete workspace
	 * ceiling using actual file sizes. Symbolic links are never followed.
	 */
	void assertOutputBudgets(Path workspace, List<Path> generatedRoots) throws IOException {
		long generatedBytes = 0;
		for (Path root : generatedRoots) {
			generatedBytes = addSizeExact(generatedBytes, treeSize(root));
		}
		if (generatedBytes > props.getMaxEstimatedOutputBytes()) {
			throw new VideoTranscodeRejectedException(
					"actual HLS output exceeds configured budget");
		}

		long workspaceBytes = treeSize(workspace);
		if (workspaceBytes > props.getMaxTempWorkBytes()) {
			throw new VideoTranscodeRejectedException(
					"actual transcode workspace exceeds configured budget");
		}
		log.debug("[ffmpeg] resource checkpoint generatedBytes={} workspaceBytes={}",
				generatedBytes, workspaceBytes);
	}

	private long treeSize(Path root) throws IOException {
		if (root == null || !Files.exists(root)) return 0;
		BasicFileAttributes rootAttributes = Files.readAttributes(
				root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (rootAttributes.isSymbolicLink()) {
			throw new IOException("symbolic links are not allowed in transcode output");
		}
		if (!rootAttributes.isDirectory()) {
			return rootAttributes.isRegularFile() ? rootAttributes.size() : 0;
		}
		final long[] total = {0};
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.isRegularFile()) total[0] = addSizeExact(total[0], attrs.size());
				return FileVisitResult.CONTINUE;
			}
		});
		return total[0];
	}

	private long addSizeExact(long left, long right) throws IOException {
		try {
			return Math.addExact(left, right);
		} catch (ArithmeticException overflow) {
			throw new IOException("transcode output size overflow", overflow);
		}
	}

	private void writeMasterPlaylist(Path outDir, List<TranscodeVariant> ladder, Container container)
			throws IOException {
		StringBuilder content = new StringBuilder();
		content.append("#EXTM3U\n");
		content.append("#EXT-X-VERSION:7\n");

		for (TranscodeVariant variant : ladder) {
			long bandwidth = kbpsToBps(variant.getVideoBitrate())
					+ kbpsToBps(variant.getAudioBitrate());
			int height = variant.getHeight();
			int width = (int) (Math.round(height * 16.0 / 9.0) / 2) * 2;
			content.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth)
					.append(",RESOLUTION=").append(width).append('x').append(height)
					.append(",CODECS=\"avc1.42E01E,mp4a.40.2\"\n")
					.append(height).append("p/index.m3u8\n");
		}

		Files.writeString(
				outDir.resolve("master.m3u8"),
				content.toString(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private long kbpsToBps(String raw) {
		if (raw == null) return 0;
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		if (trimmed.endsWith("k")) {
			try {
				return Math.round(Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1000);
			} catch (NumberFormatException ignored) {
				return 0;
			}
		}
		try {
			return Long.parseLong(trimmed);
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private void runCommand(
			List<String> command,
			Path workDir,
			Path workspace,
			List<Path> generatedRoots
	)
			throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command);
		if (workDir != null) builder.directory(workDir.toFile());
		builder.redirectErrorStream(false);
		NativeProcessEnvironment.sanitize(builder);
		Process process = builder.start();

		StringBuilder stdout = new StringBuilder();
		StringBuilder stderr = new StringBuilder();
		Thread stdoutReader = new Thread(
				() -> readStream(process.getInputStream(), stdout), "ffmpeg-stdout");
		Thread stderrReader = new Thread(
				() -> readStream(process.getErrorStream(), stderr), "ffmpeg-stderr");
		stdoutReader.setDaemon(true);
		stderrReader.setDaemon(true);
		stdoutReader.start();
		stderrReader.start();

		boolean completed = false;
		long timeoutNanos = TimeUnit.SECONDS.toNanos(props.getProcessTimeoutSec());
		long deadline = System.nanoTime() + timeoutNanos;
		try {
			while (!(completed = process.waitFor(1, TimeUnit.SECONDS))) {
				// Kill a runaway child while it is writing, not only after it exits.
				assertOutputBudgets(workspace, generatedRoots);
				if (System.nanoTime() - deadline >= 0) break;
			}
		} catch (InterruptedException interrupted) {
			terminateProcess(process);
			Thread.currentThread().interrupt();
			throw interrupted;
		} catch (IOException budgetExceeded) {
			terminateProcess(process);
			joinReader(stdoutReader);
			joinReader(stderrReader);
			throw budgetExceeded;
		}
		if (!completed) {
			terminateProcess(process);
			joinReader(stdoutReader);
			joinReader(stderrReader);
			log.error("[ffmpeg] timeout after={}s stderr={}",
					props.getProcessTimeoutSec(), safeTail(stderr, 2000));
			throw new IOException("ffmpeg timed out after " + props.getProcessTimeoutSec() + " seconds");
		}

		int exitCode = process.exitValue();
		joinReader(stdoutReader);
		joinReader(stderrReader);
		if (exitCode != 0) {
			log.error("[ffmpeg] failed exit={} command={} stderr={}",
					exitCode, String.join(" ", command), safeTail(stderr, 4000));
			throw new IOException("ffmpeg failed with exit " + exitCode);
		}
		log.debug("[ffmpeg] command succeeded command={} stderrTail={}",
				String.join(" ", command), safeTail(stderr, 2000));
	}

	private void terminateProcess(Process process) {
		process.destroy();
		try {
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException interrupted) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private void joinReader(Thread reader) throws InterruptedException {
		reader.join(TimeUnit.SECONDS.toMillis(5));
		if (reader.isAlive()) reader.interrupt();
	}

	private void readStream(java.io.InputStream stream, StringBuilder target) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (target.length() < 32_000) target.append(line).append('\n');
			}
		} catch (IOException ignored) {
			// The process exit code remains the authoritative result.
		}
	}

	private String safeTail(StringBuilder content, int maxLength) {
		int length = content.length();
		return length <= maxLength ? content.toString() : content.substring(length - maxLength);
	}
}
