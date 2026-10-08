package com.berkayb.soundconnect.modules.notification.campaign;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

@Service
@RequiredArgsConstructor
public class CampaignService {
    private final CampaignStore store;
    private final CampaignAccess access;
    private final CampaignTargets targets;

    @org.springframework.beans.factory.annotation.Value("${app.notification.campaigns.enabled:false}")
    private boolean enabled;

    private void ready() {
        if (!enabled || !Boolean.TRUE.equals(store.jdbc().queryForObject(
                "select to_regclass('tbl_notification_campaign') is not null", Map.of(), Boolean.class))) {
            throw new com.berkayb.soundconnect.shared.exception.SoundConnectException(com.berkayb.soundconnect.shared.exception.ErrorType.NOTIFICATION_CAMPAIGN_UNAVAILABLE);
        }
    }

    @Transactional(timeout = 10)
    public Campaign create(UUID actor, Write request) {
        access.requireAdmin(actor);
        ready();
        Write w = CampaignRules.normalize(request);
        if (w.requestId() == null) {
            throw CampaignRules.invalid();
        }
        // Serialize creation per administrator, including retries with the same request ID.
        store.jdbc().queryForList("select id from tbl_user where id=:id for update",
                Map.of("id", actor), UUID.class);
        // Waiting for this lock may outlive a role change; use the live administrator
        // state before returning an existing draft or admitting a new one.
        access.requireAdmin(actor);
        var old = store.jdbc().query(
                "select * from tbl_notification_campaign where created_by=:actor and request_id=:request",
                Map.of("actor", actor, "request", w.requestId()), store.rowMapper());
        if (!old.isEmpty()) {
            if (!old.getFirst().definition().equals(w)) {
                throw CampaignStore.conflict();
            }
            return response(old.getFirst());
        }
        if (store.jdbc().queryForObject(
                "select count(*) from tbl_notification_campaign where created_by=:actor and status in ('DRAFT','SCHEDULED','PAUSED')",
                Map.of("actor", actor), Long.class) >= 100) {
            throw CampaignStore.conflict();
        }
        targets.validate(actor, w.target());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        store.jdbc().update("""
            insert into tbl_notification_campaign(id,request_id,created_by,title,message,definition,status,created_at,updated_at)
            values(:id,:request,:actor,:title,:message,cast(:definition as jsonb),'DRAFT',:now,:now)
            """, Map.of("id", id, "request", w.requestId(), "actor", actor, "title", w.title(),
                "message", w.message(), "definition", store.json(w), "now", Timestamp.from(now)));
        return response(store.get(id, false));
    }

    @Transactional(timeout = 10)
    public Campaign update(UUID actor, UUID id, Write request) {
        access.requireAdmin(actor);
        ready();
        var row = store.get(id, true);
        access.requireAdmin(actor);
        version(row, request == null ? null : request.expectedVersion());
        if (row.status() != Status.DRAFT) {
            throw CampaignStore.conflict();
        }
        Write w = CampaignRules.normalize(request);
        targets.validate(actor, w.target());
        w = new Write(row.definition().requestId(), null, w.title(), w.message(), w.audience(), w.target(), w.schedule());
        store.jdbc().update(
                "update tbl_notification_campaign set title=:title,message=:message,definition=cast(:definition as jsonb),version=version+1,updated_at=:now where id=:id",
                Map.of("id", id, "title", w.title(), "message", w.message(),
                        "definition", store.json(w), "now", Timestamp.from(Instant.now())));
        return response(store.get(id, false));
    }

    @Transactional(timeout = 10)
    public Campaign action(UUID actor, UUID id, String action, Version command) {
        access.requireAdmin(actor);
        ready();
        var row = store.get(id, true);
        access.requireAdmin(actor);
        version(row, command == null ? null : command.expectedVersion());
        Instant now = Instant.now(), next = row.next();
        Status status;
        switch (action) {
            case "schedule" -> {
                if (row.status() != Status.DRAFT) {
                    throw CampaignStore.conflict();
                }
                targets.validate(actor, row.definition().target());
                status = Status.SCHEDULED;
                next = row.definition().schedule().startsAt();
                if (row.definition().schedule().repeat() == Repeat.WEEKLY) {
                    next = CampaignRules.next(row.definition().schedule(), next.minusNanos(1));
                }
                if (next == null || row.definition().schedule().endsAt() != null
                        && !row.definition().schedule().endsAt().isAfter(now)) {
                    throw CampaignRules.invalid();
                }
            }
            case "pause" -> {
                if (row.status() != Status.SCHEDULED) {
                    throw CampaignStore.conflict();
                }
                status = Status.PAUSED;
            }
            case "resume" -> {
                if (row.status() != Status.PAUSED) {
                    throw CampaignStore.conflict();
                }
                if (row.definition().schedule().endsAt() != null
                        && !row.definition().schedule().endsAt().isAfter(now)) {
                    status = Status.COMPLETED;
                    next = null;
                    store.completeRunningOccurrence(id, now);
                } else {
                    status = Status.SCHEDULED;
                }
            }
            case "cancel" -> {
                if (row.status() == Status.CANCELLED || row.status() == Status.COMPLETED) {
                    throw CampaignStore.conflict();
                }
                status = Status.CANCELLED;
                next = null;
                store.jdbc().update(
                        "update tbl_notification_campaign_occurrence set status='CANCELLED',completed_at=:now where campaign_id=:id and status='RUNNING'",
                        Map.of("id", id, "now", Timestamp.from(now)));
            }
            default -> throw CampaignRules.invalid();
        }
        store.jdbc().update(
                "update tbl_notification_campaign set status=:status,next_run_at=:next,version=version+1,updated_at=:now where id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                        .addValue("next", next == null ? null : Timestamp.from(next))
                        .addValue("now", Timestamp.from(now)));
        return response(store.get(id, false));
    }

    @Transactional(readOnly = true, timeout = 10)
    public Campaign get(UUID actor, UUID id) {
        access.requireAdmin(actor);
        ready();
        return response(store.get(id, false));
    }

    @Transactional(readOnly = true, timeout = 10)
    public Page list(UUID actor, int page, int size) {
        access.requireAdmin(actor);
        ready();
        if (page < 0 || page > 1000 || size < 1 || size > 50) {
            throw CampaignRules.invalid();
        }
        var items = store.jdbc().query(
                "select * from tbl_notification_campaign order by created_at desc,id desc limit :size offset :offset",
                Map.of("size", size, "offset", page * size), store.rowMapper());
        long total = store.jdbc().queryForObject("select count(*) from tbl_notification_campaign", Map.of(), Long.class);
        return new Page(items.stream().map(row -> response(row, false)).toList(), page, size, total);
    }

    private void version(CampaignStore.Row row, Long value) {
        if (value == null || value != row.version()) {
            throw CampaignStore.conflict();
        }
    }

    private Campaign response(CampaignStore.Row row) {
        return response(row, true);
    }

    private Campaign response(CampaignStore.Row row, boolean options) {
        var d = row.definition();
        var selected = options
                ? d.audience().userIds().stream().map(access::option).filter(Objects::nonNull).toList()
                : List.<UserOption>of();
        return new Campaign(row.id(), row.version(), d.title(), d.message(), d.audience(), d.target(),
                d.schedule(), row.status(), row.next(),
                new Stats(row.occurrences(), row.recipients(), row.notifications(), row.skipped()),
                row.created(), row.updated(), selected, null);
    }
}
