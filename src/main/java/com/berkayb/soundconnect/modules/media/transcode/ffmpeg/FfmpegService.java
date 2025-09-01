package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeVariant;
import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

//------------------------------------Takildigin Yerde MediaModule.md Bak-----------------------------------------------

/**
 * FFmpeg çağrılarını üstlenen servis:
 *  - HLS ladder üretir (her varyant için playlist + segmentler)
 *  - master.m3u8 dosyasını yazar
 *  - thumbnail çıkarır
 *
 * Gereksinim: Makinede `ffmpeg` PATH'te olmalı (prod’da Dockerfile’a ekle).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FfmpegService {
	
	private final TranscodeProperties props;
	
	/** Girdi videodan HLS ladder üretir ve outDir altına yazar. */
	public void generateHlsLadder(Path input, Path outDir) throws IOException, InterruptedException {
		Files.createDirectories(outDir);
		
		// Her kalite için alt klasör (1080p/, 720p/ ...)
		for (TranscodeVariant v : props.getLadder()) {
			Path variantDir = outDir.resolve(v.getHeight() + "p");
			Files.createDirectories(variantDir);
			runFfmpegForVariant(input, variantDir, v);
		}
		
		// Varyantların üstüne master playlist
		writeMasterPlaylist(outDir, props.getLadder(), props.getContainer());
		log.info("[ffmpeg] HLS ladder hazır: dir={}", outDir);
	}
	
	/** Videodan tek kare alıp thumbnail üretir. */
	public void generateThumbnail(Path input, Path outImage) throws IOException, InterruptedException {
		Files.createDirectories(outImage.getParent());
		
		List<String> cmd = new ArrayList<>();
		cmd.add("ffmpeg");
		cmd.add("-y");
		cmd.add("-ss"); cmd.add(String.valueOf(props.getThumbnailSecond()));
		cmd.add("-i");  cmd.add(input.toAbsolutePath().toString());
		cmd.add("-frames:v"); cmd.add("1");          // ← 1 kare
		cmd.add("-q:v");      cmd.add("2");          // kalite: 1 (en iyi)–31
		cmd.add(outImage.toAbsolutePath().toString());
		
		runCommand(cmd, outImage.getParent());
		log.info("[ffmpeg] thumbnail oluşturuldu: {}", outImage);
	}
	
	// ---- İç detaylar ----
	
	/** Tek bir varyant (ör. 1080p) için ffmpeg çalıştırır; index.m3u8 + segmentleri yazar. */
	private void runFfmpegForVariant(Path input, Path variantDir, TranscodeVariant v)
			throws IOException, InterruptedException {
		
		// En-boy oranını koru; genişlik çift kalsın diye -2
		String scaleFilter = "scale=-2:" + v.getHeight();        // ← ':' eklendi
		String vb = v.getVideoBitrate();
		String ab = v.getAudioBitrate();
		
		Path playlist = variantDir.resolve("index.m3u8");
		Path segmentPattern;
		
		List<String> cmd = new ArrayList<>();
		cmd.add("ffmpeg");
		cmd.add("-y");
		cmd.add("-i");  cmd.add(input.toAbsolutePath().toString());
		cmd.add("-vf"); cmd.add(scaleFilter);                      // ← scale filter doğru bağlandı
		cmd.add("-c:v"); cmd.add("libx264");
		cmd.add("-preset"); cmd.add(props.getPreset());
		cmd.add("-crf");    cmd.add(String.valueOf(props.getCrf()));
		cmd.add("-g");      cmd.add(String.valueOf(props.getGop()));
		cmd.add("-keyint_min");  cmd.add(String.valueOf(props.getGop()));
		cmd.add("-sc_threshold"); cmd.add("0");                   // ← alt çizgi
		cmd.add("-c:a");   cmd.add("aac");
		cmd.add("-b:v");   cmd.add(vb);
		cmd.add("-b:a");   cmd.add(ab);
		
		// HLS ayarları (VOD)
		cmd.add("-f"); cmd.add("hls");
		cmd.add("-hls_time");          cmd.add(String.valueOf(props.getSegmentDurationSec()));
		cmd.add("-hls_list_size");     cmd.add("0");
		cmd.add("-hls_playlist_type"); cmd.add("vod");
		cmd.add("-hls_flags");         cmd.add("independent_segments");
		
		if (props.getContainer() == Container.FMP4) {
			cmd.add("-hls_segment_type");        cmd.add("fmp4");
			cmd.add("-hls_fmp4_init_filename");  cmd.add("init.mp4");
			segmentPattern = variantDir.resolve("seg_%05d.m4s");
		} else {
			segmentPattern = variantDir.resolve("seg_%05d.ts");
		}
		
		cmd.add("-hls_segment_filename"); cmd.add(segmentPattern.toAbsolutePath().toString());
		cmd.add(playlist.toAbsolutePath().toString());            // çıktı playlist
		
		runCommand(cmd, variantDir);
		log.debug("[ffmpeg] variant OK: {} (v={}, a={})", v.getHeight(), vb, ab);
	}
	
	/** master.m3u8: varyant index’lerine işaret eder. */
	private void writeMasterPlaylist(Path outDir, List<TranscodeVariant> ladder, Container container)
			throws IOException {
		
		StringBuilder sb = new StringBuilder();
		sb.append("#EXTM3U\n");
		sb.append("#EXT-X-VERSION:7\n");
		
		for (TranscodeVariant v : ladder) {
			long bw = kbpsToBps(v.getVideoBitrate()) + kbpsToBps(v.getAudioBitrate()); // ← audio düzeltildi
			String path = v.getHeight() + "p/index.m3u8";
			int height = v.getHeight();
			int width  = (int) (Math.round(height * 16.0 / 9.0) / 2) * 2;
			
			sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bw)
			  .append(",RESOLUTION=").append(width).append("x").append(height)
			  .append(",CODECS=\"avc1.42E01E,mp4a.40.2\"")
			  .append("\n")
			  .append(path)
			  .append("\n");
		}
		
		Path master = outDir.resolve("master.m3u8");              // ← master.m3u8 (tüm akış bunu bekliyor)
		Files.writeString(master, sb.toString(), StandardCharsets.UTF_8,
		                  StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		log.info("[ffmpeg] master.m3u8 yazıldı: {}", master);
	}
	
	/** "6000k" → 6_000_000 bps (master için kabaca yeterli) */
	private long kbpsToBps(String s) {
		if (s == null) return 0;
		String trimmed = s.trim().toLowerCase();
		if (trimmed.endsWith("k")) {
			String num = trimmed.substring(0, trimmed.length() - 1);
			try { return Math.round(Double.parseDouble(num) * 1000); }
			catch (NumberFormatException ignored) { return 0; }
		}
		try { return Long.parseLong(trimmed); }
		catch (NumberFormatException ignored) { return 0; }
	}
	
	/** Process çalıştırıp exit-code kontrol eder; stdout/stderr’i loglar. */
	private void runCommand(List<String> cmd, Path workDir) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		if (workDir != null) pb.directory(workDir.toFile());
		pb.redirectErrorStream(false);
		Process p = pb.start();
		
		StringBuilder outBuf = new StringBuilder();
		StringBuilder errBuf = new StringBuilder();
		
		Thread tOut = new Thread(() -> readStream(p.getInputStream(), outBuf));
		Thread tErr = new Thread(() -> readStream(p.getErrorStream(), errBuf));
		tOut.start(); tErr.start();
		
		int exit = p.waitFor();
		tOut.join(); tErr.join();
		
		if (exit != 0) {
			log.error("[ffmpeg] FAILED exit={} cmd={} stderr={}", exit, String.join(" ", cmd), safeTail(errBuf, 4000));
			throw new IOException("ffmpeg failed with exit " + exit);
		} else {
			log.debug("[ffmpeg] OK cmd={} stderrTail={}", String.join(" ", cmd), safeTail(errBuf, 2000));
		}
	}
	
	private void readStream(java.io.InputStream is, StringBuilder into) {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (into.length() < 32_000) {
					into.append(line).append('\n');
				}
			}
		} catch (IOException ignored) { }
	}
	
	private String safeTail(StringBuilder sb, int max) {
		int len = sb.length();
		if (len <= max) return sb.toString();
		return sb.substring(len - max);
	}
}