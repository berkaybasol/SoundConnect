package com.berkayb.soundconnect.modules.comment.mapper;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMapperGhostIdentityTest {

	private final CommentMapper mapper = Mappers.getMapper(CommentMapper.class);

	@Test
	void visibleGhostCommentUsesCanonicalListenerIdentity() {
		UUID authorId = UUID.randomUUID();
		User author = User.builder()
				.id(authorId)
				.username("alternate-profile-name")
				.profilePicture("alternate-avatar.png")
				.build();
		Comment comment = comment(author);
		GhostListenerIdentity identity = ghostIdentity(authorId);

		CommentResponseDto dto = mapper.toCommentResponseDto(comment, 4, false, identity);

		assertThat(dto.anonymousAuthor()).isFalse();
		assertThat(dto.user().id()).isEqualTo(authorId);
		assertThat(dto.user().username()).isEqualTo("listener-handle");
		assertThat(dto.user().avatarUrl()).isEqualTo("listener-avatar.png");
		assertThat(dto.user().visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
	}

	@Test
	void maskedCommentDoesNotLeakGhostIdentity() {
		UUID authorId = UUID.randomUUID();
		User author = User.builder().id(authorId).username("real-handle").build();

		CommentResponseDto dto = mapper.toCommentResponseDto(
				comment(author),
				0,
				true,
				ghostIdentity(authorId)
		);

		assertThat(dto.anonymousAuthor()).isTrue();
		assertThat(dto.user().id()).isNull();
		assertThat(dto.user().username()).isEqualTo("Anonymous Author");
		assertThat(dto.user().avatarUrl()).isNull();
		assertThat(dto.user().visibilityMode()).isNull();
	}

	private Comment comment(User author) {
		return Comment.builder()
				.id(UUID.randomUUID())
				.user(author)
				.targetType(EngagementTargetType.OVERTHINKING)
				.targetId(UUID.randomUUID())
				.text("Katılıyorum.")
				.deleted(false)
				.build();
	}

	private GhostListenerIdentity ghostIdentity(UUID userId) {
		return new GhostListenerIdentity(
				userId,
				"listener-handle",
				"listener-avatar.png",
				ListenerVisibilityMode.GHOST
		);
	}
}
