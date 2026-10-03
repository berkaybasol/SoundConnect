package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.otp.dto.request.VerifyCodeRequestDto;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.EmailVerificationRequiredException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceVenueApplicationSessionTest {
    @Mock JwtTokenProvider tokens;
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock VenueApplicationSessionAccess applications;
    @Mock AuthAccountRateLimitGuard rateLimits;
    @Mock OtpService otp;
    @InjectMocks AuthService service;
    private final UUID applicationId = UUID.randomUUID();
    private final User user = User.builder().id(UUID.randomUUID()).username("applicant").email("applicant@example.com")
            .password("hash").emailVerified(true).status(UserStatus.PENDING_VENUE_REQUEST).roles(Set.of()).permissions(Set.of()).build();

    @Test void verifiedPendingLoginOnlyIssuesScopedOwnApplicationSession() {
        when(users.findByUsername("applicant")).thenReturn(Optional.of(user));
        when(passwords.matches("password", "hash")).thenReturn(true);
        when(applications.findSessionApplication(user)).thenReturn(Optional.of(applicationId));
        when(tokens.generateVenueApplicationToken(any(UserDetailsImpl.class), eq(applicationId))).thenReturn("restricted");
        var result = service.login(new LoginRequestDto("applicant", "password")).getData();
        assertThat(result.sessionScope()).isEqualTo("VENUE_APPLICATION");
        assertThat(result.applicationId()).isEqualTo(applicationId);
        assertThat(result.roles()).isEmpty();
        assertThat(result.permissions()).isEmpty();
        assertThat(result.admin()).isFalse();
        assertThat(result.status()).isEqualTo(UserStatus.PENDING_VENUE_REQUEST);
        verify(tokens, never()).generateToken(any());
    }

    @Test void validOtpPersistsVerificationBeforeIssuingRestrictedCredential() {
        user.setEmailVerified(false);
        when(otp.verifyOtp(user.getEmail(), "123456")).thenReturn(true);
        when(users.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(applications.findSessionApplication(user)).thenReturn(Optional.of(applicationId));
        when(tokens.generateVenueApplicationToken(any(UserDetailsImpl.class), eq(applicationId))).thenReturn("restricted");
        var result = service.verifyCode(new VerifyCodeRequestDto(user.getEmail(), "123456")).getData();
        assertThat(result.sessionScope()).isEqualTo("VENUE_APPLICATION");
        assertThat(user.getEmailVerified()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VENUE_REQUEST);
        var ordered = inOrder(users, applications, tokens);
        ordered.verify(users).saveAndFlush(user);
        ordered.verify(applications).findSessionApplication(user);
        ordered.verify(tokens).generateVenueApplicationToken(any(), eq(applicationId));
        verify(tokens, never()).generateToken(any());
    }

    @Test void wrongPasswordCannotProbeSourceOrReceiveScopedToken() {
        when(users.findByUsername("applicant")).thenReturn(Optional.of(user));
        when(passwords.matches("wrong", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.login(new LoginRequestDto("applicant", "wrong"))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(applications, tokens);
    }

    @Test void unverifiedPendingLoginCannotReceiveScopedToken() {
        user.setEmailVerified(false);
        when(users.findByUsername("applicant")).thenReturn(Optional.of(user));
        when(passwords.matches("password", "hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(new LoginRequestDto("applicant", "password"))).isInstanceOf(EmailVerificationRequiredException.class);
        verifyNoInteractions(applications, tokens);
    }

    @Test void absentOrIneligibleOwnSourceDoesNotIssueAnySession() {
        when(users.findByUsername("applicant")).thenReturn(Optional.of(user));
        when(passwords.matches("password", "hash")).thenReturn(true);
        when(applications.findSessionApplication(user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequestDto("applicant", "password"))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(tokens);
    }
}
