package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeDiskSpaceInspector;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeResourceValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicit local native-process verification; no Spring context, storage or application data.
 * Set SOUNDCONNECT_REAL_FFMPEG_TEST=true and SOUNDCONNECT_TEST_FFMPEG / SOUNDCONNECT_TEST_FFPROBE
 * to executable paths, then run this test class. Default CI skips it without launching a process.
 * SOUNDCONNECT_REAL_MEDIA_EXPORT_DIR optionally retains the verified synthetic outputs for playback QA.
 */
@EnabledIfEnvironmentVariable(named = "SOUNDCONNECT_REAL_FFMPEG_TEST", matches = "true")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AnnouncementProgressiveVideoRealProcessTest {

    @TempDir Path workspace;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest(name = "normalizes a synthetic video with audio={0}")
    @ValueSource(booleans = {true, false})
    void normalizesDecodablePrivateVideoAndThumbnail(boolean withAudio) throws Exception {
        TranscodeProperties properties = properties();
        FfmpegService ffmpeg = new FfmpegService(properties);
        FfprobeService ffprobe = probeService(properties);
        Path source = workspace.resolve("synthetic-source.mkv");
        Path output = workspace.resolve("normalized/video.mp4");
        Path thumbnail = workspace.resolve("normalized/thumbnail.jpg");

        // Different input codecs force actual normalization. The silent case also verifies no upscaling.
        List<String> generate = new ArrayList<>(List.of(properties.getFfmpegBinary(), "-nostdin", "-v", "error",
                "-y", "-f", "lavfi", "-i", withAudio
                        ? "testsrc2=size=1280x800:rate=12:duration=2"
                        : "testsrc2=size=320x240:rate=12:duration=2"));
        if (withAudio) generate.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"));
        generate.addAll(List.of("-map", "0:v:0"));
        if (withAudio) generate.addAll(List.of("-map", "1:a:0", "-c:a", "pcm_s16le"));
        generate.addAll(List.of("-c:v", "mpeg4", "-q:v", "8", "-pix_fmt", "yuv420p",
                "-t", "2", "-f", "matroska", source.toString()));
        runNative(generate);

        VideoProbeMetadata sourceMetadata = ffprobe.probeVideo(source);
        assertThat(sourceMetadata.durationSeconds()).isBetween(1.9, 2.1);
        ffmpeg.generateProgressive(source, output);
        ffmpeg.generateThumbnail(output, thumbnail);

        VideoProbeMetadata normalized = ffprobe.probeVideo(output);
        int expectedWidth = withAudio ? 1152 : 320;
        int expectedHeight = withAudio ? 720 : 240;
        assertThat(normalized.width()).isEqualTo(expectedWidth);
        assertThat(normalized.height()).isEqualTo(expectedHeight);
        assertThat(normalized.durationSeconds()).isBetween(1.9, 2.15);
        assertThat(normalized.frameRate()).isBetween(11.9, 12.1);

        JsonNode technical = mapper.readTree(runNative(List.of(properties.getFfprobeBinary(),
                "-v", "error", "-print_format", "json", "-show_streams", "-show_format",
                "-show_entries", "format=format_name,duration:stream=codec_type,codec_name,pix_fmt,width,height,channels,sample_rate",
                output.toString())));
        List<JsonNode> videos = streams(technical, "video");
        List<JsonNode> audios = streams(technical, "audio");
        assertThat(videos).hasSize(1);
        assertThat(videos.getFirst().path("codec_name").asText()).isEqualTo("h264");
        assertThat(videos.getFirst().path("pix_fmt").asText()).isEqualTo("yuv420p");
        assertThat(technical.path("format").path("format_name").asText()).contains("mp4");
        if (withAudio) {
            assertThat(audios).hasSize(1);
            assertThat(audios.getFirst().path("codec_name").asText()).isEqualTo("aac");
            assertThat(audios.getFirst().path("channels").asInt()).isEqualTo(1);
            assertThat(audios.getFirst().path("sample_rate").asInt()).isEqualTo(48000);
        } else {
            assertThat(audios).isEmpty();
        }

        // Decode every generated frame/sample: metadata alone cannot establish a playable output.
        runNative(List.of(properties.getFfmpegBinary(), "-nostdin", "-v", "error", "-xerror",
                "-i", output.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-f", "null", "-"));
        assertFaststart(output);
        var image = ImageIO.read(thumbnail.toFile());
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(expectedWidth);
        assertThat(image.getHeight()).isEqualTo(expectedHeight);
        assertThat(Files.size(output)).isBetween(1L, 8L * 1024 * 1024);
        exportVerifiedOutput(withAudio, output, thumbnail, technical);
    }

    @Test
    void realProbeAndNormalizerRejectCorruptInput() throws Exception {
        TranscodeProperties properties = properties();
        Path corrupt = Files.writeString(workspace.resolve("corrupt.mp4"), "synthetic invalid video container");
        Path output = workspace.resolve("rejected/video.mp4");

        assertThatThrownBy(() -> probeService(properties).probeVideo(corrupt))
                .isInstanceOf(IOException.class).hasMessageContaining("ffprobe failed");
        assertThatThrownBy(() -> new FfmpegService(properties).generateProgressive(corrupt, output))
                .isInstanceOf(IOException.class).hasMessageContaining("ffmpeg failed");
        assertThat(output).doesNotExist();
    }

    private TranscodeProperties properties() {
        TranscodeProperties properties = new TranscodeProperties();
        properties.setFfmpegBinary(requiredExecutable("SOUNDCONNECT_TEST_FFMPEG"));
        properties.setFfprobeBinary(requiredExecutable("SOUNDCONNECT_TEST_FFPROBE"));
        properties.setProcessTimeoutSec(30);
        properties.setFfprobeTimeoutSec(10);
        properties.setMaxDurationSeconds(5);
        properties.setMaxEstimatedOutputBytes(64L * 1024 * 1024);
        properties.setMaxTempWorkBytes(128L * 1024 * 1024);
        properties.setMinFreeTempBytes(16L * 1024 * 1024);
        TranscodeVariant variant = new TranscodeVariant();
        variant.setHeight(720);
        variant.setVideoBitrate("2200k");
        variant.setAudioBitrate("128k");
        properties.setLadder(List.of(variant));
        return properties;
    }

    private FfprobeService probeService(TranscodeProperties properties) {
        return new FfprobeService(mapper, properties,
                new VideoTranscodeResourceValidator(properties, new TranscodeDiskSpaceInspector()));
    }

    private static String requiredExecutable(String variable) {
        String value = System.getenv(variable);
        assertThat(value).as("%s must explicitly point to a local executable", variable).isNotBlank();
        Path executable = Path.of(value);
        assertThat(executable.isAbsolute()).as("%s must be absolute", variable).isTrue();
        assertThat(executable).isRegularFile();
        return executable.toString();
    }

    private String runNative(List<String> command) throws Exception {
        Path log = Files.createTempFile(workspace, "native-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        NativeProcessEnvironment.sanitize(builder);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("native verification process finishes in 30s").isTrue();
            assertThat(Files.size(log)).as("native verification log stays bounded").isLessThan(64L * 1024);
            String output = Files.readString(log);
            assertThat(process.exitValue()).as("native verification output: %s", output).isZero();
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static List<JsonNode> streams(JsonNode root, String type) {
        return StreamSupport.stream(root.path("streams").spliterator(), false)
                .filter(stream -> type.equals(stream.path("codec_type").asText())).toList();
    }

    private static void assertFaststart(Path output) throws IOException {
        ByteBuffer boxes = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.BIG_ENDIAN);
        int moovOffset = -1;
        int mdatOffset = -1;
        while (boxes.remaining() >= 8) {
            int offset = boxes.position();
            long length = Integer.toUnsignedLong(boxes.getInt());
            byte[] typeBytes = new byte[4];
            boxes.get(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            int headerSize = 8;
            if (length == 1) {
                assertThat(boxes.remaining()).isGreaterThanOrEqualTo(8);
                length = boxes.getLong();
                headerSize = 16;
            } else if (length == 0) {
                length = boxes.limit() - offset;
            }
            assertThat(length).as("valid top-level MP4 box %s", type)
                    .isBetween((long) headerSize, (long) boxes.limit() - offset);
            if (type.equals("moov")) moovOffset = offset;
            if (type.equals("mdat")) mdatOffset = offset;
            boxes.position(Math.toIntExact(offset + length));
        }
        assertThat(moovOffset).as("faststart moov box exists").isGreaterThanOrEqualTo(0);
        assertThat(mdatOffset).as("MP4 media box follows moov").isGreaterThan(moovOffset);
    }

    private void exportVerifiedOutput(boolean withAudio, Path output, Path thumbnail, JsonNode technical)
            throws IOException {
        String exportDirectory = System.getenv("SOUNDCONNECT_REAL_MEDIA_EXPORT_DIR");
        if (exportDirectory == null || exportDirectory.isBlank()) return;
        Path destination = Path.of(exportDirectory);
        assertThat(destination.isAbsolute()).as("synthetic export directory must be explicit and absolute").isTrue();
        Files.createDirectories(destination);
        String name = withAudio ? "announcement-normalized" : "announcement-silent";
        Files.copy(output, destination.resolve(name + ".mp4"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(thumbnail, destination.resolve(name + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(destination.resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(technical));
    }
}
