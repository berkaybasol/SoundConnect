package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ffprobe ile video tek cagrida metadata cikarma
 * makinede'ffprobe' kurulu ve PATH'te olmali (TODO Prodda Docker'a eklemeyi unutma
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class FfprobeService {
	private final ObjectMapper mapper;
	
	// ffprobe binary adi/yolu (Pathime boyle tanimladim.)
	@Value("${transcode.ffprobeBinary:ffprobe}")
	private String ffprobeBinary;
	
	// process timeout
	@Value("${transcode.ffprobeTimeoutSec:30}")
		private int timeoutSec;
	
	/**
	 * verilen dosya icin metadata doner
	 * Keys: "durationSeconds", "width", "height"
	 * bazi alanlar bulunamazsa null olabilir Map yapiyoruz o yuzden
	 */
	public Map<String, Integer> probe (Path input) throws IOException, InterruptedException {
		if (input == null) throw new SoundConnectException(ErrorType.MEDIA_INPUT_PATH_REQUIRED);
		
		// ffprobe komutunu JSON ile calistircaz
		List<String> cmd = List.of(
				ffprobeBinary,
				"-v", "error",
				"-print_format", "json",
				"-show_format",
				"-show_streams",
				input.toAbsolutePath().toString()
		);
		
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(false); // stderr ayri
		Process p = pb.start();
		
		StringBuilder outBuf = new StringBuilder();
		StringBuilder errBuf = new StringBuilder();
		
		// stdout/stderr ayiriyoruz
		Thread tOut = new Thread(() -> readStream(p, true, outBuf));
		Thread tErr = new Thread(() -> readStream(p, false, errBuf));
		tOut.start(); tErr.start();
		
		// timeout'lu bekleme
		boolean finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
		if (!finished) {
			p.destroyForcibly();
			throw new IOException("ffprobe timed out after " + timeoutSec + " seconds");
		}
		tOut.join();
		tErr.join();
		
		int exit = p.exitValue();
		if (exit != 0) {
			log.error("[ffprobe] exit={} stderrTail={}", exit, safeTail(errBuf, 2000));
			throw new IOException("ffprobe failed with exit=" + exit);
		}
		// JSON parse
		JsonNode root = mapper.readTree(outBuf.toString());
		
		Integer width = null;
		Integer height = null;
		Integer durationSeconds = null;
		
		// duration: once format.duration yoksa ilk video stream.duration
		String durStr = null;
		JsonNode format = root.get("format");
		if (format != null && format.has("duration")) {
			durStr = format.get("duration").asText(null);
		}
		if (durStr == null) {
			JsonNode streams = root.get("streams");
			if (streams != null && streams.isArray()) {
				for (JsonNode s : streams) {
					if ("video".equalsIgnoreCase(s.path("codec_type").asText())) {
						if (s.has("duration")) {
							durStr = s.get("duration").asText(null);
						}
						break;
					}
				}
			}
		}
		if (durStr != null) durationSeconds = parseDurationSeconds(durStr);
		
		// 2) width/height: ilk video stream
		JsonNode streams = root.get("streams");
		if (streams != null && streams.isArray()) {
			for (JsonNode s : streams) {
				if ("video".equalsIgnoreCase(s.path("codec_type").asText())) {
					if (s.has("width"))  width  = asPositiveIntOrNull(s.get("width"));
					if (s.has("height")) height = asPositiveIntOrNull(s.get("height"));
					break;
				}
			}
		}
		
		Map<String, Integer> meta = new HashMap<>();
		meta.put("durationSeconds", durationSeconds);
		meta.put("width", width);
		meta.put("height", height);
		
		log.debug("[ffprobe] meta file={} -> {}", input, meta);
		return meta;
	}
	
	
	// yardimci methodlar
	
	private static Integer asPositiveIntOrNull(JsonNode n) {
		if (n == null || !n.canConvertToInt()) return null;
		int v = n.asInt();
		return v > 0 ? v : null;
	}
	
	/** "123.456" saniyeyi 123 saniyeye yuvarlar (floor). */
	private static Integer parseDurationSeconds(String s) {
		try {
			double d = Double.parseDouble(s.trim());
			if (d < 0) return null;
			return (int) Math.floor(d);
		} catch (Exception ignored) {
			return null;
		}
	}
	
	private static String safeTail(StringBuilder sb, int max) {
		int len = sb.length();
		if (len <= max) return sb.toString();
		return sb.substring(len - max);
	}
	
	
	private static void readStream(Process p, boolean stdout, StringBuilder into) {
		try (var br = new BufferedReader(new InputStreamReader(
				stdout ? p.getInputStream() : p.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (into.length() < 32_000) into.append(line).append('\n');
			}
		} catch (IOException ignored) {}
	}
}