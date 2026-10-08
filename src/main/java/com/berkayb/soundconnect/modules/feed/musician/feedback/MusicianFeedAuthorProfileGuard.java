package com.berkayb.soundconnect.modules.feed.musician.feedback;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Resolves the same stable public profile identities emitted by feed authors. */
@Component
public class MusicianFeedAuthorProfileGuard {
    private static final Set<ProfileType> ALLOWED = Set.of(ProfileType.MUSICIAN, ProfileType.LISTENER,
            ProfileType.STUDIO, ProfileType.VENUE, ProfileType.BAND);
    private static final String SQL = """
            select exists(
              select 1 from tbl_musician_profile profile join tbl_user account on account.id=profile.user_id
              where :type='MUSICIAN' and profile.id=:profileId and profile.user_id<>:viewerId
                and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              union all
              select 1 from "tbl_listener-profile" profile join tbl_user account on account.id=profile.user_id
              where :type='LISTENER' and profile.id=:profileId and profile.user_id<>:viewerId
                and profile.visibility_mode='STANDARD' and profile.visibility_choice_completed
                and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              union all
              select 1 from tbl_studio_profile profile join tbl_user account on account.id=profile.user_id
              where :type='STUDIO' and profile.id=:profileId and profile.user_id<>:viewerId
                and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              union all
              select 1 from tbl_venues profile join tbl_user account on account.id=profile.owner_id
              where :type='VENUE' and profile.id=:profileId and profile.owner_id<>:viewerId
                and profile.status='APPROVED'
                and account.status='ACTIVE' and account.email_verified and account.erased_at is null
              union all
              select 1 from tbl_band profile
              where :type='BAND' and profile.id=:profileId
                and exists(select 1 from tbl_band_member active_member join tbl_user account
                    on account.id=active_member.user_id and account.status='ACTIVE'
                    and account.email_verified and account.erased_at is null
                    where active_member.band_id=profile.id and active_member.status='ACTIVE')
                and not exists(select 1 from tbl_band_member viewer_member
                    where viewer_member.band_id=profile.id and viewer_member.user_id=:viewerId
                      and viewer_member.status='ACTIVE')
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MusicianFeedAuthorProfileGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String requireEligibleNotOwned(UUID viewerId, String profileType, UUID profileId) {
        String normalized = normalize(profileType, profileId);
        Boolean eligible = jdbc.queryForObject(SQL, new MapSqlParameterSource()
                .addValue("viewerId", viewerId).addValue("type", normalized)
                .addValue("profileId", profileId), Boolean.class);
        if (!Boolean.TRUE.equals(eligible)) throw invalid();
        return normalized;
    }

    public String normalize(String profileType, UUID profileId) {
        if (profileType == null || profileId == null) throw invalid();
        try {
            ProfileType type = ProfileType.valueOf(profileType.strip().toUpperCase(Locale.ROOT));
            if (!ALLOWED.contains(type)) throw invalid();
            return type.name();
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.BAD_REQUEST);
    }
}
