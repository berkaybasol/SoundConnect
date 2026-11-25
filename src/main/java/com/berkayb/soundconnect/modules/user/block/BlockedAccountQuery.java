package com.berkayb.soundconnect.modules.user.block;

import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BlockedAccountQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final GhostListenerIdentityBatchResolver identities;

    public record BlockedAccount(UUID id, String username) {}
    public record BlockPage(List<BlockedAccount> content, int number, int size, boolean hasNext) {}

    @Transactional
    public BlockPage list(UUID actor, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        var ids = jdbc.queryForList("""
                select b.blocked_id from tbl_user_block b join tbl_user u on u.id=b.blocked_id
                where b.blocker_id=:actor and b.unblocked_at is null and u.erased_at is null
                order by b.created_at desc, b.blocked_id limit :limit offset :offset
                """, Map.of("actor",actor, "limit",size + 1, "offset",(long) page * size), UUID.class);
        boolean more = ids.size() > size;
        var visible = more ? ids.subList(0, size) : ids;
        var names = identities.resolveCurrentCanonicalNames(visible);
        var content = visible.stream()
                .map(id -> new BlockedAccount(id, names.getOrDefault(id, "Kullanıcı"))).toList();
        return new BlockPage(content, page, size, more);
    }
}
