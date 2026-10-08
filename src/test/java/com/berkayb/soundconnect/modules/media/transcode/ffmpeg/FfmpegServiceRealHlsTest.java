package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real native output contract. Requires local ffmpeg and ffprobe; no Spring/DB/network. */
class FfmpegServiceRealHlsTest {
    @TempDir Path work;
    private final ObjectMapper json = new ObjectMapper();
    private final String ffmpeg = System.getenv().getOrDefault("FFMPEG_BINARY", "ffmpeg");
    private final String ffprobe = System.getenv().getOrDefault("FFPROBE_BINARY", "ffprobe");

    @org.junit.jupiter.api.AfterEach
    void retainNativeEvidence(org.junit.jupiter.api.TestInfo info) throws Exception {
        String evidence = System.getenv("HLS_EVIDENCE_DIR");
        if (evidence == null) return;
        Path target = Path.of(evidence).resolve(info.getDisplayName().replaceAll("[^a-zA-Z0-9._-]", "_"));
        try (var paths = Files.walk(work)) {
            for (Path path : paths.toList()) {
                Path dest = target.resolve(work.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(dest);
                else Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"FMP4,false", "FMP4,true", "TS,false", "TS,true"})
    void realVariantsKeepSourceTracksWithoutInventingCodecMetadata(Container container, boolean audio) throws Exception {
        Path source = fixture(audio);
        assertTracks(probe(source), audio);
        TranscodeProperties props = properties(container);
        Path out = work.resolve("out");
        new FfmpegService(props).generateHlsLadder(source, out);
        String master = Files.readString(out.resolve("master.m3u8"));
        for (int height : List.of(180, 360)) {
            Path dir = out.resolve(height + "p");
            String playlist = Files.readString(dir.resolve("index.m3u8"));
            assertThat(master).contains(height + "p/index.m3u8");
            assertThat(playlist).contains("#EXT-X-ENDLIST");
            assertTracks(probe(dir.resolve("index.m3u8")), audio);
            Path segment;
            try (var paths = Files.list(dir)) {
                segment = paths.filter(p -> p.toString().endsWith(container == Container.FMP4 ? ".m4s" : ".ts")).sorted().findFirst().orElseThrow();
            }
            assertThat(Files.size(segment)).isPositive();
            Path sample = segment;
            if (container == Container.FMP4) {
                Path init = dir.resolve("init.mp4");
                assertTracks(probe(init), audio);
                sample = work.resolve("sample-" + height + ".mp4");
                try (var stream = Files.newOutputStream(sample)) {
                    Files.copy(init, stream); Files.copy(segment, stream);
                }
            }
            assertTracks(probe(sample), audio);
            // Decode actual frames (and audio samples when present), not just metadata.
            command(List.of(ffmpeg, "-v", "error", "-i", sample.toString(), "-f", "null", "-"));
        }
        // CODECS is optional. It must not guess AAC presence or a fixed H.264 profile/level.
        assertThat(master).doesNotContain("CODECS=");
    }

    @Test
    void failedNativeEncodeDoesNotPublishMaster() throws Exception {
        Path bad = work.resolve("bad.mp4"); Files.writeString(bad, "not media");
        Path out = work.resolve("out");
        assertThatThrownBy(() -> new FfmpegService(properties(Container.FMP4)).generateHlsLadder(bad, out))
                .isInstanceOf(java.io.IOException.class);
        assertThat(out.resolve("master.m3u8")).doesNotExist();
    }

    @Test
    void realOutputBudgetFailureDoesNotPublishMaster() throws Exception {
        Path source = fixture(false); var props = properties(Container.FMP4);
        props.setMaxEstimatedOutputBytes(1);
        Path out = work.resolve("out");
        assertThatThrownBy(() -> new FfmpegService(props).generateHlsLadder(source, out))
                .isInstanceOf(com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException.class);
        assertThat(out.resolve("master.m3u8")).doesNotExist();
    }

    private TranscodeProperties properties(Container container) {
        var p = new TranscodeProperties(); p.setFfmpegBinary(ffmpeg); p.setContainer(container);
        var low = new TranscodeVariant(); low.setHeight(180); low.setVideoBitrate("300k"); low.setAudioBitrate("64k");
        var high = new TranscodeVariant(); high.setHeight(360); high.setVideoBitrate("600k"); high.setAudioBitrate("96k");
        p.setLadder(List.of(low, high)); p.setProcessTimeoutSec(30); return p;
    }
    private Path fixture(boolean audio) throws Exception {
        Path p = work.resolve("source.mp4");
        var args = new ArrayList<>(List.of(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24"));
        if (audio) args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100"));
        args.addAll(List.of("-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p"));
        args.addAll(audio ? List.of("-c:a", "aac", "-shortest") : List.of("-an"));
        args.add(p.toString()); command(args); return p;
    }
    private JsonNode probe(Path p) throws Exception {
        return json.readTree(command(List.of(ffprobe, "-v", "error", "-show_streams", "-of", "json", p.toString()))).get("streams");
    }
    private void assertTracks(JsonNode streams, boolean audio) {
        int videos = 0, audios = 0;
        for (JsonNode stream : streams) {
            if (stream.path("codec_type").asText().equals("video")) {
                videos++; assertThat(stream.path("codec_name").asText()).isEqualTo("h264");
            } else if (stream.path("codec_type").asText().equals("audio")) {
                audios++; assertThat(stream.path("codec_name").asText()).isEqualTo("aac");
            }
        }
        assertThat(videos).isEqualTo(1); assertThat(audios).isEqualTo(audio ? 1 : 0);
    }
    private String command(List<String> args) throws Exception {
        Path log = Files.createTempFile(work, "native-", ".log");
        Process p = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(40, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("Native test timeout"); }
        String result = Files.readString(log);
        assertThat(p.exitValue()).as("%s: %s", args, result).isZero(); return result;
    }
}
