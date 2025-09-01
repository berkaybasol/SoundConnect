package com.berkayb.soundconnect.modules.media.transcode.config;

// ---------- BU SIINIFLA ILGILI AI SORULARIMI BIRAZDAN SORCAM -------------------------------

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HLS ladder/daki tek bir varyanti temsil eder.
 * height: hedef cozunurlluk yuksekligi 1080, 720 vs.
 * videoBitrate: "6000k" gibi string (ffmpeg'e dogrudan gecilecek)
 * audioBitrate: "192k" gibi string
 *
 * Not: ConfigurationProperties binding icin no-args ctor sart
 *
 *
 *
 */
@Data
@NoArgsConstructor
public class TranscodeVariant {
	
	@Min(144)
	private int height;
	
	@NotBlank
	private String videoBitrate; // orn "6000k" sonraki adimda parse/validate edebiliriz
	
	@NotBlank
	private String audioBitrate; // orn "192k"
}