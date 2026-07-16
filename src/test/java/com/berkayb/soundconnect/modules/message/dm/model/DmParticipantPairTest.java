package com.berkayb.soundconnect.modules.message.dm.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DmParticipantPairTest {

	@Test
	void pairIdentityIsIndependentOfInputOrder() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		assertThat(DmParticipantPair.of(first, second))
				.isEqualTo(DmParticipantPair.of(second, first));
	}

	@Test
	void rejectsSelfConversation() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> DmParticipantPair.of(userId, userId))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
