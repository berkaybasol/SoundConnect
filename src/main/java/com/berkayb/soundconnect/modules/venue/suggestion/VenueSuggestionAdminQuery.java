package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.role.enums.PermissionEnum;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class VenueSuggestionAdminQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPanelReadAccess access;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Row> list(UserDetailsImpl actor, int page, int size) {
        access.require(actor, PermissionEnum.MANAGE_VENUES);
        if (page < 0 || page > 1000 || size < 1 || size > 50) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        var pageable = PageRequest.of(page, size);
        var suggestions = jdbc.query("""
                select venue_name, city_name, district_name, live_music
                from tbl_venue_suggestion
                order by created_at desc, id desc
                limit :size offset :offset
                """, Map.of("size", size, "offset", pageable.getOffset()),
                (rs, index) -> new Row(rs.getString("venue_name"), rs.getString("city_name"),
                        rs.getString("district_name"), VenueSuggestionRequest.LiveMusic.valueOf(rs.getString("live_music"))));
        return PageResponse.from(PageableExecutionUtils.getPage(suggestions, pageable,
                () -> jdbc.getJdbcTemplate().queryForObject("select count(*) from tbl_venue_suggestion", Long.class)));
    }

    public record Row(String venueName, String cityName, String districtName, VenueSuggestionRequest.LiveMusic liveMusic) { }
}
