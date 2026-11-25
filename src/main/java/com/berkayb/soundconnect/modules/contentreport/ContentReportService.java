package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentReportService {
    public record Receipt(UUID id, Instant reportedAt) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountDeliveryFence accounts;
    private final ContentReportTargets targets;

    @Transactional
    public Receipt create(UserDetailsImpl actor, ContentReportRequest input) {
        if (actor == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (input == null) throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        var request = input.normalized();
        UUID reporter = actor.getId();
        accounts.requireActive(List.of(reporter));
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_user where id=:id and session_version=:version)
                """, Map.of("id", reporter,"version",actor.getUser().getSessionVersion()),Boolean.class))) {
            throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        }
        // Serialize this reporter only. Both retry recognition and the hourly bound
        // must see the previous committed transaction, including simultaneous submissions.
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(hashtextextended(:key,0))",
                Map.of("key", "content-report:" + reporter), Integer.class);
        var params = new MapSqlParameterSource("reporter", reporter).addValue("key", request.requestId())
                .addValue("target", request.targetId()).addValue("profile", request.profileId()).addValue("kind", request.kind().name())
                .addValue("reason", request.reason().name()).addValue("note", request.description());
        Receipt existing = jdbc.query("""
                select id, reported_at, target_id, profile_id, kind, reason, description from tbl_content_report
                where reporter_id=:reporter and request_id=:key
                """, params, rs -> {
            if (!rs.next()) return null;
            if (!Objects.equals(request.targetId(), rs.getObject("target_id")) || !Objects.equals(request.profileId(), rs.getObject("profile_id")) ||
                    !request.kind().name().equals(rs.getString("kind")) ||
                    !request.reason().name().equals(rs.getString("reason")) ||
                    !Objects.equals(request.description(), rs.getString("description"))) {
                throw new SoundConnectException(ErrorType.CONTENT_REPORT_STATE_CHANGED);
            }
            return new Receipt(rs.getObject("id",UUID.class), rs.getTimestamp("reported_at").toInstant());
        });
        if (existing != null) return existing;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_content_report where reporter_id=:reporter
                    and reported_at > clock_timestamp() - interval '1 hour' limit 1 offset 29)
                """, params, Boolean.class))) throw new SoundConnectException(ErrorType.CONTENT_REPORT_RATE_LIMITED);

        ContentReportEvidence evidence;
        try {
            evidence = targets.capture(reporter, request.kind(), request.targetId(), request.profileId());
        } catch (SoundConnectException unavailable) {
            ErrorType type = unavailable.getErrorType();
            if (type.getHttpStatus().value() == 404 || type == ErrorType.FORBIDDEN_ACCESS ||
                    type == ErrorType.ACCOUNT_DELETED || type == ErrorType.ANNOUNCEMENT_FORBIDDEN) {
                throw new SoundConnectException(ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
            }
            throw unavailable;
        }
        params.addValue("id", UUID.randomUUID()).addValue("label", evidence.targetLabel())
                .addValue("text", evidence.evidenceText()).addValue("parent", evidence.parentText());
        return jdbc.queryForObject("""
                insert into tbl_content_report(id,request_id,reporter_id,kind,target_id,profile_id,reason,description,target_label,evidence_text,parent_text)
                values (:id,:key,:reporter,:kind,:target,:profile,:reason,:note,:label,:text,:parent)
                returning id,reported_at
                """, params, (row,n) -> new Receipt(row.getObject("id", UUID.class), row.getTimestamp("reported_at").toInstant()));
    }
}
