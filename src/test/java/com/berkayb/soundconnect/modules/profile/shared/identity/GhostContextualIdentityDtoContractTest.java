package com.berkayb.soundconnect.modules.profile.shared.identity;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GhostContextualIdentityDtoContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void ghostMarkersAreSerializedAdditivelyInEveryContextualIdentity() {
		JsonNode follow = json(new FollowResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"ghost-follower",
				"follower.png",
				ListenerVisibilityMode.GHOST,
				UUID.randomUUID(),
				"ghost-following",
				"following.png",
				ListenerVisibilityMode.GHOST,
				null
		));
		JsonNode bandFollow = json(new BandFollowResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"ghost-follower",
				"follower.png",
				ListenerVisibilityMode.GHOST,
				UUID.randomUUID(),
				"Band",
				null,
				null,
				null
		));
		JsonNode post = json(postDto(true, ListenerVisibilityMode.GHOST));
		JsonNode commentUser = json(new UserSummaryDto(
				UUID.randomUUID(),
				"ghost-author",
				"author.png",
				ListenerVisibilityMode.GHOST
		));

		assertThat(follow.path("followerVisibilityMode").asText()).isEqualTo("GHOST");
		assertThat(follow.path("followingVisibilityMode").asText()).isEqualTo("GHOST");
		assertThat(bandFollow.path("followerVisibilityMode").asText()).isEqualTo("GHOST");
		assertThat(post.path("authorVisibilityMode").asText()).isEqualTo("GHOST");
		assertThat(commentUser.path("visibilityMode").asText()).isEqualTo("GHOST");
	}

	@Test
	void standardMarkersAreNormalizedAndOmittedForBackwardCompatiblePayloads() {
		JsonNode follow = json(new FollowResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"follower",
				null,
				ListenerVisibilityMode.STANDARD,
				UUID.randomUUID(),
				"following",
				null,
				ListenerVisibilityMode.STANDARD,
				null
		));
		JsonNode bandFollow = json(new BandFollowResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"follower",
				null,
				ListenerVisibilityMode.STANDARD,
				UUID.randomUUID(),
				"Band",
				null,
				null,
				null
		));
		JsonNode post = json(postDto(true, ListenerVisibilityMode.STANDARD));
		JsonNode commentUser = json(new UserSummaryDto(
				UUID.randomUUID(),
				"author",
				null,
				ListenerVisibilityMode.STANDARD
		));

		assertThat(follow.has("followerVisibilityMode")).isFalse();
		assertThat(follow.has("followingVisibilityMode")).isFalse();
		assertThat(bandFollow.has("followerVisibilityMode")).isFalse();
		assertThat(post.has("authorVisibilityMode")).isFalse();
		assertThat(commentUser.has("visibilityMode")).isFalse();
	}

	@Test
	void maskedAuthorsCannotLeakIdsAvatarsOrGhostMarkers() {
		JsonNode post = json(postDto(false, ListenerVisibilityMode.GHOST));
		JsonNode commentUser = json(new UserSummaryDto(
				null,
				"real-handle",
				"real-avatar.png",
				ListenerVisibilityMode.GHOST
		));

		assertThat(post.path("authorId").isNull()).isTrue();
		assertThat(post.path("authorUsername").asText()).isEqualTo("Anonymous");
		assertThat(post.path("authorAvatarUrl").isNull()).isTrue();
		assertThat(post.has("authorVisibilityMode")).isFalse();

		assertThat(commentUser.path("id").isNull()).isTrue();
		assertThat(commentUser.path("username").asText()).isEqualTo("Anonymous Author");
		assertThat(commentUser.path("avatarUrl").isNull()).isTrue();
		assertThat(commentUser.has("visibilityMode")).isFalse();
	}

	private OverthinkingPostResponseDto postDto(
			boolean canViewAuthor,
			ListenerVisibilityMode visibilityMode
	) {
		return new OverthinkingPostResponseDto(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"real-handle",
				"real-avatar.png",
				visibilityMode,
				!canViewAuthor,
				canViewAuthor,
				canViewAuthor
						? OverthinkingVisibilityType.VISIBLE
						: OverthinkingVisibilityType.ANONYMOUS,
				"Title",
				"Content",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				0,
				false
		);
	}

	private JsonNode json(Object value) {
		return objectMapper.valueToTree(value);
	}
}
