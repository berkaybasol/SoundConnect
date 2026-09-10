package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.model.VerifiedGoogleIdentity;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeletionCommandServiceTest {
    final ListenerAccountDeletionService deletion = mock(ListenerAccountDeletionService.class);
    final GoogleIdTokenValidator google = mock(GoogleIdTokenValidator.class);
    final AuthAccountRateLimitGuard guard = mock(AuthAccountRateLimitGuard.class);
    final AccountDeletionCommandService commands = new AccountDeletionCommandService(deletion, google, guard);
    final UUID user = UUID.randomUUID();

    @Test void invalidConfirmationNeverStartsCredentialVerificationOrDeletion() {
        assertThatThrownBy(() -> commands.deleteSelf(user, new AccountDeletionRequest("YES", "secret", null)))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(guard, google, deletion);
    }

    @Test void throttledReauthenticationNeverReachesTheExpensiveVerifierOrTheDeletionTransaction() {
        doThrow(new SoundConnectException(ErrorType.AUTH_RATE_LIMITED)).when(guard).checkAccountDeletion(user.toString());
        assertThatThrownBy(() -> commands.deleteSelf(user, new AccountDeletionRequest("DELETE", null, "raw-token")))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(google, deletion);
    }

    @Test void forgedGoogleTokenNeverStartsDeletion() {
        when(google.verify("forged-token")).thenThrow(new SoundConnectException(ErrorType.UNAUTHORIZED));
        assertThatThrownBy(() -> commands.deleteSelf(user, new AccountDeletionRequest("DELETE", null, "forged-token")))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(deletion);
    }

    @Test void onlyTheVerifiedGoogleSubjectCrossesTheDeletionBoundary() {
        when(google.verify("raw-token")).thenReturn(new VerifiedGoogleIdentity("signed-subject", "mail@test.invalid", "name"));
        commands.deleteSelf(user, new AccountDeletionRequest("DELETE", null, "raw-token"));
        verify(deletion).deleteSelf(user, null, "signed-subject");
        assertThat(new AccountDeletionRequest("DELETE", "secret", "raw-token").toString())
                .doesNotContain("secret", "raw-token");
    }
}
