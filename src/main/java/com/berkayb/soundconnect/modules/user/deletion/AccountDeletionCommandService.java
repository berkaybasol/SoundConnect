package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.GoogleIdTokenValidator;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Network token verification must finish before opening the erasure transaction. */
@Service
@RequiredArgsConstructor
public class AccountDeletionCommandService {
    private final ListenerAccountDeletionService deletion;
    private final GoogleIdTokenValidator googleIdTokenValidator;
    private final AuthAccountRateLimitGuard rateLimit;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteSelf(UUID userId, AccountDeletionRequest request) {
        if (request == null || !"DELETE".equals(request.confirmation())) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        rateLimit.checkAccountDeletion(userId.toString());
        String googleSubject = request.googleIdToken() == null || request.googleIdToken().isBlank()
                ? null : googleIdTokenValidator.verify(request.googleIdToken()).subject();
        deletion.deleteSelf(userId, request.currentPassword(), googleSubject);
    }
}
