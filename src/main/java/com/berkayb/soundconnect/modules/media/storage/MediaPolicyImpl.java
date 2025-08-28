package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.*;

/**
 * Tur + Mim + boyut dogrulamasi yapar.
 * Guvenli object key (S3/R2) ve HLS predix uretir.
 * Dosya adi hijyeni uygular. PII sizmamasi icin key'de orjinal ismi kullanmaz
 */
@Component
@Slf4j
public class MediaPolicyImpl implements MediaPolicy {
	
	@Value("${media.limits.imageMaxBytes:20000000}")
	private long imageMax; // max gorsel boyutu 20 mb
	
	@Value("${media.limits.audioMaxBytes:200000000}")
	private long audioMax; // max ses boyutu 200mb
	
	@Value("${media.limits.videoMaxBytes:4000000000}")
	private long videoMax; // max video boyutu 4gb
	
	
	// ------------ IZINLI MIME'LAR (YML)
	// yml'da liste tanimlasak da spring bunlari virgulle birlestirilmis string olarak verir. biz splitlicez
	
	@Value("${media.allowedMime.image:image/png,image/jpeg,image/webp}")
	private String imageAllowedCsv;
	
	@Value("${media.allowedMime.audio:audio/mpeg,audio/aac,audio/wav,audio/x-wav,audio/ogg}")
	private String audioAllowedCsv;
	
	@Value("${media.allowedMime.video:video/mp4,video/quicktime,video/x-matroska}")
	private String videoAllowedCsv;
	
	
	
	// ----------PATH AYARLARI (YML)--------------
	@Value("${media.paths.root:media}")
	private String rootDir;
	
	@Value("${media.paths.hlsDir:hls}")
	private String hlsDir;
	
	@Value("${media.paths.sourceBasename:source}")
	private String sourceBasename;
	
	// ------------MIME -> uzanti eslemesi (sik kullanilanlar) -------------
	private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
			Map.entry("image/png", "png"),
			Map.entry("image/jpeg","jpg"),
			Map.entry("image/webp", "webp"),
			
			Map.entry("audio/mpeg", "mp3"),
			Map.entry("audio/aac","aac"),
			Map.entry("audio/wav", "wav"),
			Map.entry("audio/x-wav", "wav"),
			Map.entry("audio/ogg", "ogg"),
			
			Map.entry("video/mp4", "mp4"),
			Map.entry("video/quicktime", "mov"),
			Map.entry("video/x-matroska", "mkv")
	);
	
	// calisma set'leri
	private Set<String> imageAllowed;
	private Set<String> audioAllowed;
	private Set<String> videoAllowed;
	
	@PostConstruct
	void init() {
		// CSV Stringlerini set'e cevir
		imageAllowed = csvToSet(imageAllowedCsv);
		audioAllowed = csvToSet(audioAllowedCsv);
		videoAllowed = csvToSet(videoAllowedCsv);
		
		log.info("[media-policy] limits image={} audio={} video={}", imageMax, audioMax, videoMax);
		log.info("[media-policy] allowed image={} audio={} video={}", imageAllowed, audioAllowed, videoAllowed);
		log.info("[media-policy] paths root='{}' hlsDir='{}' source='{}'", rootDir, hlsDir, sourceBasename);
	}
	
	// tum dogrulamalar: tur + mime + boyut
	@Override
	public void validate(MediaKind kind, String mimeType, long sizeBytes) {
		if (kind == null || !StringUtils.hasText(mimeType) || sizeBytes <= 0) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
		}
		
		// izinli kume sec
		Set<String> allowed = switch (kind) {
			case IMAGE -> imageAllowed;
			case AUDIO -> audioAllowed;
			case VIDEO -> videoAllowed;
		};
		
		if (!allowed.contains(mimeType)) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_UNSUPPORTED_MIME);
		}
		
		// boyut siniri
		long max = switch (kind) {
			case IMAGE -> imageMax;
			case AUDIO -> audioMax;
			case VIDEO -> videoMax;
		};
		
		if (sizeBytes > max) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_SIZE_EXCEEDED);
		}
	}
	
	// SOURCE KEY: {root}/{assetId}/{sourceBasename}.{ext}
	// orijinal isim PII sizdirmamak icin kullanilmaz.
	// uzanti orijinal isimden cikarilir yoksa "dat" fallback.
	@Override
	public String buildSourceKey(UUID assetId, String originalFileName) {
		if (assetId == null) throw new SoundConnectException(ErrorType.MEDIA_ASSET_ID_REQUIRED);
		
		String ext = safeExtensionFromFilename(originalFileName).orElse("dat");
		return rootDir + "/" + assetId + "/" + sourceBasename + "." + ext;
	}
	
	// HLS PREFIX: {root}/{assetId}/{hlsDir}
	@Override
	public String buildHlsPrefix(UUID assetId) {
		if (assetId == null) throw new SoundConnectException(ErrorType.MEDIA_ASSET_ID_REQUIRED);
		return rootDir + "/" + assetId + "/" + hlsDir;
	}
	
	
	// -----------yardimci metodlar----------
	
	// bazi yaygin alias duzeltmeleri
	private static String smartNormalizeExt(String ext) {
		return switch (ext) {
			case "jpeg" -> "jpg";
			case "mpeg" -> "mp3";
			case "quicktime" -> "mov";
			case "x-wav" -> "wav";
			case "x-matroska" -> "mkv";
			default -> ext.replaceAll("[^a-z0-9]+", ""); // sadece guvenli karakterler
		};
	}
	
	// orijinal dosya adindan guvenli uzanti cikar
	private static Optional<String> safeExtensionFromFilename(String originalFileName) {
		if (!StringUtils.hasText(originalFileName)) return Optional.empty();
		
		// Unicode normalize: Türkçe karakterleri sadeleştir (ç→c, ğ→g)
		String normalized = Normalizer.normalize(originalFileName, Normalizer.Form.NFD)
		                              .replaceAll("\\p{M}", "");
		
		int dot = normalized.lastIndexOf('.');
		if (dot < 0 || dot == normalized.length() - 1) return Optional.empty();
		
		String raw = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
		return Optional.of(smartNormalizeExt(raw));
	}
	
	private static Set<String> csvToSet(String csv) {
		if (!StringUtils.hasText(csv)) return Set.of();
		String[] parts = csv.split(",");
		Set<String> set = new HashSet<>();
		for (String p : parts) {
			String v = p.trim();
			if (!v.isEmpty()) set.add(v);
		}
		return Collections.unmodifiableSet(set);
	}
	
}