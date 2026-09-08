package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Small explicit SQL transactions also fence sender state before/after external side effects. */
@Repository
public class VenueSuggestionStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public VenueSuggestionStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    public void accept(VenueSuggestionRequest request, String name, String payloadHash, String dedupeKey,
            List<String> recipients) {
        transaction.executeWithoutResult(transactionStatus -> {
            var params = new HashMap<String, Object>();
            params.put("requestId", request.requestId()); params.put("hash", payloadHash);
            params.put("dedupe", dedupeKey); params.put("city", request.cityId()); params.put("district", request.districtId());
            jdbc.update("""
                    INSERT INTO tbl_venue_suggestion_request(request_id,payload_hash)
                    VALUES (:requestId,:hash) ON CONFLICT (request_id) DO NOTHING
                    """, params);
            var receipt = jdbc.queryForMap("SELECT payload_hash,suggestion_id FROM tbl_venue_suggestion_request WHERE request_id=:requestId FOR UPDATE", params);
            if (!payloadHash.equals(receipt.get("payload_hash"))) throw new SoundConnectException(ErrorType.VENUE_SUGGESTION_CONFLICT);
            if (receipt.get("suggestion_id") != null) return;
            var locations = jdbc.queryForList("""
                    SELECT city.name AS city_name,district.name AS district_name
                    FROM tbl_district district JOIN tbl_city city ON city.id=district.city_id
                    WHERE district.id=:district AND city.id=:city
                    """, params);
            if (locations.size() != 1) throw VenueSuggestionNormalizer.invalid();
            jdbc.update("INSERT INTO tbl_venue_suggestion_dedupe(dedupe_key) VALUES (:dedupe) ON CONFLICT DO NOTHING", params);
            var dedupe = jdbc.queryForMap("""
                    SELECT suggestion_id,expires_at > CURRENT_TIMESTAMP AS recent
                    FROM tbl_venue_suggestion_dedupe WHERE dedupe_key=:dedupe FOR UPDATE
                    """, params);
            UUID suggestionId = (UUID) dedupe.get("suggestion_id");
            if (suggestionId == null || !Boolean.TRUE.equals(dedupe.get("recent"))) {
                suggestionId = UUID.randomUUID();
                params.put("id", suggestionId); params.put("name", name); params.put("music", request.liveMusic().name());
                params.put("cityName", locations.getFirst().get("city_name"));
                params.put("districtName", locations.getFirst().get("district_name"));
                jdbc.update("""
                        INSERT INTO tbl_venue_suggestion(id,venue_name,city_id,district_id,city_name,district_name,live_music)
                        VALUES (:id,:name,:city,:district,:cityName,:districtName,:music)
                        """, params);
                for (String recipient : recipients) {
                    jdbc.update("INSERT INTO tbl_venue_suggestion_mail(id,suggestion_id,recipient) VALUES (:mailId,:id,:recipient)",
                            Map.of("mailId", UUID.randomUUID(), "id", suggestionId, "recipient", recipient));
                }
                jdbc.update("UPDATE tbl_venue_suggestion_dedupe SET suggestion_id=:id,expires_at=CURRENT_TIMESTAMP+INTERVAL '24 hours' WHERE dedupe_key=:dedupe", params);
            }
            params.put("id", suggestionId);
            jdbc.update("UPDATE tbl_venue_suggestion_request SET suggestion_id=:id WHERE request_id=:requestId", params);
        });
    }

    private static final String DELIVERY_SELECT = """
            SELECT mail.id,mail.lease_token,mail.recipient,suggestion.id AS suggestion_id,
                   suggestion.venue_name,suggestion.city_name,suggestion.district_name,suggestion.live_music
            FROM tbl_venue_suggestion_mail mail JOIN tbl_venue_suggestion suggestion ON suggestion.id=mail.suggestion_id
            """;

    public List<Delivery> claimPublishBatch(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("Invalid bounded mail batch");
        return transaction.execute(transactionStatus -> {
            // Crashed/ambiguous sending is NOT blindly replayed. Later definitive success may still settle its same token.
            jdbc.update("""
                    UPDATE tbl_venue_suggestion_mail SET status='NEEDS_REVIEW',last_error='send_lease_expired'
                    WHERE id IN (SELECT id FROM tbl_venue_suggestion_mail WHERE status='SENDING'
                        AND lease_until<=CURRENT_TIMESTAMP ORDER BY lease_until,id LIMIT 100 FOR UPDATE SKIP LOCKED)
                    """, Map.of());
            jdbc.update("""
                    UPDATE tbl_venue_suggestion_mail SET status='NEEDS_REVIEW',last_error='publish_attempt_limit',lease_until=NULL
                    WHERE id IN (SELECT id FROM tbl_venue_suggestion_mail WHERE publish_attempts>=20
                        AND ((status IN ('PENDING','QUEUED') AND next_attempt_at<=CURRENT_TIMESTAMP)
                            OR (status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP))
                        ORDER BY created_at,id LIMIT 100 FOR UPDATE SKIP LOCKED)
                    """, Map.of());
            var candidates = jdbc.queryForList(DELIVERY_SELECT + """
                    WHERE mail.publish_attempts<20 AND ((mail.status IN ('PENDING','QUEUED') AND mail.next_attempt_at<=CURRENT_TIMESTAMP)
                      OR (mail.status='PUBLISHING' AND mail.lease_until<=CURRENT_TIMESTAMP))
                    ORDER BY mail.created_at,mail.id LIMIT :limit FOR UPDATE OF mail SKIP LOCKED
                    """, Map.of("limit", limit));
            var result = new ArrayList<Delivery>();
            for (var row : candidates) {
                UUID token = UUID.randomUUID();
                jdbc.update("""
                        UPDATE tbl_venue_suggestion_mail SET status='PUBLISHING',lease_token=:token,
                            lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds',publish_attempts=publish_attempts+1
                        WHERE id=:id
                        """, Map.of("token", token, "id", row.get("id")));
                result.add(delivery(row, token));
            }
            return List.copyOf(result);
        });
    }

    public void published(Delivery delivery) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE tbl_venue_suggestion_mail SET status='QUEUED',lease_until=NULL,
                    next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '10 minutes',last_error=NULL
                WHERE id=:id AND lease_token=:token AND status='PUBLISHING'
                """, identity(delivery)));
    }

    public void publishFailed(Delivery delivery) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE tbl_venue_suggestion_mail SET
                    status=CASE WHEN publish_attempts>=20 THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                    lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds',last_error='queue_publish_failed'
                WHERE id=:id AND lease_token=:token AND status='PUBLISHING'
                """, identity(delivery)));
    }

    public Optional<Delivery> claimSend(UUID id) {
        return transaction.execute(transactionStatus -> {
            var rows = jdbc.queryForList(DELIVERY_SELECT
                    + " WHERE mail.id=:id AND mail.status IN ('PUBLISHING','QUEUED') FOR UPDATE OF mail", Map.of("id", id));
            if (rows.isEmpty()) return Optional.empty();
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE tbl_venue_suggestion_mail SET status='SENDING',lease_token=:token,
                        lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes',send_attempts=send_attempts+1
                    WHERE id=:id
                    """, Map.of("id", id, "token", token));
            return Optional.of(delivery(rows.getFirst(), token));
        });
    }

    public void sent(Delivery delivery) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE tbl_venue_suggestion_mail SET status='SENT',sent_at=CURRENT_TIMESTAMP,lease_until=NULL,last_error=NULL
                WHERE id=:id AND lease_token=:token AND status IN ('SENDING','NEEDS_REVIEW')
                """, identity(delivery)));
    }
    public void needsReview(Delivery delivery, String reason) {
        String safeReason = reason != null && reason.matches("[a-z_]{1,100}") ? reason : "send_outcome_unknown";
        var params = new HashMap<>(identity(delivery)); params.put("reason", safeReason);
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE tbl_venue_suggestion_mail SET status='NEEDS_REVIEW',last_error=:reason,lease_until=NULL
                WHERE id=:id AND lease_token=:token AND status IN ('SENDING','NEEDS_REVIEW')
                """, params));
    }
    public void retryRateLimited(Delivery delivery, long retrySeconds) {
        var params = new HashMap<>(identity(delivery)); params.put("retry", Math.max(30, Math.min(86400, retrySeconds)));
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE tbl_venue_suggestion_mail SET
                    status=CASE WHEN send_attempts>=10 THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                    next_attempt_at=CURRENT_TIMESTAMP+(:retry * INTERVAL '1 second'),lease_until=NULL,last_error='provider_rate_limit'
                WHERE id=:id AND lease_token=:token AND status='SENDING'
                """, params));
    }
    public Map<String, Object> healthCounts() {
        return jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status='NEEDS_REVIEW') AS review,
                       count(*) FILTER (WHERE status NOT IN ('SENT','NEEDS_REVIEW')) AS pending,
                       count(*) FILTER (WHERE status<>'SENT' AND created_at<CURRENT_TIMESTAMP-INTERVAL '30 minutes') AS stale
                FROM tbl_venue_suggestion_mail WHERE status<>'SENT'
                """, Map.of());
    }
    private static Map<String, Object> identity(Delivery delivery) { return Map.of("id", delivery.id(), "token", delivery.token()); }

    private static Delivery delivery(Map<String, Object> row, UUID token) {
        String liveMusic = switch ((String) row.get("live_music")) {
            case "YES" -> "Evet"; case "NO" -> "Hayır"; default -> "Bilinmiyor";
        };
        String text = "Yeni bir mekan önerisi alındı.\n\nÖneri ID: " + row.get("suggestion_id")
                + "\nMekan: " + row.get("venue_name") + "\nŞehir: " + row.get("city_name")
                + "\nİlçe: " + row.get("district_name") + "\nCanlı müzik: " + liveMusic
                + "\n\nBu öneri bir mekan başvurusu değildir. Herhangi bir hesap veya profil oluşturulmadı.";
        return new Delivery((UUID) row.get("id"), token, (String) row.get("recipient"), "Yeni mekan önerisi — SoundConnect", text);
    }
    public record Delivery(UUID id, UUID token, String recipient, String subject, String text) {
        public MailSendRequest queueRequest() {
            return new MailSendRequest(recipient, subject, null, text, MailKind.VENUE_SUGGESTION_ADMIN,
                    Map.of("venueSuggestionDeliveryId", id.toString()));
        }
    }
}
