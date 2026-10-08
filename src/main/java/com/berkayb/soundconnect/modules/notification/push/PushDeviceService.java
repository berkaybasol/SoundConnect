package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushDeviceService {
    public static final long MAX_CLIENT_REVISION = 9_007_199_254_740_991L;
    public enum Platform { ANDROID, IOS }
    public enum Permission { AUTHORIZED, PROVISIONAL, DENIED, NOT_DETERMINED }
    public record Registration(@NotBlank @Size(max=4096) String token, @NotNull Platform platform,
                               @NotNull Permission permission, @Size(max=80) String appVersion,
                               @NotNull @Min(1) @Max(MAX_CLIENT_REVISION) Long clientRevision,
            @Pattern(regexp="ANDROID_DM_V1|ANDROID_NATIVE_V2|ANDROID_NATIVE_V3|ANDROID_NATIVE_V4|ANDROID_NATIVE_V5|ANDROID_NATIVE_V6|ANDROID_NATIVE_V7|ANDROID_NATIVE_V8|ANDROID_NATIVE_V9|ANDROID_NATIVE_V10|ANDROID_NATIVE_V11") String presentationVersion) {
        @Override public String toString() { return "Registration[redacted]"; }
    }
    public record Preferences(@NotNull Boolean enabled, @NotNull @Size(max=30) Set<@NotBlank @Size(max=40) String> disabledCategories) { }
    private static final Set<String> CATEGORIES = Arrays.stream(NotificationType.values())
            .map(NotificationType::getCategory).collect(Collectors.toUnmodifiableSet());
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;
    private final PushTokenCipher cipher;
    private final PushProperties properties;
    private final Clock clock;

    public PushDeviceService(NamedParameterJdbcTemplate jdbc, AccountDeliveryFence accounts, PushTokenCipher cipher,
                             PushProperties properties, @Qualifier("pushClock") Clock clock) {
        this.jdbc=jdbc; this.accounts=accounts; this.cipher=cipher; this.properties=properties; this.clock=clock;
    }

    @Transactional
    public void register(UUID userId, UUID installationId, Registration request) {
        accounts.requireActive(List.of(userId));
        registerChecked(userId, installationId, request, null);
    }

    @Transactional
    public void registerApplication(UUID userId, UUID applicationId, UUID installationId, Registration request) {
        new VenueApplicationDeviceScope(jdbc).requireAllowed(userId, applicationId);
        if (request.platform()!=Platform.ANDROID || !VenueApplicationPushPresentation.supportsApplication(request.presentationVersion()))
            throw new SoundConnectException(ErrorType.MALFORMED_REQUEST);
        registerChecked(userId, installationId, request, applicationId);
    }

    private void registerChecked(UUID userId, UUID installationId, Registration request, UUID applicationId) {
        requireRevision(request.clientRevision());
        if(request.presentationVersion()!=null && (request.platform()!=Platform.ANDROID
                || !VenuePushPresentation.supportsDm(request.presentationVersion())))
            throw new SoundConnectException(ErrorType.MALFORMED_REQUEST);
        if (request.token().isBlank() || request.token().chars().anyMatch(Character::isWhitespace))
            throw new SoundConnectException(ErrorType.MALFORMED_REQUEST);
        String hash = PushTokenCipher.hash(request.token());
        // Serialize registrations for this installation and token across accounts/nodes.
        // Sorted advisory keys avoid cross-registration deadlocks. No raw token in locks/logs.
        var keys = List.of("push-install:" + installationId, "push-token:" + hash,"push-user:" + userId).stream().sorted().toList();
        for (String key : keys) jdbc.getJdbcTemplate().queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, key);
        if (alreadyObserved(installationId, request.clientRevision())) return;
        requireStorageCapacity(userId, installationId, true);
        Map<String,Object> args = new HashMap<>();
        args.put("user", userId); args.put("installation", installationId); args.put("hash", hash);
        args.put("now", Timestamp.from(clock.instant()));
        args.put("stale", Timestamp.from(clock.instant().minus(properties.getDeviceStaleAfter())));
        // Move a refreshed/reinstalled token out of its prior installation. Version fences
        // invalidate previously queued deliveries without exposing the new owner's identity.
        jdbc.update("""
                update tbl_push_device set token_hash=null, token_ciphertext=null, revoked_at=:now, generation=generation+1
                where token_hash=:hash and installation_id<>:installation
                """, args);
        Long active = jdbc.queryForObject("""
                select count(*) from tbl_push_device where user_id=:user and revoked_at is null
                and last_seen_at>:stale and installation_id<>:installation
                """, args, Long.class);
        if (active != null && active >= properties.getMaxDevicesPerUser())
            throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        args.put("encrypted", cipher.encrypt(request.token()));
        args.put("platform", request.platform().name()); args.put("permission", request.permission().name());
        args.put("version", request.appVersion());
        args.put("revision", request.clientRevision());
        args.put("presentation",request.presentationVersion());
        args.put("application",applicationId);
        jdbc.update("""
                insert into tbl_push_device(installation_id,user_id,token_hash,token_ciphertext,generation,platform,permission,app_version,last_seen_at,client_revision,presentation_version,application_scope_id)
                values(:installation,:user,:hash,:encrypted,1,:platform,:permission,:version,:now,:revision,:presentation,:application)
                on conflict(installation_id) do update set
                  generation=tbl_push_device.generation + case when tbl_push_device.user_id<>excluded.user_id
                    or tbl_push_device.permission<>excluded.permission or tbl_push_device.revoked_at is not null
                    or tbl_push_device.application_scope_id is distinct from excluded.application_scope_id then 1 else 0 end,
                  user_id=excluded.user_id, token_hash=excluded.token_hash, token_ciphertext=excluded.token_ciphertext,
                  platform=excluded.platform, permission=excluded.permission, app_version=excluded.app_version,
                  last_seen_at=excluded.last_seen_at, revoked_at=null, client_revision=excluded.client_revision,
                  presentation_version=excluded.presentation_version, application_scope_id=excluded.application_scope_id
                """, args);
    }

    @Transactional
    public void revoke(UUID userId, UUID installationId, Long clientRevision) {
        accounts.requireActive(List.of(userId));
        revokeChecked(userId, installationId, clientRevision);
    }

    @Transactional
    public void revokeApplication(UUID userId, UUID applicationId, UUID installationId, Long clientRevision) {
        new VenueApplicationDeviceScope(jdbc).requireAllowed(userId, applicationId);
        revokeChecked(userId, installationId, clientRevision);
    }

    private void revokeChecked(UUID userId, UUID installationId, Long clientRevision) {
        requireRevision(clientRevision);
        for (String key : List.of("push-install:" + installationId,"push-user:" + userId).stream().sorted().toList())
            jdbc.getJdbcTemplate().queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",Object.class,key);
        if (alreadyObserved(installationId, clientRevision)) return;
        requireStorageCapacity(userId, installationId, false);
        // Retain an absent-device tombstone too: logout may arrive before the first register.
        // An obsolete owner's logout advances the installation-wide revision but cannot revoke
        // its current owner's token. Revoked tombstone platform is inert until registration.
        jdbc.update("""
                insert into tbl_push_device(installation_id,user_id,generation,platform,permission,last_seen_at,revoked_at,client_revision)
                values(:installation,:user,1,'ANDROID','DENIED',:now,:now,:revision)
                on conflict(installation_id) do update set
                  client_revision=excluded.client_revision,
                  revoked_at=case when tbl_push_device.user_id=:user then :now else tbl_push_device.revoked_at end,
                  token_hash=case when tbl_push_device.user_id=:user then null else tbl_push_device.token_hash end,
                  token_ciphertext=case when tbl_push_device.user_id=:user then null else tbl_push_device.token_ciphertext end,
                  presentation_version=case when tbl_push_device.user_id=:user then null else tbl_push_device.presentation_version end,
                  generation=tbl_push_device.generation + case when tbl_push_device.user_id=:user
                    and tbl_push_device.revoked_at is null then 1 else 0 end
                """, Map.of("user",userId,"installation",installationId,"revision",clientRevision,"now",Timestamp.from(clock.instant())));
    }

    private boolean alreadyObserved(UUID installationId,long revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tbl_push_device where installation_id=:installation and client_revision>=:revision)",
                Map.of("installation",installationId,"revision",revision),Boolean.class));
    }

    private void requireStorageCapacity(UUID userId,UUID installationId,boolean claimsOwnership) {
        // Taking an existing installation from another account also grows this owner's rows.
        // Revoke never transfers ownership, so an obsolete owner may still advance its fence.
        Boolean addsRow=jdbc.queryForObject("""
                select not exists(select 1 from tbl_push_device where installation_id=:installation
                  and (not :claimsOwnership or user_id=:user))
                """, Map.of("installation",installationId,"user",userId,"claimsOwnership",claimsOwnership),Boolean.class);
        if(Boolean.TRUE.equals(addsRow)) {
            Long stored=jdbc.queryForObject("select count(*) from tbl_push_device where user_id=:user",Map.of("user",userId),Long.class);
            if(stored!=null && stored>=properties.getMaxStoredDevicesPerUser()) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        }
    }

    private static void requireRevision(Long revision) {
        if(revision==null || revision<1 || revision>MAX_CLIENT_REVISION) throw new SoundConnectException(ErrorType.MALFORMED_REQUEST);
    }

    @Transactional(readOnly = true)
    public Preferences preferences(UUID userId) {
        var rows = jdbc.query("select enabled, disabled_categories from tbl_push_preference where user_id=:user",
                Map.of("user",userId), (rs,index) -> new Preferences(rs.getBoolean("enabled"),
                        Set.copyOf(Arrays.asList((String[])rs.getArray("disabled_categories").getArray()))));
        return rows.isEmpty() ? new Preferences(true, Set.of()) : rows.getFirst();
    }

    @Transactional
    public Preferences updatePreferences(UUID userId, Preferences preferences) {
        accounts.requireActive(List.of(userId));
        if (!CATEGORIES.containsAll(preferences.disabledCategories())) throw new SoundConnectException(ErrorType.MALFORMED_REQUEST);
        var args = new MapSqlParameterSource().addValue("user",userId).addValue("enabled",preferences.enabled())
                .addValue("categories", "{" + String.join(",",new TreeSet<>(preferences.disabledCategories())) + "}")
                .addValue("now",Timestamp.from(clock.instant()));
        jdbc.update("""
                insert into tbl_push_preference(user_id,enabled,disabled_categories,updated_at)
                values(:user,:enabled,cast(:categories as text[]),:now)
                on conflict(user_id) do update set enabled=excluded.enabled,
                    disabled_categories=excluded.disabled_categories, updated_at=excluded.updated_at
                """, args);
        return preferences;
    }
}
