package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class ReportedProfileShares {
    private final CommentTargetAccessGuard access;
    private final JdbcTemplate jdbc;

    ContentReportEvidence capture(UUID actor, ContentReportKind kind, UUID id) {
        Publication publication = publication(kind);
        try {
            access.requireReadable(actor, publication.target, id);
        } catch (SoundConnectException denied) {
            if (denied.getErrorType() == ErrorType.ENGAGEMENT_NOT_FOUND) throw unavailable();
            throw denied;
        }
        Share share = read(publication, id).orElseThrow(ReportedProfileShares::unavailable);
        if (share.owner().equals(actor)) throw unavailable();
        return share.evidence();
    }

    boolean removable(ContentReportKind kind, UUID id, ContentReportEvidence evidence) {
        return read(publication(kind), id).filter(share -> share.evidence().equals(evidence)).isPresent();
    }

    void remove(ContentReportKind kind, UUID id, ContentReportEvidence evidence) {
        Publication publication = publication(kind);
        UUID owner = read(publication, id).orElseThrow(ReportedProfileShares::changed).owner();
        // Publisher edits take this account lock before reading their publication.
        // Re-read afterwards so an edit or re-publication cannot be removed on stale evidence.
        if (jdbc.queryForList("select id from tbl_user where id=? for update", UUID.class, owner).isEmpty()) {
            throw changed();
        }
        Share current = read(publication, id).orElseThrow(ReportedProfileShares::changed);
        if (!current.evidence().equals(evidence) || jdbc.update(publication.removeSql, id) != 1) throw changed();
    }

    private Optional<Share> read(Publication publication, UUID id) {
        return jdbc.query(publication.readSql, (rs, index) -> {
            String note = rs.getString("note");
            var evidence = new ContentReportEvidence(publication.label,
                    note == null ? "Paylaşım notu yok." : "Paylaşım notu: " + note,
                    rs.getString("source_label"));
            return new Share(rs.getObject("owner_id", UUID.class), evidence);
        }, id).stream().findFirst();
    }

    private record Share(UUID owner, ContentReportEvidence evidence) {}

    private static Publication publication(ContentReportKind kind) {
        return switch (kind) {
            case EVENT_PROFILE_SHARE -> Publication.EVENT;
            case OVERTHINKING_PROFILE_SHARE -> Publication.OVERTHINKING;
            case TABLEGROUP_PROFILE_SHARE -> Publication.TABLE;
            default -> throw new IllegalArgumentException("Expected a profile share");
        };
    }

    private static SoundConnectException changed() {
        return new SoundConnectException(ErrorType.CONTENT_REPORT_STATE_CHANGED);
    }

    private static SoundConnectException unavailable() {
        return new SoundConnectException(ErrorType.CONTENT_REPORT_TARGET_UNAVAILABLE);
    }

    private enum Publication {
        EVENT(EngagementTargetType.EVENT_POST, "Etkinlik paylaşımı", """
                select s.user_id as owner_id, s.note, e.title as source_label
                from tbl_event_audience_intent s join tbl_event e on e.id=s.event_id
                where s.post_id=? and s.published_on_profile
                """, """
                update tbl_event_audience_intent set published_on_profile=false, post_id=null,
                    note=null, published_at=null, updated_at=current_timestamp, version=version+1
                where post_id=? and published_on_profile
                """),
        OVERTHINKING(EngagementTargetType.OVERTHINKING_PROFILE_SHARE, "Overthinking paylaşımı", """
                select s.owner_user_id as owner_id, s.note, p.title as source_label
                from tbl_overthinking_profile_share s join tbl_overthinking_post p on p.id=s.source_post_id
                where s.id=?
                """, "delete from tbl_overthinking_profile_share where id=?"),
        TABLE(EngagementTargetType.TABLE_GROUP_POST, "Masa paylaşımı", """
                select s.owner_user_id as owner_id, s.note,
                    case when t.report_removed_at is not null then 'Bu masa yayından kaldırıldı.'
                         else coalesce(s.final_source->>'description', t.description) end as source_label
                from tbl_table_group_profile_share s join tbl_table_group t on t.id=s.table_group_id
                where s.id=?
                """, "delete from tbl_table_group_profile_share where id=?");

        final EngagementTargetType target;
        final String label;
        final String readSql;
        final String removeSql;

        Publication(EngagementTargetType target, String label, String readSql, String removeSql) {
            this.target = target;
            this.label = label;
            this.readSql = readSql;
            this.removeSql = removeSql;
        }
    }
}
