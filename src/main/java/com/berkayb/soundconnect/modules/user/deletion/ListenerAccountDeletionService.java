package com.berkayb.soundconnect.modules.user.deletion;

import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One database commit erases identity and publications; the existing durable media worker removes bytes. */
@Service
@RequiredArgsConstructor
public class ListenerAccountDeletionService {
    private final UserRepository users;
    private final ListenerProfileRepository profiles;
    private final PasswordEncoder passwordEncoder;
    private final ListenerAccountDataCleaner dataCleaner;
    private final MediaAssetService media;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional
    public void deleteSelf(UUID userId, String password, String verifiedGoogleSubject) {
        User user = lockUser(userId);
        if (user.getErasedAt() != null) throw new SoundConnectException(ErrorType.ACCOUNT_DELETED);
        boolean verified = user.getProvider() == AuthProvider.GOOGLE
                ? user.getProviderSubject() != null && user.getProviderSubject().equals(verifiedGoogleSubject)
                : password != null && !password.isBlank() && passwordEncoder.matches(password, user.getPassword());
        if (!verified) throw new SoundConnectException(ErrorType.ACCOUNT_DELETION_REAUTH_REQUIRED);
        eraseLocked(user);
    }

    /** Administrative authorization and owner preservation are enforced by UserService before this call. */
    @Transactional
    public void deleteByAdministrator(UUID userId) {
        User user = lockUser(userId);
        if (user.getErasedAt() == null) eraseLocked(user);
    }

    private User lockUser(UUID userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
        // A previous lookup in the same persistence context must not revive stale identity.
        entityManager.refresh(user);
        return user;
    }

    private void eraseLocked(User user) {
        UUID userId = user.getId();
        Set<String> footprints = users.findExistingPersonalProfileRoleNames(userId);
        Set<String> roles = users.findRoleNamesByUserId(userId);
        if (footprints.stream().anyMatch(role -> !"ROLE_LISTENER".equals(role))
                || roles.stream().anyMatch(role -> !Set.of("ROLE_LISTENER", "ROLE_USER").contains(role))
                || (!roles.contains("ROLE_LISTENER") && !footprints.contains("ROLE_LISTENER"))) {
            throw new SoundConnectException(ErrorType.ACCOUNT_DELETION_UNSUPPORTED_PROFILE);
        }
        // Gameplay takes table/game locks before profile projection locks. Never hold
        // this listener's profile while waiting for one of those shared aggregates.
        dataCleaner.prepareLifecycle(userId);
        var profile = profiles.findByUserIdForUpdate(userId).orElse(null);
        UUID profileId = profile == null ? null : profile.getId();
        if (profile != null) {
            profile.setProfilePictureMediaId(null);
            profiles.saveAndFlush(profile);
            jdbc.update("delete from tbl_profile_media where profile_type = 'LISTENER' and profile_id = ?", profileId);
        }
        user.setProfilePicture(null);
        entityManager.flush();
        // Asset deletion is durable and transaction-aware. Never remove S3 bytes before commit.
        for (UUID assetId : jdbc.queryForList("select id from tbl_media_asset where owner_type = 'USER' and owner_id = ? order by id", UUID.class, userId)) {
            media.delete(assetId, userId, MediaOwnerType.USER, userId);
        }
        if (profileId != null) {
            for (UUID assetId : jdbc.queryForList("select id from tbl_media_asset where owner_type = 'LISTENER_PROFILE' and owner_id = ? order by id", UUID.class, profileId)) {
                media.delete(assetId, userId, MediaOwnerType.LISTENER_PROFILE, profileId);
            }
        }
        entityManager.flush();
        dataCleaner.erase(userId, profileId);
        // Bulk SQL intentionally bypasses Hibernate. Detach obsolete collections before the final identity write.
        entityManager.clear();
        user = lockUser(userId);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        user.setUsername("deleted_" + nonce.substring(0, 22));
        user.setEmail("deleted-" + nonce + "@account.invalid");
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPhone(null);
        user.setDescription(null);
        user.setGender(null);
        user.setCity(null);
        user.setProfilePicture(null);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiry(null);
        user.setUsernameChangedAt(null);
        user.setProviderSubject(null);
        user.setProvider(AuthProvider.LOCAL);
        user.setEmailVerified(false);
        user.setStatus(UserStatus.INACTIVE);
        user.setRoles(new HashSet<>());
        user.setPermissions(new HashSet<>());
        user.setErasedAt(LocalDateTime.now(ZoneOffset.UTC));
        // The existing schema keeps public_code immutable, like the UUID retained by shared
        // history. Public access is revoked by the erased/inactive state, not by changing it.
        users.saveAndFlush(user);
    }
}
