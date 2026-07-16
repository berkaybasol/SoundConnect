package com.berkayb.soundconnect.modules.media.transcode.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscodeResourcePropertiesTest {

	@Test
	void defaultsBoundProfileAndGalleryVideoResources() {
		TranscodeProperties properties = new TranscodeProperties();

		assertThat(properties.getMaxDurationSeconds()).isEqualTo(900);
		assertThat(properties.getSourceDownloadTimeoutSec()).isEqualTo(7_200);
		assertThat(properties.getMaxVideoDimension()).isEqualTo(3840);
		assertThat(properties.getMaxVideoPixels()).isEqualTo(8_294_400L);
		assertThat(properties.getMaxFrameRate()).isEqualTo(60.0);
		assertThat(properties.getMaxEstimatedOutputBytes()).isEqualTo(2_147_483_648L);
		assertThat(properties.getMaxTempWorkBytes()).isEqualTo(6_442_450_944L);
		assertThat(properties.getGlobalTempBudgetBytes()).isEqualTo(6_442_450_944L);
		assertThat(properties.getTempVolumeBytes()).isEqualTo(6_442_450_944L);
		assertThat(properties.getTempBudgetAcquireTimeoutSeconds()).isEqualTo(300);
		assertThat(properties.getMinFreeTempBytes()).isEqualTo(536_870_912L);
		assertThat(properties.isResourceBudgetConsistent()).isTrue();
	}
}
