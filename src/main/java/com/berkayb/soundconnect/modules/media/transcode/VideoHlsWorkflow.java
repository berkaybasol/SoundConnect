package com.berkayb.soundconnect.modules.media.transcode;


import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;


/**
 * Orchestrator:
 * -Storage'dan SOURCE indirir
 * ffprobe: meta cikar
 * FFmpeg ile hls ladder ve thumbnail uretir
 * HLS ciktisini S3'e yukler (dogru Content-Type/Cache-Control ile)
 * MediaAsset durumunu READY/FAILED yapar
 * en sonda temp dosyalari temizler
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoHlsWorkflow {
	private final StorageClient storage;
	private final FfmpegService ffmpeg;
	private final HlsUploader uploader;
	private final MediaAssetStatusUpdater statusUpdater;
	private FfprobeService ffprobe;
	
	/*
	Tek giris noktasi: videHlsRequest DTO'su ile akisi yurut
	Basariliysa READY, hata varsa FAILED + throw.
	 */
	public void process(VideoHlsRequest req) throws Exception {
		// param dogrulama (erken fail" kuyrugu cop mesajla mesgul etmemek icin
		if (req == null) throw new SoundConnectException(ErrorType.INVALID_HLS_REQUEST);
		if (req.assetId() == null || req.assetId().isBlank())
			throw new SoundConnectException(ErrorType.ASSET_ID_REQUIRED);
		if (req.sourceKey() == null || req.sourceKey().isBlank())
			throw new SoundConnectException(ErrorType.SOURCE_KEY_REQUIRED);
		if (req.hlsPrefix() == null || req.hlsPrefix().isBlank())
			throw new SoundConnectException(ErrorType.HLS_PREFIX_REQUIRED);
		
		UUID assetId = UUID.fromString(req.assetId());
		
		// (opsiyonel) zaten PROCESSING ise idempotent, degilse PROCESSING'e al
		statusUpdater.markProcessing(assetId);
		
		Path workdir = null;
		try {
			// calisma alani olustur (OS tmp altinda guvenli bir klasor)
			workdir = Files.createTempDirectory("sc-hls-" + assetId + "-");
			Path srcFile = workdir.resolve("source" + extFromKey(req.sourceKey())); // uzatntiyi key'den cikar
			Path outDIr = workdir.resolve("out"); // HLS ciktilari buraya.. (variant klasorleri ve master.m3u8)
			Path thumb = workdir.resolve("thumbnail.jpg");
			
			log.info("[workflow] start assetId={} sourceKey={} out={}", assetId, req.sourceKey(), outDIr);
			
			// Source'u indir
			storage.downloadToFile(req.sourceKey(), srcFile);
			
			// 2) ffprobe metadata (kaynak dosyadan ölçmek daha güvenilir)
			Integer duration = null, width = null, height = null;
			try {
				Map<String, Integer> meta = ffprobe.probe(srcFile);
				duration = meta.get("durationSeconds");
				width    = meta.get("width");
				height   = meta.get("height");
				log.debug("[workflow] ffprobe meta assetId={} duration={}s {}x{}", assetId, duration, width, height);
			} catch (Exception probeErr) {
				// Meta zorunlu değil; sadece logla ve devam et
				log.warn("[workflow] ffprobe failed assetId={} err={}", assetId, probeErr.getMessage());
			}
			
			// FFmpeg: HLD ladder + thumbnail uret
			ffmpeg.generateHlsLadder(srcFile, outDIr);
			ffmpeg.generateThumbnail(srcFile, thumb);
			
			// HLS ciktisini s3'e yukle playback (master.m3u8) ve thumbnail CDN URL'lerini al
			HlsUploadResult upload = uploader.uploadHlsTree(outDIr, req.hlsPrefix(), thumb);
			
			// DB'de READY (HLS) yap - metadata
			statusUpdater.markReadyHls(assetId,
			                           upload.playbackUrl(),
			                           upload.thumbnailUrl(),
			                           duration, // durationSeconds
			                           width, // width
			                           height // height
			);
			log.info("[workflow] OK assetId={} objects={} playback={}", assetId, upload.objectCount(), upload.playbackUrl());
		}
		catch (Exception e) {
			// hata: FAILED'a cek ve direkt firlat (listener NACK/DLQ karar versin)
			try {
				statusUpdater.markFailed(assetId);
			}
			catch (Exception exception) {
				log.error("[workflow] markFailed error assetId={} err={}", assetId, exception.getMessage(), exception);
			}
			log.error("[workflow] FAILED assetId={} err={}", assetId, e.getMessage(), e);
			throw e;
		}
		finally {
			if (workdir != null) {
				try {
					deleteRecursive(workdir);
				}
				catch (Exception ex) {
					log.warn("[workflow] cleanup failed dir={} err={}", workdir, ex.getMessage());
				}
			}
		}
	}
	
	// yardimci methodlar
	// sourceKey'den guvenli bir uzanti cikarir yoksa .mp4 varsayar.
	private static String extFromKey(String key) {
		int dot = key.lastIndexOf('.');
		if (dot < 0 || dot == key.length() - 1) return ".mp4";
		String raw = key.substring(dot).toLowerCase(); // ".mp4"
		// çok sert hijyen gerekmez; nokta ile başlayan kısa bir uzantı yeterli
		if (raw.length() > 8) return ".mp4"; // garip şeyler olmasın
		return raw;
	}
	
	// klasoru icindekilerle siler.
	private static void deleteRecursive(Path root) throws IOException {
		if (!Files.exists(root)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}
			
			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}