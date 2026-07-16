package com.berkayb.soundconnect.modules.media.transcode.validation;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscodeTempBudgetManagerTest {

	@Test
	void reservationIsByteWeightedAndIdempotentlyReleased() throws Exception {
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxTempWorkBytes(1024);
		properties.setGlobalTempBudgetBytes(1024);
		TranscodeTempBudgetManager manager = new TranscodeTempBudgetManager(properties);

		TranscodeTempBudgetManager.Reservation reservation = manager.reserveMaxWorkBudget();
		assertThat(manager.reservedBytes()).isEqualTo(1024);

		reservation.close();
		reservation.close();
		assertThat(manager.reservedBytes()).isZero();
	}

	@Test
	void refusesConfigurationThatCannotAdmitOneWorstCaseJob() {
		TranscodeProperties properties = new TranscodeProperties();
		properties.setMaxTempWorkBytes(2048);
		properties.setGlobalTempBudgetBytes(1024);

		assertThatThrownBy(() -> new TranscodeTempBudgetManager(properties))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cover one maximum job");
	}
}
