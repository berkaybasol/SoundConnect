package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberAnalyticsPreferenceService {
    public static final String POLICY_VERSION = "2026-11-26-r3";
    public enum Decision { UNDECIDED, GRANTED, DENIED }
    public record Change(Decision decision, String policyVersion, Long expectedRevision) {}
    public record Preference(Decision decision, UUID analyticsUserId, String policyVersion, long revision, Instant changedAt) {}
    public record FirstAction(String actionType, String profileType, Instant occurredAt) {}

    private static final String CURRENT_ACCOUNT = """
            where u.id=:userId and u.session_version=:sessionVersion and u.erased_at is null
              and u.status='ACTIVE' and u.email_verified and not u.access_suspended
            """;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Preference get(UserDetailsImpl actor) {
        return read(account(actor));
    }

    @Transactional
    public Preference change(UserDetailsImpl actor, Change change) {
        if (change == null || change.decision() == null || change.decision() == Decision.UNDECIDED
                || change.expectedRevision() == null || change.expectedRevision() < 0
                || change.policyVersion() == null || change.policyVersion().isBlank() || change.policyVersion().length() > 32) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        if (change.decision() == Decision.GRANTED && !POLICY_VERSION.equals(change.policyVersion())) {
            throw new SoundConnectException(ErrorType.ANALYTICS_PREFERENCE_CONFLICT);
        }
        var account = account(actor);
        // Both devices and the erasure path serialize on the account, including the first decision.
        if (jdbc.queryForList("select u.id from tbl_user u " + CURRENT_ACCOUNT + " for update", account, UUID.class).isEmpty()) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
        Preference current = read(account);
        if (current.decision() == change.decision()) return current;
        if (change.expectedRevision() != current.revision()) {
            throw new SoundConnectException(ErrorType.ANALYTICS_PREFERENCE_CONFLICT);
        }
        var params = new MapSqlParameterSource(account)
                .addValue("decision", change.decision().name())
                .addValue("version", change.policyVersion())
                .addValue("revision", current.revision() + 1)
                .addValue("analyticsId", change.decision() == Decision.GRANTED ? UUID.randomUUID() : null);
        jdbc.update("""
                insert into tbl_member_analytics_preference(user_id, decision, policy_version, revision, changed_at, analytics_user_id)
                values (:userId, :decision, :version, :revision, clock_timestamp(), :analyticsId)
                on conflict (user_id) do update set decision=excluded.decision, policy_version=excluded.policy_version,
                    revision=excluded.revision, changed_at=excluded.changed_at, analytics_user_id=excluded.analytics_user_id
                """, params);
        return read(account);
    }

    private static Map<String, ?> account(UserDetailsImpl actor) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        return Map.of("userId", actor.getId(), "sessionVersion", actor.getUser().getSessionVersion());
    }

    @Transactional
    public FirstAction claimFirstAction(UserDetailsImpl actor, UUID analyticsUserId) {
        if (analyticsUserId == null) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        var params = new MapSqlParameterSource(account(actor)).addValue("analyticsId", analyticsUserId)
                .addValue("policyVersion", POLICY_VERSION);
        if (jdbc.queryForList("select u.id from tbl_user u " + CURRENT_ACCOUNT + " for share", params, UUID.class).isEmpty()) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
        return jdbc.query("""
                update tbl_analytics_first_action a set ga_claimed_at=clock_timestamp()
                from tbl_member_analytics_preference p
                join tbl_analytics_membership_period m on m.user_id=p.user_id and m.state='ACTIVE'
                where a.user_id=:userId and a.ga_claimed_at is null and a.analytics_user_id=:analyticsId
                  and m.profile_type=a.profile_type
                  and p.user_id=a.user_id and p.decision='GRANTED' and p.policy_version=:policyVersion
                  and p.analytics_user_id=a.analytics_user_id
                returning a.action_type, a.profile_type, a.occurred_at
                """, params, (row, i) -> new FirstAction(row.getString("action_type"), row.getString("profile_type"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant())).stream().findFirst().orElse(null);
    }

    private Preference read(Map<String, ?> params) {
        return jdbc.query("""
                select p.decision, p.policy_version, p.revision, p.changed_at, p.analytics_user_id from tbl_user u
                left join tbl_member_analytics_preference p on p.user_id=u.id
                """ + CURRENT_ACCOUNT, params, (row, i) -> {
            String decision = row.getString("decision");
            long revision = row.getLong("revision");
            if (decision == null || ("GRANTED".equals(decision) && !POLICY_VERSION.equals(row.getString("policy_version")))) {
                return new Preference(Decision.UNDECIDED, null, POLICY_VERSION, revision, null);
            }
            return new Preference(Decision.valueOf(decision), row.getObject("analytics_user_id", UUID.class), POLICY_VERSION,
                    revision, row.getObject("changed_at", OffsetDateTime.class).toInstant());
        }).stream().findFirst().orElseThrow(() -> new SoundConnectException(ErrorType.UNAUTHORIZED));
    }
}
