package com.berkayb.soundconnect.modules.application.mailintent;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Application transaction owns enqueue. Only dispatcher claim/outcome use independent short transactions. */
@Repository
public class ApplicationMailIntentStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationMailProperties properties;
    private final TransactionTemplate enqueueTransaction;
    private final TransactionTemplate workerTransaction;

    public ApplicationMailIntentStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json,
            PlatformTransactionManager manager, ApplicationMailProperties properties) {
        this.jdbc = jdbc; this.json = json; this.properties = properties;
        enqueueTransaction = new TransactionTemplate(manager);
        enqueueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        workerTransaction = new TransactionTemplate(manager);
        workerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        workerTransaction.setTimeout(10);
    }

    public void enqueue(UUID applicationId, String decision, MailSendRequest request) {
        try {
            enqueueTransaction.executeWithoutResult(status -> {
            validate(applicationId, decision, request);
            UUID id = UUID.randomUUID();
            var params = new LinkedHashMap<String, Object>(request.params());
            params.put("applicationMailIntentId", id.toString());
            var snapshot = new MailSendRequest(request.to(), request.subject(), request.htmlBody(),
                    request.textBody(), request.kind(), params);
            final String payload;
            try { payload = json.writeValueAsString(snapshot); }
            catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid application mail snapshot"); }
            // Serialize now; the background worker never dereferences an entity or recomputes recipients/content.
            jdbc.update("""
                    INSERT INTO tbl_application_mail_intent(id,application_id,purpose,decision,recipient,payload,max_attempts)
                    VALUES (:id,:app,:purpose,:decision,:recipient,CAST(:payload AS jsonb),:max)
                    ON CONFLICT (application_id,purpose,decision,recipient) DO NOTHING
                    """, Map.of("id", id, "app", applicationId, "purpose", request.kind().name(),
                    "decision", decision, "recipient", request.to(), "payload", payload, "max", properties.getMaxAttempts()));
            // Conflict deliberately preserves the original snapshot, identity, status and attempt budget.
            });
        } catch (DataAccessException unavailable) {
            // PostgreSQL constraint details can contain the entire rejected row, including address/body.
            // Keep rollback semantics but do not propagate those details into HTTP or generic exception logs.
            throw new DataAccessResourceFailureException("Application mail intent persistence unavailable. applicationId=" + applicationId);
        }
    }

    private static void validate(UUID app, String decision, MailSendRequest request) {
        if (app == null || request == null || request.params() == null || request.to() == null
                || request.to().isBlank() || !request.to().equals(request.to().trim())
                || !app.toString().equals(request.params().get("applicationId")))
            throw new IllegalArgumentException("Invalid application mail identity");
        boolean terminal = request.kind() == MailKind.STUDIO_APPLICATION_DECISION;
        boolean admin = request.kind() == MailKind.VENUE_APPLICATION_ADMIN || request.kind() == MailKind.STUDIO_APPLICATION_ADMIN;
        if ((!terminal && !admin) || (admin && !"CREATED".equals(decision))
                || (terminal && (!Set.of("APPROVED", "REJECTED").contains(decision)
                || !decision.equals(request.params().get("status")))))
            throw new IllegalArgumentException("Invalid application mail purpose");
    }

    /** Claim one just before sending, so sequential batch members do not age while waiting for broker IO. */
    public Optional<Claim> claim() {
        return workerTransaction.execute(tx -> {
            jdbc.update("""
                    UPDATE tbl_application_mail_intent SET status='NEEDS_REVIEW',lease_token=NULL,lease_until=NULL,
                        last_error='publish_attempt_limit'
                    WHERE id IN (SELECT id FROM tbl_application_mail_intent WHERE attempt_count>=max_attempts
                        AND ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP)
                          OR (status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP))
                        ORDER BY created_at,id LIMIT 100 FOR UPDATE SKIP LOCKED)
                    """, Map.of());
            var rows = jdbc.queryForList("""
                    SELECT id,application_id,purpose,decision,recipient,payload::text AS payload,attempt_count FROM tbl_application_mail_intent
                    WHERE attempt_count<max_attempts AND ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP)
                        OR (status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP))
                    ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, Map.of());
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.getFirst();
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    UPDATE tbl_application_mail_intent SET status='PUBLISHING',attempt_count=attempt_count+1,
                        lease_token=:token,lease_until=CURRENT_TIMESTAMP+(:lease * INTERVAL '1 second') WHERE id=:id
                    """, Map.of("id", row.get("id"), "token", token, "lease", properties.getLeaseSeconds()));
            return Optional.of(new Claim((UUID) row.get("id"), (UUID) row.get("application_id"), token,
                    (String) row.get("purpose"), (String) row.get("decision"), (String) row.get("recipient"),
                    (String) row.get("payload"), ((Number) row.get("attempt_count")).intValue()+1));
        });
    }

    public MailSendRequest payload(Claim claim) {
        try {
            var request = json.readValue(claim.payload(), MailSendRequest.class);
            if (request == null || request.params() == null
                    || request.kind() == null || !request.kind().name().equals(claim.purpose())
                    || !Objects.equals(request.to(),claim.recipient())
                    || !claim.id().toString().equals(request.params().get("applicationMailIntentId")))
                throw new IllegalArgumentException("Invalid application mail snapshot");
            validate(claim.applicationId(), claim.decision(), request);
            return request;
        } catch (JsonProcessingException | IllegalArgumentException invalid) { throw new InvalidSnapshotException(); }
    }

    /** Distinct from IllegalArgumentException, which JPA's repository advisor translates to a data-access failure. */
    public static final class InvalidSnapshotException extends RuntimeException {
        InvalidSnapshotException() { super("Invalid application mail snapshot"); }
    }

    private static final String FENCE = " WHERE id=:id AND lease_token=:token AND status='PUBLISHING' AND lease_until>CURRENT_TIMESTAMP";
    public boolean published(Claim claim) {
        return workerTransaction.execute(tx -> jdbc.update("""
                UPDATE tbl_application_mail_intent SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP,
                    lease_token=NULL,lease_until=NULL,last_error=NULL
                """ + FENCE, identity(claim)) == 1);
    }
    public boolean failed(Claim claim, boolean invalidPayload) {
        var parameters = new HashMap<String, Object>(identity(claim));
        long delay = Math.min(properties.getRetryMaxSeconds(), (long) properties.getRetryBaseSeconds()
                * (1L << Math.min(20, Math.max(0, claim.attempt()-1))));
        parameters.put("delay", delay); parameters.put("invalid", invalidPayload);
        return workerTransaction.execute(tx -> jdbc.update("""
                UPDATE tbl_application_mail_intent SET
                    status=CASE WHEN :invalid OR attempt_count>=max_attempts THEN 'NEEDS_REVIEW' ELSE 'PENDING' END,
                    last_error=CASE WHEN :invalid THEN 'invalid_snapshot' WHEN attempt_count>=max_attempts
                        THEN 'publish_attempt_limit' ELSE 'publish_failed_or_unknown' END,
                    next_attempt_at=CURRENT_TIMESTAMP+(:delay * INTERVAL '1 second'),lease_token=NULL,lease_until=NULL
                """ + FENCE, parameters) == 1);
    }
    public Map<String,Object> healthCounts() {
        // Verify every column used by the writer/worker, even when the table is empty.
        jdbc.queryForList("""
                SELECT id,application_id,purpose,decision,recipient,payload,max_attempts,attempt_count,
                    next_attempt_at,lease_token,lease_until,created_at,published_at,last_error,status
                FROM tbl_application_mail_intent WHERE false
                """, Map.of());
        return jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status='PENDING') AS pending,
                    count(*) FILTER (WHERE status='PENDING' AND attempt_count>0) AS retry,
                    count(*) FILTER (WHERE status='PUBLISHING') AS publishing,
                    count(*) FILTER (WHERE status='NEEDS_REVIEW') AS review,
                    count(*) FILTER (WHERE status IN ('PENDING','PUBLISHING') AND created_at<CURRENT_TIMESTAMP-INTERVAL '30 minutes') AS stale
                FROM tbl_application_mail_intent WHERE status<>'PUBLISHED'
                """, Map.of());
    }
    private static Map<String,Object> identity(Claim claim) { return Map.of("id",claim.id(),"token",claim.token()); }
    // Never log this record: payload contains private application content.
    public record Claim(UUID id, UUID applicationId, UUID token, String purpose, String decision, String recipient, String payload, int attempt) {
        @Override public String toString() { return "ApplicationMailClaim[id="+id+", attempt="+attempt+"]"; }
    }
}
