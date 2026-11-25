package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.ratelimit.AuthAccountRateLimitGuard;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandDeletionDecision;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandDeletionOption;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandAccountLifecycle;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.util.EmailUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/account-deletion")
@RequiredArgsConstructor
public class MailboxDeletionController {
    private final AuthAccountRateLimitGuard guard;
    private final UserRepository users;
    private final OtpService otp;
    private final MailProducer mail;
    private final StringRedisTemplate redis;
    private final BandAccountLifecycle bands;
    private final ListenerAccountDeletionService deletion;
    private final PasswordEncoder passwords;

    public record Request(@NotBlank @Email @Size(max=254) String email) {}
    public record Password(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=128) String currentPassword) {
        @Override public String toString() { return "Password[REDACTED]"; }
    }
    public record Verify(@NotBlank @Email @Size(max=254) String email, @NotNull @Pattern(regexp="[0-9]{6}") String code) {
        @Override public String toString() { return "Verify[REDACTED]"; }
    }
    public record Confirm(@NotBlank @Email @Size(max=254) String email, @NotNull UUID proof,
                          @NotNull @Pattern(regexp="DELETE") String confirmation,
                          @Size(max=100) List<@NotNull @Valid BandDeletionDecision> bandDecisions) {
        @Override public String toString() { return "Confirm[REDACTED]"; }
    }
    public record Preview(String username, UUID proof, List<BandDeletionOption> bands) {
        @Override public String toString() { return "Preview[REDACTED]"; }
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@Valid @RequestBody Request request) {
        String email = EmailUtils.normalize(request.email());
        guard.checkMailboxDeletion(email, true);
        users.findByEmail(email).filter(u -> u.getErasedAt() == null).ifPresent(user -> {
            var claim = otp.acquireErasureOtp(user.getId(), email);
            if (claim.acquired()) {
                String text = "SoundConnect hesabını silme talebin için doğrulama kodun: " + claim.code()
                        + ". Hesabın yalnızca son adımı onayladığında silinir. Bu talebi sen başlatmadıysan kodu paylaşma.";
                mail.send(new MailSendRequest(email, "SoundConnect hesap silme kodu", "<p>" + text + "</p>", text,
                        MailKind.GENERIC, Map.of("requestId", UUID.randomUUID().toString())));
            }
        });
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/verify")
    public ResponseEntity<Preview> verify(@Valid @RequestBody Verify request) {
        String email = EmailUtils.normalize(request.email());
        guard.checkMailboxDeletion(email, false);
        var user = users.findByEmail(email).filter(u -> u.getErasedAt() == null).orElseThrow(this::invalid);
        if (!otp.verifyErasureOtp(user.getId(), email, request.code())) throw invalid();
        return verifiedPreview(email, user, false);
    }

    @PostMapping("/password")
    public ResponseEntity<Preview> password(@Valid @RequestBody Password request) {
        String email = EmailUtils.normalize(request.email());
        guard.checkMailboxDeletion(email, false);
        var user = users.findByEmail(email).filter(u -> u.getErasedAt() == null && u.getProvider() == AuthProvider.LOCAL
                && Boolean.TRUE.equals(u.getEmailVerified())).orElse(null);
        boolean matches = passwords.matches(request.currentPassword(), user == null ? AuthService.DUMMY_PASSWORD_HASH : user.getPassword());
        if (user == null || !matches) throw invalid();
        return verifiedPreview(email, user, true);
    }

    private ResponseEntity<Preview> verifiedPreview(String email, User user, boolean byPassword) {
        var options = bands.deletionOptions(user.getId());
        UUID proof = UUID.randomUUID();
        String identity = user.getId().toString();
        if (byPassword) identity += ":" + user.getSessionVersion();
        redis.opsForValue().set(key(email, proof), identity, Duration.ofMinutes(10));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Preview(user.getUsername(), proof, options));
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody Confirm request) {
        String email = EmailUtils.normalize(request.email());
        guard.checkMailboxDeletion(email, false);
        String key = key(email, request.proof());
        String identity = redis.opsForValue().get(key);
        if (identity == null) throw invalid();
        String[] fields = identity.split(":", 2);
        Long passwordRevision = fields.length == 2 ? Long.valueOf(fields[1]) : null;
        // Racing confirmations serialize against the same account row. Keep the proof if cleanup rolls back.
        deletion.deleteFromVerifiedWebRequest(UUID.fromString(fields[0]), email, passwordRevision, request.bandDecisions());
        // The committed erasure already invalidates every proof for this immutable ID.
        // Redis expiry also removes it when Redis becomes unavailable after the database commit.
        try { redis.delete(key); }
        catch (org.springframework.dao.DataAccessException unavailable) { /* TTL bounds retention. */ }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static String key(String email, UUID proof) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((email + ":" + proof).getBytes(StandardCharsets.UTF_8));
            return "soundconnect:account-deletion:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.ACCOUNT_DELETION_REAUTH_REQUIRED);
    }
}
