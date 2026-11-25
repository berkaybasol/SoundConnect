package com.berkayb.soundconnect.modules.user.service;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAccountAccessService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPanelReadAccess access;
    private final AdminAccountDirectory directory;
    private final ApplicationEventPublisher events;

    public record DecisionRequest(Boolean suspended, Long expectedRevision, UUID requestId, String reason) {}
    public record DecisionReceipt(boolean suspended, long revision) {}
    private record Previous(UUID target, boolean suspended, long revision, String reason) {}

    @Transactional
    public DecisionReceipt decide(UserDetailsImpl actor, UUID target, DecisionRequest request) {
        if (actor == null || actor.getId() == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (actor.getId().equals(target)) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        String reason = validatedReason(request);
        var args = new MapSqlParameterSource(Map.of("actor", actor.getId(), "target", target,
                "request", request.requestId(), "reason", reason, "suspended", request.suspended(),
                "expected", request.expectedRevision()));

        // Use the same stable account ordering as the admin credential operations; re-read grants under these locks.
        jdbc.queryForList("select id from tbl_user where id in (:actor, :target) order by id for update", args, UUID.class);
        access.require(actor, PermissionEnum.MANAGE_USERS);
        if (!directory.canChangeTarget(actor.getId(), target)) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        var previous = jdbc.query("""
                select target_id, suspended, revision, reason from tbl_account_access_decision
                where actor_id=:actor and request_id=:request
                """, args, (row, i) -> new Previous(row.getObject("target_id", UUID.class), row.getBoolean("suspended"),
                row.getLong("revision"), row.getString("reason"))).stream().findFirst();
        if (previous.isPresent()) {
            Previous saved = previous.get();
            if (!saved.target().equals(target) || saved.suspended() != request.suspended()
                    || saved.revision() - 1 != request.expectedRevision() || !saved.reason().equals(reason)) {
                throw new SoundConnectException(ErrorType.ACCOUNT_ACCESS_CONFLICT);
            }
            return new DecisionReceipt(saved.suspended(), saved.revision());
        }

        var versions = jdbc.queryForList("""
                update tbl_user set access_suspended=:suspended, access_revision=access_revision+1,
                     session_version=session_version+case when :suspended then 1 else 0 end
                where id=:target and access_revision=:expected and access_suspended<>:suspended
                returning session_version
                """, args, Long.class);
        if (versions.isEmpty()) throw new SoundConnectException(ErrorType.ACCOUNT_ACCESS_CONFLICT);
        long revision = Math.incrementExact(request.expectedRevision());
        args.addValue("revision", revision);
        jdbc.update("""
                insert into tbl_account_access_decision
                    (target_id, revision, actor_id, actor_username, request_id, was_suspended, suspended, reason)
                select :target, :revision, id, user_name, :request, not :suspended, :suspended, :reason
                from tbl_user where id=:actor
                """, args);
        if (request.suspended()) {
            jdbc.update("""
                    update tbl_push_device set revoked_at=current_timestamp, token_hash=null, token_ciphertext=null,
                        generation=generation+1 where user_id=:target and revoked_at is null
                    """, args);
        }
        events.publishEvent(new AccountAccessChanged(target, request.suspended(), versions.getFirst()));
        return new DecisionReceipt(request.suspended(), revision);
    }

    private static String validatedReason(DecisionRequest request) {
        if (request == null || request.requestId() == null || request.suspended() == null
                || request.expectedRevision() == null || request.expectedRevision() < 0 || request.reason() == null) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        String reason = request.reason().strip();
        int length = reason.codePointCount(0, reason.length());
        if (length < 5 || length > 500 || reason.indexOf(0) >= 0) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        return reason;
    }
}
