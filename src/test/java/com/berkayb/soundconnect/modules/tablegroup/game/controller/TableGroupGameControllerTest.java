package com.berkayb.soundconnect.modules.tablegroup.game.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameService;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TableGroupGameControllerTest {
	@Test
	void activeReturnsSuccessfulNullDataWhenThereIsNoActiveGame() {
		TableGroupGameService service = mock(TableGroupGameService.class);
		TableGroupGameController controller = new TableGroupGameController(service);
		UserDetailsImpl principal = mock(UserDetailsImpl.class);
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(principal.getId()).thenReturn(userId);
		when(service.getActive(userId, tableGroupId)).thenReturn(Optional.empty());

		var response = controller.active(principal, tableGroupId);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getSuccess()).isTrue();
		assertThat(response.getBody().getData()).isNull();
	}
}
