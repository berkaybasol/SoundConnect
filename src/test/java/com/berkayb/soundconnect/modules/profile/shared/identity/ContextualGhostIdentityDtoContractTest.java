package com.berkayb.soundconnect.modules.profile.shared.identity;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextualGhostIdentityDtoContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void tableGroupMarkersAreSerializedOnlyForGhostIdentities() throws Exception {
		TableGroupParticipantDto standardParticipant = new TableGroupParticipantDto(
				UUID.randomUUID(),
				null,
				ParticipantStatus.ACCEPTED,
				null,
				"standard",
				null,
				ListenerVisibilityMode.STANDARD
		);
		TableGroupParticipantDto ghostParticipant = new TableGroupParticipantDto(
				UUID.randomUUID(),
				null,
				ParticipantStatus.ACCEPTED,
				null,
				"ghosthandle",
				null,
				ListenerVisibilityMode.GHOST
		);
		TableGroupResponseDto standardOwner = response(ListenerVisibilityMode.STANDARD);
		TableGroupResponseDto ghostOwner = response(ListenerVisibilityMode.GHOST);

		assertThat(objectMapper.writeValueAsString(standardParticipant))
				.doesNotContain("visibilityMode");
		assertThat(standardParticipant.visibilityMode()).isNull();
		assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(ghostParticipant))
				.path("visibilityMode").asText()).isEqualTo("GHOST");
		assertThat(objectMapper.writeValueAsString(standardOwner))
				.doesNotContain("ownerVisibilityMode");
		assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(ghostOwner))
				.path("ownerVisibilityMode").asText()).isEqualTo("GHOST");
	}

	@Test
	void dmPreviewMarkerIsSerializedOnlyForGhostIdentity() throws Exception {
		DMConversationPreviewResponseDto standard = new DMConversationPreviewResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"standard",
				null,
				null,
				null,
				null,
				null,
				null,
				ListenerVisibilityMode.STANDARD
		);
		DMConversationPreviewResponseDto ghost = new DMConversationPreviewResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"ghosthandle",
				null,
				null,
				null,
				null,
				null,
				null,
				ListenerVisibilityMode.GHOST
		);

		assertThat(objectMapper.writeValueAsString(standard))
				.doesNotContain("otherUserVisibilityMode");
		assertThat(standard.otherUserVisibilityMode()).isNull();
		JsonNode ghostJson = objectMapper.readTree(objectMapper.writeValueAsBytes(ghost));
		assertThat(ghostJson.path("otherUserVisibilityMode").asText()).isEqualTo("GHOST");
	}

	private TableGroupResponseDto response(ListenerVisibilityMode visibilityMode) {
		return new TableGroupResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"owner",
				null,
				null,
				null,
				null,
				4,
				List.of(),
				18,
				99,
				null,
				null,
				null,
				TableGroupStatus.ACTIVE,
				Set.of(),
				null,
				null,
				null,
				visibilityMode
		);
	}
}
