package com.berkayb.soundconnect.modules.like;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.engagement.service.EngagementTargetValidator;
import com.berkayb.soundconnect.modules.like.repository.*;
import com.berkayb.soundconnect.modules.like.service.*;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeUsersReadServiceTest {
    @Mock LikeRepository likes;
    @Mock LikeUsersReadRepository repository;
    @Mock EngagementTargetValidator targets;
    @Mock CommentLikeAccessGuard comments;
    @Mock CommentAuthorBatchResolver identities;
    @InjectMocks LikeUsersReadService service;
    final UUID viewer = UUID.randomUUID(), target = UUID.randomUUID();
    final LocalDateTime now = LocalDateTime.parse("2026-09-12T10:00:00");

    @Test void boundedLookaheadAndCanonicalIdentityKeepTheExactShareTarget() {
        when(likes.lockActiveActor(viewer)).thenReturn(Optional.of(viewer));
        var rows = new ArrayList<LikeUsersReadRepository.Row>();
        var users = new LinkedHashMap<UUID, UserSummaryDto>();
        for (int i = 0; i < 51; i++) {
            UUID user = UUID.randomUUID();
            rows.add(new LikeUsersReadRepository.Row(UUID.randomUUID(), user, now.minusSeconds(i)));
            if (i < 50) users.put(user, new UserSummaryDto(user, "username" + i, null));
        }
        when(repository.page(EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target, null, 51)).thenReturn(rows);
        when(identities.resolve(List.copyOf(users.keySet()))).thenReturn(users);

        var result = service.get(viewer, EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target, 50, null);
        assertThat(result.items()).containsExactlyElementsOf(users.values());
        assertThat(result.hasMore()).isTrue();
        var position = LikeUsersCursor.decode(result.nextCursor(), EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target);
        assertThat(position.likeId()).isEqualTo(rows.get(49).id());
        assertThat(position.createdAt()).isEqualTo(rows.get(49).createdAt());
        var order = inOrder(likes, targets, repository, identities);
        order.verify(likes).lockActiveActor(viewer);
        order.verify(targets).validateExists(EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target);
        order.verify(repository).page(EngagementTargetType.OVERTHINKING_PROFILE_SHARE, target, null, 51);
        order.verify(identities).resolve(List.copyOf(users.keySet()));
        verifyNoInteractions(comments);
    }

    @ParameterizedTest @EnumSource(EngagementTargetType.class)
    void hiddenTargetCannotBeUsedToEnumerateLikes(EngagementTargetType type) {
        when(likes.lockActiveActor(viewer)).thenReturn(Optional.of(viewer));
        var failure = new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
        if (type == EngagementTargetType.COMMENT) doThrow(failure).when(comments).requireLikeable(target, false);
        else doThrow(failure).when(targets).validateExists(type, target);
        assertThatThrownBy(() -> service.get(viewer, type, target, 20, null)).isSameAs(failure);
        verifyNoInteractions(repository, identities);
    }

    @Test void missingOrInactiveViewerCannotEnumeratePublicUsers() {
        assertThatThrownBy(() -> service.get(null, EngagementTargetType.MEDIA, target, 20, null))
                .isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        assertThatThrownBy(() -> service.get(viewer, EngagementTargetType.MEDIA, target, 20, null))
                .isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        verifyNoInteractions(targets, comments, repository, identities);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 51, Integer.MAX_VALUE})
    void invalidPageSizeIsRejectedBeforeDatabaseWork(int size) {
        assertThatThrownBy(() -> service.get(viewer, EngagementTargetType.MEDIA, target, size, null))
                .isInstanceOfSatisfying(SoundConnectException.class, e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.INVALID_PARAMETER));
        verifyNoInteractions(likes, targets, comments, repository, identities);
    }

    @Test void finalAndEmptyPagesDoNotInventACursor() {
        when(likes.lockActiveActor(viewer)).thenReturn(Optional.of(viewer));
        when(repository.page(EngagementTargetType.MEDIA, target, null, 21)).thenReturn(List.of());
        var result = service.get(viewer, EngagementTargetType.MEDIA, target, null, null);
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test void cursorIsCanonicalBoundedAndBoundToTheExactTarget() {
        var position = new LikeUsersCursor(now, UUID.randomUUID());
        String encoded = position.encode(EngagementTargetType.EVENT_POST, target);
        assertThat(LikeUsersCursor.decode(encoded, EngagementTargetType.EVENT_POST, target)).isEqualTo(position);
        for (String invalid : List.of("", " ", "!", "a".repeat(513), encoded + "=")) {
            assertThatThrownBy(() -> LikeUsersCursor.decode(invalid, EngagementTargetType.EVENT_POST, target))
                    .isInstanceOf(SoundConnectException.class);
        }
        assertThatThrownBy(() -> LikeUsersCursor.decode(encoded, EngagementTargetType.EVENT, target)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> LikeUsersCursor.decode(encoded, EngagementTargetType.EVENT_POST, UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        String oversizedYear = new LikeUsersCursor(LocalDateTime.of(999999999, 1, 1, 0, 0), UUID.randomUUID())
                .encode(EngagementTargetType.EVENT_POST, target);
        assertThatThrownBy(() -> LikeUsersCursor.decode(oversizedYear, EngagementTargetType.EVENT_POST, target))
                .isInstanceOf(SoundConnectException.class);
        var legacy = new LikeUsersCursor(null, UUID.randomUUID());
        assertThat(LikeUsersCursor.decode(legacy.encode(EngagementTargetType.MEDIA, target), EngagementTargetType.MEDIA, target)).isEqualTo(legacy);
    }
}
