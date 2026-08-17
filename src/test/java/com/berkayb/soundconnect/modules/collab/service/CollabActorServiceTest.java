package com.berkayb.soundconnect.modules.collab.service;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.repository.CollabActorRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.ownership.OwnedProfileTarget;
import com.berkayb.soundconnect.modules.profile.shared.ownership.ProfileOwnershipResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollabActorServiceTest {

    @Mock CollabActorRepository actorRepository;
    @Mock ProfileOwnershipResolver ownershipResolver;
    @Mock UserRepository userRepository;
    @Mock BandRepository bandRepository;

    @InjectMocks CollabActorService service;

    @Test
    void requireOwnedRevalidatesOwnershipThenLocksAndReactivatesActorSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CollabActor actor = actor(actorId, sourceId, false);
        OwnedProfileTarget target = new OwnedProfileTarget(
                ProfileType.VENUE, sourceId, "  Yeni Mekan Adi  ", "  https://cdn/avatar.jpg  ");
        when(actorRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(ownershipResolver.requireOwnership(userId, ProfileType.VENUE, sourceId)).thenReturn(target);
        when(actorRepository.findByIdForUpdate(actorId)).thenReturn(Optional.of(actor));

        CollabActor result = service.requireOwned(userId, actorId);

        assertThat(result).isSameAs(actor);
        assertThat(result.isActive()).isTrue();
        assertThat(result.getDisplayName()).isEqualTo("Yeni Mekan Adi");
        assertThat(result.getAvatarUrl()).isEqualTo("https://cdn/avatar.jpg");
        InOrder order = inOrder(actorRepository, ownershipResolver);
        order.verify(actorRepository).findById(actorId);
        order.verify(ownershipResolver).requireOwnership(userId, ProfileType.VENUE, sourceId);
        order.verify(actorRepository).findByIdForUpdate(actorId);
    }

    @Test
    void requireOwnedDoesNotAcquireActorWriteLockWhenOwnershipWasLost() {
        UUID userId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        CollabActor actor = actor(actorId, sourceId, true);
        when(actorRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(ownershipResolver.requireOwnership(userId, ProfileType.VENUE, sourceId))
                .thenThrow(new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));

        assertThatThrownBy(() -> service.requireOwned(userId, actorId))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));

        verify(actorRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void listMineSynchronizesExistingActorsUnderProfileKeyLock() {
        UUID userId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setUsername("\u00A0Studio_Owner\uFEFF");
        OwnedProfileTarget target = new OwnedProfileTarget(ProfileType.STUDIO, sourceId,
                "  Kayit Studyosu  ", " ");
        CollabActor actor = CollabActor.builder()
                .profileType(ProfileType.STUDIO)
                .sourceProfileId(sourceId)
                .displayName("Eski Ad")
                .avatarUrl("old-avatar")
                .active(false)
                .build();
        actor.setId(actorId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(ownershipResolver.resolveOwnedProfiles(userId, CollabActorService.ALLOWED_TYPES))
                .thenReturn(List.of(target));
        when(actorRepository.findByProfileTypeAndSourceProfileIdForUpdate(ProfileType.STUDIO, sourceId))
                .thenReturn(Optional.of(actor));
        when(actorRepository.save(actor)).thenReturn(actor);

        List<CollabActorSummary> result = service.listMine(userId);

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.actorId()).isEqualTo(actorId);
            assertThat(summary.contactUserId()).isEqualTo(userId);
            assertThat(summary.contactUsername()).isEqualTo("studio_owner");
            assertThat(summary.displayName()).isEqualTo("Kayit Studyosu");
        });
        assertThat(actor.isActive()).isTrue();
        assertThat(actor.getAvatarUrl()).isNull();
        verify(actorRepository)
                .findByProfileTypeAndSourceProfileIdForUpdate(ProfileType.STUDIO, sourceId);
        verify(userRepository, times(1)).findByIdForUpdate(userId);
    }

    @Test
    void toSummaryDoesNotInventUsernameForBlankLegacyValue() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("   ");

        CollabActorSummary result = service.toSummary(
                actor(UUID.randomUUID(), UUID.randomUUID(), true), user);

        assertThat(result.contactUserId()).isEqualTo(user.getId());
        assertThat(result.contactUsername()).isNull();
    }

    private CollabActor actor(UUID actorId, UUID sourceId, boolean active) {
        CollabActor actor = CollabActor.builder()
                .profileType(ProfileType.VENUE)
                .sourceProfileId(sourceId)
                .displayName("Eski Ad")
                .active(active)
                .build();
        actor.setId(actorId);
        return actor;
    }
}
