package com.berkayb.soundconnect.modules.user.support;

import com.berkayb.soundconnect.modules.user.block.BlockStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Fresh scalar locks fence erasure even when a session holds an old User entity. */
@Component
@RequiredArgsConstructor
public class AccountDeliveryFence {
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(Collection<UUID> userIds) {
        var ids=normalized(userIds);
        var accounts=lock(ids);
        if (accounts.values().stream().anyMatch(Account::erased))
            throw new SoundConnectException(ErrorType.ACCOUNT_DELETED);
        if (accounts.size()!=ids.size() || accounts.values().stream().anyMatch(value -> !value.active() || !value.verified()))
            // An unavailable peer is not an authentication failure of the
            // current caller; a 401 would unnecessarily invalidate their session.
            throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
    }

    /** Existing shared history remains readable to its active participant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireConversationReader(UUID actorId, Collection<UUID> participants) {
        var ids=normalized(participants);
        var values=lock(ids);
        var actor=values.get(actorId);
        if(actor!=null && actor.erased()) throw new SoundConnectException(ErrorType.ACCOUNT_DELETED);
        if(actor==null || !actor.active() || !actor.verified()) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if(values.size()!=ids.size()) throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canDeliver(UUID recipient, Collection<UUID> referencedIds) {
        if (recipient==null) return false;
        var ids=new LinkedHashSet<UUID>(); ids.add(recipient);
        if(referencedIds!=null) ids.addAll(referencedIds);
        var accounts=lock(normalized(ids));
        return accounts.containsKey(recipient) && !accounts.get(recipient).suspended()
                && accounts.values().stream().noneMatch(Account::erased);
    }

    public BlockStatus blockStatus(UUID actor, UUID target) {
        if (actor == null || target == null || actor.equals(target)) return BlockStatus.AVAILABLE;
        return jdbc.queryForObject("""
                select coalesce(bool_or(blocker_id=:actor),false) as mine, count(*)>0 as either
                from tbl_user_block
                where unblocked_at is null and ((blocker_id=:actor and blocked_id=:target)
                       or (blocker_id=:target and blocked_id=:actor))
                """, Map.of("actor",actor,"target",target),
                (rs, row) -> new BlockStatus(rs.getBoolean("mine"), rs.getBoolean("either")));
    }

    /** Serialize a relationship change with contact and late delivery, even before either row exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInteraction(UUID first, UUID second) {
        String key = first.compareTo(second) <= 0 ? first + ":" + second : second + ":" + first;
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))",
                Map.of("key", "user-contact:" + key), Integer.class);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean interactionBlockedForUpdate(UUID first, UUID second) {
        lockInteraction(first, second);
        return blockStatus(first, second).interactionBlocked();
    }

    /** A queued event from before a block must stay suppressed if the two accounts reconnect later. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean contactEventSuppressed(UUID first, UUID second, java.time.Instant occurredAt) {
        if (first == null || second == null || first.equals(second)) return false;
        lockInteraction(first, second);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user_block
                   where ((blocker_id=:first and blocked_id=:second) or (blocker_id=:second and blocked_id=:first))
                     and (unblocked_at is null or unblocked_at >= :occurred))
                """, Map.of("first",first, "second",second, "occurred",java.sql.Timestamp.from(
                        occurredAt == null ? java.time.Instant.EPOCH : occurredAt)), Boolean.class));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireInteractionAllowed(UUID actor, UUID target) {
        if (actor == null || target == null || actor.equals(target)) return;
        if (interactionBlockedForUpdate(actor, target))
            throw new SoundConnectException(ErrorType.USER_INTERACTION_UNAVAILABLE);
    }

    private Map<UUID,Account> lock(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.query("""
                select id, erased_at is not null as erased, status='ACTIVE' and not access_suspended as active,
                       email_verified, access_suspended
                from tbl_user where id in (:ids) order by id for share
                """,Map.of("ids",ids),rs -> {
            Map<UUID,Account> result=new LinkedHashMap<>();
            while(rs.next()) result.put(rs.getObject("id",UUID.class),new Account(rs.getBoolean("erased"),
                    rs.getBoolean("active"),rs.getBoolean("email_verified"),rs.getBoolean("access_suspended")));
            return result;
        });
    }

    private List<UUID> normalized(Collection<UUID> ids) {
        if(ids==null || ids.isEmpty()) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
    private record Account(boolean erased,boolean active,boolean verified,boolean suspended) { }
}
