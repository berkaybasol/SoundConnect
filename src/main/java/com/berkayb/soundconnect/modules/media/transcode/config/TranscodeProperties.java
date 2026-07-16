package com.berkayb.soundconnect.modules.media.transcode.config;

import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

//----------------------------------Terimlerde takilirsan TranscodeProperties.md bak------------------------------------

/**
 * FFmpeg/HLS icin tum ayarlarin yapildigi config sinifi
 * Worker, ladder, (cozunurluk/bitrate) segment suresi, CRF/preset vs. hepsi buradan okunur
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "transcode") // application.yml'daki transcode alanina baglaniyoruz
@Slf4j
public class TranscodeProperties {
	
	// HLS varyantlarinin listesi. buyukten kucuge (1080, 720, 480, 360 vs.) yml.dan gelir
	@NotEmpty
	@Valid
	private List<TranscodeVariant> ladder = new ArrayList<>();
	
	// HLS segment suresi saniye cinsinden 4 olarak default deger verdik. Detay: -> TranscodeProperties.md
	@Min(1) // 1 saniyeden kucuk olamaz
	private int segmentDurationSec = 4;
	
	// kac saniyede bir ana kare olsun? Detay: -> TranscodeProperties.md
	@Min(1)
	private int gop = 48;
	
	// aciklamasi zor. -> TranscodeProperties.md
	private String preset = "veryfast";
	
	// aciklamasi zor. -> TranscodeProperties.md
	@Min(0)
	private int crf = 21;
	
	// videonun kacinci saniyesinden thumbnail gorseli cikartayim? @TODO burasi gelistirilcek ileride
	@Min(0)
	private int thumbnailSecond = 1;

	/** Executable name or absolute path used for native and container runtimes. */
	@NotEmpty
	private String ffmpegBinary = "ffmpeg";

	@NotEmpty
	private String ffprobeBinary = "ffprobe";

	/** Hard upper bound for each ffmpeg child process. */
	@Min(30)
	@Max(21600)
	private int processTimeoutSec = 3600;

	/** Hard upper bound for downloading one verified video source from storage. */
	@Min(60)
	@Max(21_600)
	private int sourceDownloadTimeoutSec = 7_200;

	/** Hard aggregate budget for publishing the complete HLS object tree. */
	@Min(60)
	@Max(7200)
	private int hlsUploadTimeoutSec = 3600;

	/** Hard upper bound for each ffprobe child process. */
	@Min(5)
	@Max(120)
	private int ffprobeTimeoutSec = 30;

	/** Profile/gallery video admission limits, enforced before FFmpeg starts. */
	@Min(1)
	@Max(21600)
	private int maxDurationSeconds = 900;

	@Min(480)
	@Max(8192)
	private int maxVideoDimension = 3840;

	@Min(230400)
	private long maxVideoPixels = 8_294_400L;

	@DecimalMin("1.0")
	@DecimalMax("240.0")
	private double maxFrameRate = 60.0;

	@Min(16_777_216L)
	private long maxEstimatedOutputBytes = 2_147_483_648L;

	@Min(67_108_864L)
	private long maxTempWorkBytes = 6_442_450_944L;

	/** Byte-weighted admission shared by all transcode jobs in this process. */
	@Min(67_108_864L)
	private long globalTempBudgetBytes = 6_442_450_944L;

	/** Declared hard capacity of the worker's dedicated temporary volume. */
	@Min(67_108_864L)
	private long tempVolumeBytes = 6_442_450_944L;

	@Min(1)
	@Max(3600)
	private int tempBudgetAcquireTimeoutSeconds = 300;

	@Min(0)
	private long minFreeTempBytes = 536_870_912L;

	@DecimalMin("1.0")
	@DecimalMax("3.0")
	private double outputEstimateMultiplier = 1.20;

	@Min(0)
	private long outputFixedOverheadBytes = 16_777_216L;
	
	// HLS protokolune gore olusturulan video segmentlerinin hangi formatta (ST veya FMP4) dosyalanacagini belirtir.
	// TS: Eski MPEG-TS formati (yaygin ama eski)
	// FMP4: Modern, dusuk gecikmeli ve tum yeni tarayicilarla uyumlu.
	private Container container = Container.FMP4;

	@AssertTrue(message = "transcode temporary budget must cover the maximum estimated output")
	public boolean isResourceBudgetConsistent() {
		return maxTempWorkBytes >= maxEstimatedOutputBytes
				&& globalTempBudgetBytes >= maxTempWorkBytes
				&& tempVolumeBytes >= globalTempBudgetBytes;
	}
	
	// Config yuklendiginde calisir. siralama & validasyon ve loglama yapar
	@PostConstruct
	void afterBind() {
		this.ladder = new ArrayList<>(this.ladder != null ? this.ladder : List.of());
		
		// Ladder (variant listesi) yukseklik sirasina gore buytukten kucuge sirala
		this.ladder.sort(Comparator.comparingInt(TranscodeVariant::getHeight).reversed());
		
		// en az 2 variant olmasi onerilir. adaptive bitrate icin gerekli
		if (ladder.size() < 2) {
			log.warn("[transcode] Ladder 2’den az. ABR faydası düşer: size={}", ladder.size());
		}
		log.info("[transcode] container={} segment={}s gop={} preset={} crf={} thumbAt={}s ffmpeg={} processTimeout={}s sourceDownloadTimeout={}s maxDuration={}s maxDimension={} maxPixels={} maxFps={} maxOutputBytes={} maxTempBytes={} globalTempBytes={} tempVolumeBytes={} ladder={}",
		         container, segmentDurationSec, gop, preset, crf, thumbnailSecond,
				ffmpegBinary, processTimeoutSec, sourceDownloadTimeoutSec,
				maxDurationSeconds, maxVideoDimension,
				maxVideoPixels, maxFrameRate, maxEstimatedOutputBytes, maxTempWorkBytes,
				globalTempBudgetBytes, tempVolumeBytes,
				ladderToString());
	}
	
	private String ladderToString() {
		StringBuilder sb = new StringBuilder();
		for(TranscodeVariant variant : ladder) {
			if (sb.length() > 0) sb.append(" | ");
			sb.append(variant.getHeight()).append("p@").append(variant.getVideoBitrate())
					.append("+").append(variant.getAudioBitrate());
		}
		return sb.toString();
	}
	
	
}
