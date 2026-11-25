package com.berkayb.soundconnect.auth.passwordchange;

import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountPasswordChangeService {
    public record PasswordChanged(UUID userId, long sessionVersion) {}

    private final AuthAccountRateLimitGuard rateLimits;
    private final UserRepository users;
    private final EntityManager entityManager;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider tokens;
    private final ApplicationEventPublisher events;

    @Transactional
    public String change(UUID accountId, long authenticatedVersion, PasswordChangeRequest request) {
        rateLimits.checkPasswordChange(accountId.toString());
        User user = users.findByIdForUpdate(accountId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.UNAUTHORIZED));
        // The filter may already have loaded the user before another transaction committed.
        entityManager.refresh(user);
        var principal = UserDetailsImpl.fromUser(user);
        if (user.isAccessSuspended()) throw new SoundConnectException(ErrorType.ACCOUNT_SUSPENDED);
        if (user.getSessionVersion() != authenticatedVersion || !principal.isEnabled()
                || !principal.isAccountNonLocked() || user.getRoles().isEmpty()) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new SoundConnectException(ErrorType.PASSWORD_CHANGE_PROVIDER_UNSUPPORTED);
        }
        if (!encoder.matches(request.currentPassword(), user.getPassword())) {
            throw new SoundConnectException(ErrorType.PASSWORD_CHANGE_CURRENT_INVALID);
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new SoundConnectException(ErrorType.PASSWORD_CHANGE_SAME_PASSWORD);
        }

        if (users.resetPasswordAndRevokeSessions(accountId, encoder.encode(request.newPassword())) != 1) {
            throw new IllegalStateException("Locked account credential update failed");
        }
        entityManager.refresh(user);
        String token = tokens.generateToken(principal);
        events.publishEvent(new PasswordChanged(accountId, user.getSessionVersion()));
        return token;
    }
}
