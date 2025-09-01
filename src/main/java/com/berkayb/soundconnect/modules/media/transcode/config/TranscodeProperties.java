package com.berkayb.soundconnect.modules.media.transcode.config;

import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
	
	// HLS protokolune gore olusturulan video segmentlerinin hangi formatta (ST veya FMP4) dosyalanacagini belirtir.
	// TS: Eski MPEG-TS formati (yaygin ama eski)
	// FMP4: Modern, dusuk gecikmeli ve tum yeni tarayicilarla uyumlu.
	private Container container = Container.FMP4;
	
	// Config yuklendiginde calisir. siralama & validasyon ve loglama yapar
	@PostConstruct
	void afterBind() {
		// Ladder (variant listesi) yukseklik sirasina gore buytukten kucuge sirala
		ladder.sort(Comparator.comparingInt(TranscodeVariant::getHeight).reversed());
		
		// en az 2 variant olmasi onerilir. adaptive bitrate icin gerekli
		if (ladder.size() < 2 ) {
			log.warn("[transcode] Ladder 2’den az. ABR (adaptif) faydası düşer: size={}", ladder.size());
		}
		log.info("[transcode] container={} segment={}s gop={} preset={} crf={} thumbAt={}s ladder={}",
		         container, segmentDurationSec, gop, preset, crf, thumbnailSecond, ladderToString());
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