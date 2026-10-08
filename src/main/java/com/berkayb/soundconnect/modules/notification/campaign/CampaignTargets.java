package com.berkayb.soundconnect.modules.notification.campaign;

import com.berkayb.soundconnect.modules.comment.support.CommentTargetAccessGuard;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.berkayb.soundconnect.modules.notification.campaign.CampaignContract.*;

/** Exact owned notification proof is separate from the current public product target. */
@Service
@RequiredArgsConstructor
public class CampaignTargets {
    private final CampaignStore store;
    private final CampaignAccess access;
    private final CommentTargetAccessGuard guard;
    private final EventService events;
    private final MediaAssetRepository media;
    private final MediaAssetMapper mediaMapper;
    private final PublicProfileResolverService profiles;
    private final PlatformTransactionManager transactions;

    private record Projection(Object event, Object media, Object profile) { }

    void validate(UUID actor, Target target) {
        project(actor, target, true);
    }

    private Projection project(UUID viewer, Target target, boolean administration) {
        access.requireActive(viewer);
        String type = access.profile(viewer);
        if (type == null && !administration) {
            throw CampaignStore.missing();
        }
        if (!administration && (target.kind() == Kind.COLLAB && "LISTENER".equals(type)
                || target.kind() == Kind.MARKETPLACE && "LISTENER".equals(type))) {
            throw CampaignStore.missing();
        }
        return switch (target.kind()) {
            case EVENT -> {
                guard.requireReadable(viewer, EngagementTargetType.EVENT, target.targetId());
                yield new Projection(events.getEventById(target.targetId()), null, null);
            }
            case CONTENT -> {
                guard.requireReadable(viewer, EngagementTargetType.MEDIA, target.targetId());
                yield new Projection(null,
                        mediaMapper.toDto(media.findById(target.targetId()).orElseThrow(CampaignStore::missing)), null);
            }
            case PROFILE -> {
                if (!access.active(target.targetId())) {
                    throw CampaignStore.missing();
                }
                var profile = profiles.resolveByUserId(target.targetId());
                if (profile.profiles().isEmpty()) {
                    throw CampaignStore.missing();
                }
                yield new Projection(null, null, profile);
            }
            default -> new Projection(null, null, null);
        };
    }

    public Resolved resolve(UUID viewer, UUID notification) {
        access.requireActive(viewer);
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject(
                "select exists(select 1 from tbl_notification where id=:id and recipient_id=:viewer and type='ADMIN_BROADCAST')",
                Map.of("id", notification, "viewer", viewer), Boolean.class))) {
            throw CampaignStore.missing();
        }
        var found = store.jdbc().query("""
            select n.is_read,c.*,o.id as occurrence_id,n.payload::text as notification_payload
            from tbl_notification n
            join tbl_notification_receipt receipt on receipt.source_event_id=n.source_event_id and receipt.recipient_id=n.recipient_id
            join tbl_notification_campaign_recipient recipient on recipient.event_id=n.source_event_id and recipient.recipient_id=n.recipient_id
            join tbl_notification_campaign_occurrence o on o.id=recipient.occurrence_id
            join tbl_notification_campaign c on c.id=o.campaign_id
            where n.id=:notification and n.recipient_id=:viewer and n.type='ADMIN_BROADCAST'
              and n.title=c.title and n.message=c.message
              and n.payload->>'campaignId'=c.id::text and n.payload->>'occurrenceId'=o.id::text
              and n.payload->>'targetKind'=c.definition->'target'->>'kind'
              and coalesce(n.payload->>'targetId','')=coalesce(c.definition->'target'->>'targetId','')
            """, Map.of("notification", notification, "viewer", viewer),
                (r, n) -> Map.entry(r.getBoolean("is_read"), store.rowMapper().mapRow(r, n)));
        if (found.isEmpty()) {
            throw CampaignStore.missing();
        }
        var row = found.getFirst();
        var target = row.getValue().definition().target();
        try {
            // Expected target disappearance rolls back its own read transaction, never the owned receipt lookup.
            if (!access.eligible(viewer, row.getValue().definition().audience())) {
                throw CampaignStore.missing();
            }
            Projection projection = new TransactionTemplate(transactions)
                    .execute(status -> project(viewer, target, false));
            return new Resolved(notification, viewer, "ADMIN_BROADCAST", row.getKey(), target, "AVAILABLE", null,
                    projection.event(), projection.media(), projection.profile());
        } catch (SoundConnectException unavailable) {
            if (!Set.of(ErrorType.NOTIFICATION_NOT_FOUND, ErrorType.ENGAGEMENT_NOT_FOUND, ErrorType.EVENT_NOT_FOUND,
                    ErrorType.PROFILE_NOT_FOUND).contains(unavailable.getErrorType())) {
                throw unavailable;
            }
            return new Resolved(notification, viewer, "ADMIN_BROADCAST", row.getKey(), new Target(Kind.HOME, null),
                    "UNAVAILABLE", "Bu içerik artık görüntülenemiyor.", null, null, null);
        }
    }

    public List<TargetOption> search(UUID actor, Kind kind, String query) {
        access.requireAdmin(actor);
        String term = CampaignAccess.term(query);
        if (kind == Kind.PROFILE) {
            return access.search(actor, query).stream()
                    .map(u -> new TargetOption(u.id(), u.username(), kind, u.profileType())).toList();
        }
        if (kind != Kind.EVENT && kind != Kind.CONTENT) {
            throw CampaignRules.invalid();
        }
        String table = kind == Kind.EVENT ? "tbl_event" : "tbl_media_asset";
        var candidates = store.jdbc().query(
                "select id,title from " + table + " where lower(title) like :q escape '\\' order by id limit 100",
                Map.of("q", "%" + term + "%"),
                (r, n) -> new TargetOption(r.getObject(1, UUID.class), r.getString(2), kind, null));
        var result = new ArrayList<TargetOption>();
        for (var candidate : candidates) {
            try {
                new TransactionTemplate(transactions)
                        .execute(s -> project(actor, new Target(kind, candidate.id()), true));
                result.add(candidate);
            } catch (SoundConnectException hidden) {
                if (!Set.of(ErrorType.ENGAGEMENT_NOT_FOUND, ErrorType.EVENT_NOT_FOUND, ErrorType.NOTIFICATION_NOT_FOUND)
                        .contains(hidden.getErrorType())) {
                    throw hidden;
                }
            }
            if (result.size() == 20) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
