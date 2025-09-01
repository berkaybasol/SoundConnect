// src/test/java/com/berkayb/soundconnect/modules/media/transcode/config/TranscodePropertiesTest.java
package com.berkayb.soundconnect.modules.media.transcode.config;

import com.berkayb.soundconnect.modules.media.transcode.enums.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class TranscodePropertiesTest {
	
	TranscodeProperties props;
	
	@BeforeEach
	void setup() {
		props = new TranscodeProperties();
	}
	
	@Test
	void afterBind_sortsLadder_byHeight_desc() {
		TranscodeVariant v360 = new TranscodeVariant();
		v360.setHeight(360);
		v360.setVideoBitrate("600k");
		v360.setAudioBitrate("96k");
		
		TranscodeVariant v1080 = new TranscodeVariant();
		v1080.setHeight(1080);
		v1080.setVideoBitrate("6000k");
		v1080.setAudioBitrate("192k");
		
		TranscodeVariant v720 = new TranscodeVariant();
		v720.setHeight(720);
		v720.setVideoBitrate("3500k");
		v720.setAudioBitrate("128k");
		
		// karışık sırada verelim
		props.setLadder(List.of(v360, v1080, v720));
		
		// act
		props.afterBind();
		
		// assert: azalan (1080, 720, 360)
		assertThat(props.getLadder()).extracting(TranscodeVariant::getHeight)
		                             .containsExactly(1080, 720, 360);
	}
	
	@Test
	void defaults_areReasonable_and_canBeOverridden() {
		// varsayılanlar
		assertThat(props.getContainer()).isEqualTo(Container.FMP4);
		assertThat(props.getSegmentDurationSec()).isGreaterThanOrEqualTo(1);
		assertThat(props.getGop()).isGreaterThanOrEqualTo(1);
		assertThat(props.getCrf()).isGreaterThanOrEqualTo(0);
		assertThat(props.getPreset()).isNotBlank();
		assertThat(props.getThumbnailSecond()).isGreaterThanOrEqualTo(0);
		
		// override edelim
		props.setContainer(Container.TS);
		props.setSegmentDurationSec(6);
		props.setGop(30);
		props.setCrf(23);
		props.setPreset("faster");
		props.setThumbnailSecond(3);
		
		assertThat(props.getContainer()).isEqualTo(Container.TS);
		assertThat(props.getSegmentDurationSec()).isEqualTo(6);
		assertThat(props.getGop()).isEqualTo(30);
		assertThat(props.getCrf()).isEqualTo(23);
		assertThat(props.getPreset()).isEqualTo("faster");
		assertThat(props.getThumbnailSecond()).isEqualTo(3);
	}
	
	@Test
	void ladder_mustNotBeEmpty_forABR_but_method_tolerates_small_lists() {
		// tek elemanlı ladder (uyarı loglanır ama akış kırılmaz)
		TranscodeVariant only = new TranscodeVariant();
		only.setHeight(480);
		only.setVideoBitrate("1200k");
		only.setAudioBitrate("128k");
		
		props.setLadder(List.of(only));
		
		// act: sıralama/yapılandırma çalışır
		props.afterBind();
		
		// assert
		assertThat(props.getLadder()).hasSize(1);
		assertThat(props.getLadder().get(0).getHeight()).isEqualTo(480);
	}
}