package com.berkayb.soundconnect.modules.user.block;

import com.berkayb.soundconnect.modules.follow.repository.FollowRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserBlockService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;
    private final FollowRepository follows;

    public BlockStatus status(UUID actor, UUID target) {
        return accounts.blockStatus(actor, target);
    }

    public boolean blockedEitherWay(UUID first, UUID second) {
        return status(first, second).interactionBlocked();
    }

    public boolean hasBlocks(UUID viewer) {
        return viewer != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user_block where unblocked_at is null
                     and (blocker_id=:viewer or blocked_id=:viewer))
                """, Map.of("viewer",viewer), Boolean.class));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireInteractionAllowed(UUID first, UUID second) {
        accounts.requireInteractionAllowed(first, second);
    }

    public Set<UUID> blockedAmong(UUID viewer, Collection<UUID> candidates) {
        if (viewer == null || candidates == null || candidates.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.queryForList("""
                select blocked_id from tbl_user_block where unblocked_at is null and blocker_id=:viewer and blocked_id in (:owners)
                union
                select blocker_id from tbl_user_block where unblocked_at is null and blocked_id=:viewer and blocker_id in (:owners)
                """, Map.of("viewer",viewer, "owners",candidates), UUID.class));
    }

    @Transactional
    public BlockStatus block(UUID actor, UUID target) {
        if (actor.equals(target)) throw new SoundConnectException(ErrorType.BAD_REQUEST);
        accounts.requireActive(List.of(actor, target));
        accounts.lockInteraction(actor, target);
        int changed = jdbc.update("""
                insert into tbl_user_block (blocker_id, blocked_id) values (:actor, :target)
                on conflict (blocker_id, blocked_id) do update set created_at=clock_timestamp(), unblocked_at=null
                where tbl_user_block.unblocked_at is not null
                """, Map.of("actor",actor,"target",target));
        if (changed > 0) follows.deleteBetween(actor, target);
        return new BlockStatus(true, true);
    }

    @Transactional
    public BlockStatus unblock(UUID actor, UUID target) {
        if (actor.equals(target)) throw new SoundConnectException(ErrorType.BAD_REQUEST);
        accounts.lockInteraction(actor, target);
        jdbc.update("""
                update tbl_user_block set unblocked_at=clock_timestamp()
                where blocker_id=:actor and blocked_id=:target and unblocked_at is null
                """, Map.of("actor",actor,"target",target));
        return status(actor, target);
    }
}
