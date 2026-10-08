package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

@Component
@RequiredArgsConstructor
public class CampaignAccess {
    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;

    public void requireAdmin(UUID id) {
        requireActive(id);
        var roles = users.findRoleNamesByUserId(id);
        if (roles.contains("ROLE_LISTENER") || Collections.disjoint(roles, Set.of("ROLE_ADMIN", "ROLE_OWNER"))) {
            throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        }
    }

    public void requireActive(UUID id) {
        if (!active(id)) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
    }

    public boolean active(UUID id) {
        return id != null && Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from tbl_user where id=:id and status='ACTIVE' and email_verified and erased_at is null)
            """, Map.of("id", id), Boolean.class));
    }

    public String profile(UUID id) {
        if (!active(id)) {
            return null;
        }
        var existing = users.findExistingPersonalProfileRoleNames(id);
        var roles = users.findRoleNamesByUserId(id);
        if (existing.size() != 1) {
            return null;
        }
        String role = existing.iterator().next();
        if (!role.startsWith("ROLE_") || !CampaignRules.PROFILES.contains(role.substring(5)) || !roles.contains(role)
                || roles.stream().filter(r -> Set.of("ROLE_MUSICIAN", "ROLE_LISTENER", "ROLE_VENUE", "ROLE_STUDIO",
                        "ROLE_ORGANIZER", "ROLE_PRODUCER").contains(r)).count() != 1) {
            return null;
        }
        return role.substring(5);
    }

    public boolean eligible(UUID id, Audience audience) {
        String profile = profile(id);
        return profile != null && switch (audience.mode()) {
            case ALL -> true;
            case PROFILE_TYPES -> audience.profileTypes().contains(profile);
            case USERS -> audience.userIds().contains(id);
        };
    }

    public UserOption option(UUID id) {
        String profile = profile(id);
        if (profile == null) {
            return null;
        }
        return jdbc.query("select user_name from tbl_user where id=:id", Map.of("id", id),
                (r, n) -> new UserOption(id, r.getString(1), r.getString(1), profile))
                .stream().findFirst().orElse(null);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 5)
    public List<UserOption> search(UUID actor, String query) {
        requireAdmin(actor);
        String term = term(query);
        var ids = jdbc.queryForList("""
            select id from tbl_user where status='ACTIVE' and email_verified and erased_at is null
              and lower(user_name) like :q escape '\\' order by user_name,id limit 100
            """, Map.of("q", "%" + term + "%"), UUID.class);
        return ids.stream().map(this::option).filter(Objects::nonNull).limit(20).toList();
    }

    static String term(String value) {
        if (value == null || value.strip().length() < 2 || value.length() > 80) {
            throw CampaignRules.invalid();
        }
        return value.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
