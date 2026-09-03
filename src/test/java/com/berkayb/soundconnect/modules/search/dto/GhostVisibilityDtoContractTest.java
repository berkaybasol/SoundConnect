package com.berkayb.soundconnect.modules.search.dto;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GhostVisibilityDtoContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void existingNonListenerPayloadShapeDoesNotGainANullVisibilityField() throws Exception {
		ProfileSearchItemDto searchItem = new ProfileSearchItemDto(
				"MUSICIAN",
				UUID.randomUUID(),
				UUID.randomUUID(),
				"Artist",
				"artist",
				"https://cdn.example/artist.jpg"
		);
		UserProfileTargetDto resolverTarget = new UserProfileTargetDto(
				"MUSICIAN",
				UUID.randomUUID(),
				"Artist",
				"https://cdn.example/artist.jpg"
		);

		assertThat(objectMapper.writeValueAsString(searchItem))
				.doesNotContain("visibilityMode");
		assertThat(objectMapper.writeValueAsString(resolverTarget))
				.doesNotContain("visibilityMode");
	}

	@Test
	void standardListenerMarkerIsNormalizedOutOfGenericPayloads() throws Exception {
		ProfileSearchItemDto searchItem = new ProfileSearchItemDto(
				"LISTENER", UUID.randomUUID(), UUID.randomUUID(), "Listener", "listener",
				null, ListenerVisibilityMode.STANDARD);
		UserProfileTargetDto resolverTarget = new UserProfileTargetDto(
				"LISTENER", UUID.randomUUID(), "Listener", null,
				ListenerVisibilityMode.STANDARD);

		assertThat(searchItem.visibilityMode()).isNull();
		assertThat(resolverTarget.visibilityMode()).isNull();
		assertThat(objectMapper.writeValueAsString(searchItem)).doesNotContain("visibilityMode");
		assertThat(objectMapper.writeValueAsString(resolverTarget)).doesNotContain("visibilityMode");
	}

	@Test
	void listenerPayloadsExposeTheTypedVisibilityMarkerAdditively() throws Exception {
		ProfileSearchItemDto searchItem = new ProfileSearchItemDto(
				"LISTENER",
				UUID.randomUUID(),
				UUID.randomUUID(),
				"ghosthandle",
				null,
				"https://cdn.example/avatar.jpg",
				ListenerVisibilityMode.GHOST
		);
		UserProfileTargetDto resolverTarget = new UserProfileTargetDto(
				"LISTENER",
				UUID.randomUUID(),
				"ghosthandle",
				"https://cdn.example/avatar.jpg",
				ListenerVisibilityMode.GHOST
		);

		assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(searchItem))
				.path("visibilityMode")
				.asText())
				.isEqualTo("GHOST");
		assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(resolverTarget))
				.path("visibilityMode")
				.asText())
				.isEqualTo("GHOST");
	}

}
