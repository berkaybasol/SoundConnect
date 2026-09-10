package com.berkayb.soundconnect.modules.user.support;

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
        return accounts.containsKey(recipient) && accounts.values().stream().noneMatch(Account::erased);
    }

    private Map<UUID,Account> lock(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.query("""
                select id, erased_at is not null as erased, status='ACTIVE' as active, email_verified
                from tbl_user where id in (:ids) order by id for share
                """,Map.of("ids",ids),rs -> {
            Map<UUID,Account> result=new LinkedHashMap<>();
            while(rs.next()) result.put(rs.getObject("id",UUID.class),new Account(rs.getBoolean("erased"),
                    rs.getBoolean("active"),rs.getBoolean("email_verified")));
            return result;
        });
    }

    private List<UUID> normalized(Collection<UUID> ids) {
        if(ids==null || ids.isEmpty()) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
    private record Account(boolean erased,boolean active,boolean verified) { }
}
