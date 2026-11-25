package com.berkayb.soundconnect.modules.profile.shared.visibility;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.block.UserBlockService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import com.berkayb.soundconnect.modules.search.service.VisibleProfileSearch;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileBlockVisibilityTest {
    private final UserBlockService blocks = mock(UserBlockService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ProfileBlockVisibility visibility = new ProfileBlockVisibility(blocks, jdbc);

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void profileUsesVerifiedViewerAndReturnsOrdinaryNotFound() {
        UUID viewer = UUID.randomUUID(), owner = UUID.randomUUID();
        var principal = UserDetailsImpl.fromUser(User.builder().id(viewer).build());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        when(blocks.blockedEitherWay(viewer, owner)).thenReturn(true);
        assertThatThrownBy(() -> visibility.requireVisible(owner)).isInstanceOfSatisfying(
                SoundConnectException.class, ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));
    }

    @Test void anonymousRequestsAndBandProfilesHaveNoPairwiseBlock() {
        assertThat(visibility.hiddenTargets(null, "MEDIA", List.of(UUID.randomUUID()))).isEmpty();
        assertThat(visibility.hiddenTargets(UUID.randomUUID(), "BAND", List.of(UUID.randomUUID()))).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test void profileSearchFillsTheRequestedLimitAfterAnEntireHiddenPage() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID(), fourth = UUID.randomUUID();
        var filter = mock(ProfileBlockVisibility.class);
        when(filter.hiddenOwners(List.of(first,second))).thenReturn(Set.of(first,second));
        assertThat(VisibleProfileSearch.collect(2, page -> page.getPageNumber() == 0 ? List.of(first, second)
                : List.of(third, fourth), id -> id, filter)).containsExactly(third, fourth);
    }
}
