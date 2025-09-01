package com.berkayb.soundconnect.modules.media.transcode.upload;

import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

//------------------------------------Takildigin Yerde MediaModule.md Bak-----------------------------------------------

@Service
@RequiredArgsConstructor
@Slf4j
public class HlsUploader {
	
	private final StorageClient storage;
	
	/**
	 * HLS çıktı ağacını (master.m3u8 + {height}p/index.m3u8 + segmentler) S3'e yükler.
	 * - Object key kökü: hlsPrefix (örn: "media/{assetId}/hls")
	 * - İçerik türünü (Content-Type) ve Cache-Control'u uzantıya göre ayarlar.
	 * - Sonuçta playback URL (master.m3u8) ve varsa thumbnail URL döner.
	 *
	 * Akış:
	 *   uploadHlsTree(outDir, hlsPrefix, thumbnailPath)
	 *     -> outDir/master.m3u8  →  hlsPrefix/master.m3u8
	 *     -> outDir/720p/...     →  hlsPrefix/720p/...
	 *     -> thumbnail.jpg       →  hlsPrefix/thumbnail.jpg
	 */
	public HlsUploadResult uploadHlsTree(Path hlsOutDir, String hlsPrefix, Path thumbnail) throws IOException {
		if (hlsOutDir == null || !Files.isDirectory(hlsOutDir)) {
			throw new IOException("HLS output directory missing: " + hlsOutDir);
		}
		int count = 0;
		
		try (var walk = Files.walk(hlsOutDir)) {
			for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
				String rel = unixify(hlsOutDir.relativize(file).toString());
				String key = joinKey(hlsPrefix, rel);
				var ct = guessContentType(file);
				storage.putFile(file, key, ct.contentType, ct.cacheControl);
				count++;
			}
		}
		
		String thumbUrl = null;
		// Thumbnail aynı ağacın içindeyse zaten yüklendi; ayrı yollamayalım.
		boolean thumbInsideTree = thumbnail != null
				&& thumbnail.toAbsolutePath().normalize().startsWith(hlsOutDir.toAbsolutePath().normalize());
		
		if (thumbnail != null && Files.exists(thumbnail) && Files.isRegularFile(thumbnail) && !thumbInsideTree) {
			String key = joinKey(hlsPrefix, thumbnail.getFileName().toString());
			var ct = guessContentType(thumbnail);
			storage.putFile(thumbnail, key, ct.contentType, ct.cacheControl);
			thumbUrl = storage.publicUrl(key);
			count++;
		} else if (thumbInsideTree) {
			// Ağacın içindeyse URL’ini yine de dönelim
			String key = joinKey(hlsPrefix, thumbnail.getFileName().toString());
			thumbUrl = storage.publicUrl(key);
		}
		
		String playbackUrl = storage.publicUrl(joinKey(hlsPrefix, "master.m3u8"));
		log.info("[hls-uploader] uploaded {} objects under prefix={}", count, hlsPrefix);
		return new HlsUploadResult(playbackUrl, thumbUrl, count);
	}
	
	// Path'leri her zaman unix formatına (/) çeviriyoruz (Windows vs fark etmesin diye)
	private static String unixify(String p) {
		return p.replace('\\', '/');
	}
	
	private static String joinKey(String prefix, String rel) {
		if (prefix.endsWith("/")) return prefix + rel;
		return prefix + "/" + rel;
	}
	
	// İçerik tipini ve cache kontrolünü tuttuğumuz küçük bir model
	private static class Content {
		final String contentType;
		final String cacheControl;
		Content(String contentType, String cacheControl) {
			this.contentType = contentType;
			this.cacheControl = cacheControl;
		}
	}
	
	// Content-Type ve Cache-Control tahmini
	private static Content guessContentType(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".m3u8")) {
			return new Content("application/vnd.apple.mpegurl", "public, max-age=30, s-maxage=60");
		} else if (name.endsWith(".m4s")) {
			return new Content("video/iso.segment", "public, max-age=31536000, immutable");
		} else if (name.endsWith(".ts")) {
			return new Content("video/mp2t", "public, max-age=31536000, immutable");
		} else if (name.equals("init.mp4") || name.endsWith(".mp4")) {
			return new Content("video/mp4", "public, max-age=31536000, immutable");
		} else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
			return new Content("image/jpeg", "public, max-age=2592000");
		} else if (name.endsWith(".png")) {
			return new Content("image/png", "public, max-age=2592000");
		} else if (name.endsWith(".webp")) {
			return new Content("image/webp", "public, max-age=2592000");
		}
		return new Content("application/octet-stream", "public, max-age=31536000, immutable");
	}
}