package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DmPushPresentationTest {
    final PublicProfileResolverService profiles=mock(PublicProfileResolverService.class);
    final UserRepository users=mock(UserRepository.class);
    final DmPushPresentation subject=new DmPushPresentation(profiles,users,"https://example.cloudfront.net");
    final UUID sender=UUID.randomUUID();
    @Test void resolvesAgainForEveryDispatchAndDoesNotReusePreGhostIdentity() {
        when(users.findPublicUsernameForDmPush(sender)).thenReturn(Optional.of("public_handle"));
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of(
                new UserProfileTargetDto("MUSICIAN",UUID.randomUUID(),"Public Name","https://example.cloudfront.net/avatar.png"))),
                new UserProfilesResolveResponseDto(sender,List.of(new UserProfileTargetDto("LISTENER",UUID.randomUUID(),
                        "ghost_alias",null, ListenerVisibilityMode.GHOST))));
        assertThat(subject.resolve(sender).name()).isEqualTo("public_handle");
        assertThat(subject.resolve(sender)).isEqualTo(new DmPushPresentation.Sender("ghost_alias",""));
        verify(users,times(1)).findPublicUsernameForDmPush(sender);
    }
    @Test void pendingChoiceOrFailureNeverFallsBackToRawUserIdentity() {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of()))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThat(subject.resolve(sender)).isEqualTo(DmPushPresentation.anonymous());
        assertThat(subject.resolve(sender)).isEqualTo(DmPushPresentation.anonymous());
        verifyNoInteractions(users);
    }

    @Test void ghostInMixedProfileResultNeverQueriesOrUsesRawAccountHandle() {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of(
                new UserProfileTargetDto("VENUE",UUID.randomUUID(),"Private venue","https://example.cloudfront.net/venue.png"),
                new UserProfileTargetDto("LISTENER",UUID.randomUUID(),"safe_alias",null,ListenerVisibilityMode.GHOST))));
        assertThat(subject.resolve(sender)).isEqualTo(new DmPushPresentation.Sender("safe_alias",""));
        verifyNoInteractions(users);
    }

    @Test void completedPublicPersonalProfilesUseTheCurrentHandleInsteadOfDisplayName() {
        for(String type:List.of("MUSICIAN","LISTENER","VENUE","STUDIO","ORGANIZER","PRODUCER")) {
            when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of(
                    new UserProfileTargetDto(type,UUID.randomUUID(),"Profile Display","https://example.cloudfront.net/avatar.png"))));
            when(users.findPublicUsernameForDmPush(sender)).thenReturn(Optional.of("handle_"+type.toLowerCase()));
            assertThat(subject.resolve(sender)).isEqualTo(new DmPushPresentation.Sender("handle_"+type.toLowerCase(),"https://example.cloudfront.net/avatar.png"));
        }
    }

    @Test void unavailableOrNewlyRestrictedAccountDoesNotLeakOldDisplayOrAvatar() {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of(
                new UserProfileTargetDto("MUSICIAN",UUID.randomUUID(),"Old public name","https://example.cloudfront.net/avatar.png"))));
        when(users.findPublicUsernameForDmPush(sender)).thenReturn(Optional.empty()).thenThrow(new IllegalStateException("unavailable"));
        assertThat(subject.resolve(sender)).isEqualTo(DmPushPresentation.anonymous());
        assertThat(subject.resolve(sender)).isEqualTo(DmPushPresentation.anonymous());
    }

    @Test void mismatchedResolvedOwnerIsAnonymousAndDoesNotLookupHandle() {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(UUID.randomUUID(),List.of(
                new UserProfileTargetDto("MUSICIAN",UUID.randomUUID(),"Other public name",null))));
        assertThat(subject.resolve(sender)).isEqualTo(DmPushPresentation.anonymous());
        verifyNoInteractions(users);
    }

    @Test void admittedLegacyBlankHandleUsesOnlyResolvedSafeDisplayName() {
        when(profiles.resolveByUserId(sender)).thenReturn(new UserProfilesResolveResponseDto(sender,List.of(
                new UserProfileTargetDto("MUSICIAN",UUID.randomUUID(),"Safe display",null))));
        when(users.findPublicUsernameForDmPush(sender)).thenReturn(Optional.of("  "));
        assertThat(subject.resolve(sender).name()).isEqualTo("Safe display");
    }
    @Test void avatarOnlyAllowsUnsignedHttpsConfiguredCdn() {
        assertThat(subject.safeAvatar("https://example.cloudfront.net/media/a.png")).isNotEmpty();
        for(String url:List.of("http://example.cloudfront.net/a", "https://127.0.0.1/a",
                "https://example.cloudfront.net.evil.invalid/a", "https://evil.invalid/a",
                "https://user@example.cloudfront.net/a", "https://example.cloudfront.net:8443/a",
                "https://example.cloudfront.net/a?secret=signed", "https://example.cloudfront.net/a#part"))
            assertThat(subject.safeAvatar(url)).isEmpty();
    }
    @Test void visibleNameIsBoundedAndCannotInjectControlOrBidiFormatting() {
        assertThat(DmPushPresentation.safeName("  A\n\u202EB\t  ")).isEqualTo("AB");
        assertThat(DmPushPresentation.safeName("\uD83D\uDE00".repeat(100)).codePointCount(0,
                DmPushPresentation.safeName("\uD83D\uDE00".repeat(100)).length())).isEqualTo(80);
    }
}
