package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.support.MediaContentAudiencePolicy;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaContentAudienceServiceTest {
    @Mock MediaAssetRepository repository;
    @Mock ListenerVisibilityPolicy listenerVisibilityPolicy;
    @Mock StudioProfileRepository studios;
    @Mock com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard mediaUploadAbuseGuard;
    @Mock com.berkayb.soundconnect.modules.media.storage.MediaPolicy mediaPolicy;
    @Mock com.berkayb.soundconnect.modules.media.storage.StorageClient storageClient;
    @Mock com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow presignedUploadWriteWindow;
    @InjectMocks MediaAssetServiceImpl service;
    final UUID owner = UUID.randomUUID(), id = UUID.randomUUID();

    @AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

    @Test void legacyMusicRemainsMainstageAndStudioOwnershipOverridesClientAudience() {
        assertThat(asset(MediaVisibility.PUBLIC, null).getContentAudience()).isEqualTo(MediaContentAudience.MAINSTAGE);
        var studio = asset(MediaVisibility.PUBLIC, MediaContentAudience.MAINSTAGE);
        studio.setOwnerType(MediaOwnerType.STUDIO_PROFILE);
        assertThat(studio.getContentAudience()).isEqualTo(MediaContentAudience.BACKSTAGE);
    }

    @Test void newUploadPersistsExplicitAudienceAndLegacyUploadDefaultsToMainstage() {
        when(repository.save(any(MediaAsset.class))).thenAnswer(invocation -> {
            MediaAsset draft = invocation.getArgument(0); draft.setId(id); return draft;
        });
        when(mediaPolicy.buildSourceKey(id, "audio/mpeg")).thenReturn("media/asset/source.mp3");
        var captured = org.mockito.ArgumentCaptor.forClass(MediaAsset.class);
        service.initUpload(owner, MediaOwnerType.USER, owner, MediaKind.AUDIO, MediaVisibility.PRIVATE,
                "audio/mpeg", 100, "demo.mp3", MediaContentAudience.BACKSTAGE);
        verify(repository, times(2)).save(captured.capture());
        assertThat(captured.getValue().getContentAudience()).isEqualTo(MediaContentAudience.BACKSTAGE);
        assertThat(captured.getValue().getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
        clearInvocations(repository);
        service.initUpload(owner, MediaOwnerType.USER, owner, MediaKind.AUDIO, MediaVisibility.PUBLIC,
                "audio/mpeg", 100, "performance.mp3");
        verify(repository, times(2)).save(captured.capture());
        assertThat(captured.getValue().getContentAudience()).isEqualTo(MediaContentAudience.MAINSTAGE);
    }

    @Test void guestAndBackstageRetainPublicBusinessMediaAccess() {
        var business = asset(MediaVisibility.PUBLIC, MediaContentAudience.BACKSTAGE);
        when(repository.findById(id)).thenReturn(Optional.of(business));
        assertThat(service.getPublicReadyById(id)).isSameAs(business);
        viewer("ROLE_MUSICIAN");
        assertThat(service.getPlaybackUrl(id)).isEqualTo("https://cdn.test/play");
        verify(repository, never()).findMainstagePublicIds(any());
    }

    @Test void listenerDetailRejectsCurrentBackstageEvenIfAnOldManagedEntitySaysMainstage() {
        viewer("ROLE_LISTENER");
        when(repository.findMainstagePublicIds(List.of(id))).thenReturn(List.of());
        assertThatThrownBy(() -> service.getPublicReadyById(id)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.getPlaybackUrl(id)).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> service.getDisplayUrl(id)).isInstanceOf(SoundConnectException.class);
        verify(repository, never()).findById(any());
    }

    @Test void currentMainstageNeverMakesPrivateAssetsPublic() {
        viewer("ROLE_LISTENER");
        when(repository.findMainstagePublicIds(List.of(id))).thenReturn(List.of(id));
        when(repository.findById(id)).thenReturn(Optional.of(asset(MediaVisibility.PRIVATE, MediaContentAudience.MAINSTAGE)));
        assertThatThrownBy(() -> service.getPublicReadyById(id)).isInstanceOf(SoundConnectException.class);
    }

    @Test void urlBatchOmitsChangedAudienceFromStaleManagedAssetsWithoutPerItemQueries() {
        viewer("ROLE_LISTENER");
        UUID other = UUID.randomUUID();
        var first = asset(MediaVisibility.PUBLIC, MediaContentAudience.MAINSTAGE);
        var second = asset(MediaVisibility.PUBLIC, MediaContentAudience.MAINSTAGE);
        second.setId(other);
        when(repository.findMainstagePublicIds(List.of(id, other))).thenReturn(List.of(other));
        when(repository.findAllById(List.of(id, other))).thenReturn(List.of(first, second));
        assertThat(service.getPlaybackUrlMap(List.of(id, other))).containsOnlyKeys(other);
        verify(repository).findMainstagePublicIds(List.of(id, other));
    }

    @Test void listenerPublicPaginationFiltersInDatabaseAndKeepsPrivateAndGhostRules() {
        viewer("ROLE_LISTENER");
        var page = PageRequest.of(1, 20);
        when(repository.findMainstagePublicByOwner(MediaOwnerType.MUSICIAN_PROFILE, owner, null, page))
                .thenReturn(Page.empty(page));
        assertThat(service.listPublicByOwner(MediaOwnerType.MUSICIAN_PROFILE, owner, page)).isEmpty();
        verify(repository, never()).findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(any(), any(), any(), any(), any());
        when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestrictedProfile(owner)).thenReturn(true);
        assertThat(service.listPublicByOwner(MediaOwnerType.LISTENER_PROFILE, owner, page)).isEmpty();
        verify(repository, never()).findMainstagePublicByOwner(MediaOwnerType.LISTENER_PROFILE, owner, null, page);
    }

    @Test void ownerCanChangeDestinationUnderLockWithoutChangingPrivacyOrStorage() {
        var media = asset(MediaVisibility.PRIVATE, MediaContentAudience.MAINSTAGE);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(media));
        when(repository.save(media)).thenReturn(media);
        assertThat(service.updateContentAudience(owner, id, MediaContentAudience.BACKSTAGE)).isSameAs(media);
        assertThat(media.getContentAudience()).isEqualTo(MediaContentAudience.BACKSTAGE);
        assertThat(media.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
        assertThat(media.getPlaybackUrl()).isEqualTo("https://cdn.test/play");
        var order = inOrder(repository);
        order.verify(repository).findByIdForUpdate(id);
        order.verify(repository).save(media);
    }

    @Test void anotherUserCannotChangeAudience() {
        var media = asset(MediaVisibility.PUBLIC, MediaContentAudience.MAINSTAGE);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(media));
        assertThatThrownBy(() -> service.updateContentAudience(UUID.randomUUID(), id, MediaContentAudience.BACKSTAGE))
                .isInstanceOf(SoundConnectException.class);
        assertThat(media.getContentAudience()).isEqualTo(MediaContentAudience.MAINSTAGE);
        verify(repository, never()).save(any());
    }

    @Test void studioOwnerCannotPromoteStudioMediaIntoMainstage() {
        var media = asset(MediaVisibility.PUBLIC, MediaContentAudience.MAINSTAGE);
        media.setOwnerType(MediaOwnerType.STUDIO_PROFILE);
        var profile = StudioProfile.builder().user(User.builder().id(owner).build()).build();
        when(studios.findById(owner)).thenReturn(Optional.of(profile));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(media));
        when(repository.save(media)).thenReturn(media);
        assertThat(service.updateContentAudience(owner, id, MediaContentAudience.MAINSTAGE).getContentAudience())
                .isEqualTo(MediaContentAudience.BACKSTAGE);
    }

    @Test void listenerStudioPolicyDoesNotChangeGuestOrBackstageAccess() {
        assertThatCode(MediaContentAudiencePolicy::requireStudioAccess).doesNotThrowAnyException();
        viewer("ROLE_VENUE");
        assertThatCode(MediaContentAudiencePolicy::requireStudioAccess).doesNotThrowAnyException();
        viewer("ROLE_LISTENER");
        assertThatThrownBy(MediaContentAudiencePolicy::requireStudioAccess).isInstanceOf(SoundConnectException.class);
    }

    private MediaAsset asset(MediaVisibility visibility, MediaContentAudience audience) {
        return MediaAsset.builder().id(id).ownerType(MediaOwnerType.USER).ownerId(owner).kind(MediaKind.AUDIO)
                .status(MediaStatus.READY).visibility(visibility).contentAudience(audience)
                .playbackUrl("https://cdn.test/play").build();
    }
    static void viewer(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "viewer", "n/a", List.of(new SimpleGrantedAuthority(role))));
    }
}
