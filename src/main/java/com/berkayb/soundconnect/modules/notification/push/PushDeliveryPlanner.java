package com.berkayb.soundconnect.modules.notification.push;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushDeliveryPlanner {
    private final NamedParameterJdbcTemplate jdbc;
    private final PushProperties properties;
    private final Clock clock;
    public PushDeliveryPlanner(NamedParameterJdbcTemplate jdbc, PushProperties properties, @Qualifier("pushClock") Clock clock) {
        this.jdbc=jdbc; this.properties=properties; this.clock=clock;
    }

    private static UUID applicationId(com.berkayb.soundconnect.modules.notification.entity.Notification notification) {
        var id=com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy.uuid(notification.getPayload(),"applicationId");
        return id==null?new UUID(0,0):id;
    }

    @EventListener // Deliberately synchronous; AFTER_COMMIT would introduce a process-crash gap.
    @Transactional(propagation = Propagation.MANDATORY)
    public void plan(NotificationPersisted event) {
        var notification = event.notification();
        if (!properties.isEnabled() || !properties.getAllowedTypes().contains(notification.getType()) || notification.isRead()) return;
        if (MediaPushPresentation.TYPES.contains(notification.getType())
                && !MediaPushPresentation.eligible(notification,jdbc)) return;
        if (BandPushPresentation.TYPES.contains(notification.getType())
                && !BandPushPresentation.eligible(notification,jdbc)) return;
        if (TablePushPresentation.TYPES.contains(notification.getType())
                && TablePushPresentation.resolve(notification,jdbc).isEmpty()) return;
        if (CollabPushPresentation.TYPES.contains(notification.getType())
                && CollabPushPresentation.resolve(notification,jdbc).isEmpty()) return;
        if (OverthinkingPushPresentation.TYPES.contains(notification.getType())
                && !OverthinkingPushPresentation.eligible(notification,jdbc)) return;
        var now = clock.instant();
        var expires = notification.getOccurredAt().plus(properties.getMessageTtl());
        if (!expires.isAfter(now)) return;
        var args = new java.util.HashMap<String,Object>();
        args.putAll(Map.<String,Object>of("recipient",notification.getRecipientId(),
                "venue",VenuePushPresentation.TYPES.contains(notification.getType()),
                "capability",VenuePushPresentation.CAPABILITY,
                "applicationType",VenueApplicationPushPresentation.TYPES.contains(notification.getType()),
                "applicationId",applicationId(notification),
                "applicationCapability",VenueApplicationPushPresentation.CAPABILITY,
                "studioType",StudioPushPresentation.TYPES.contains(notification.getType()),
                "studioCapability",StudioPushPresentation.CAPABILITY,
                "category",notification.getType().getCategory(), "stale",Timestamp.from(now.minus(properties.getDeviceStaleAfter()))));
        args.put("followType",FollowPushPresentation.TYPES.contains(notification.getType()));
        args.put("followCapability",FollowPushPresentation.CAPABILITY);
        args.put("mediaType",MediaPushPresentation.TYPES.contains(notification.getType()));
        args.put("mediaCapability",MediaPushPresentation.CAPABILITY);
        args.put("bandType",BandPushPresentation.TYPES.contains(notification.getType()));
        args.put("bandCapability",BandPushPresentation.CAPABILITY);
        args.put("tableType",TablePushPresentation.TYPES.contains(notification.getType()));
        args.put("tableCapability",TablePushPresentation.CAPABILITY);
        args.put("collabType",CollabPushPresentation.TYPES.contains(notification.getType()));
        args.put("collabCapability",CollabPushPresentation.CAPABILITY);
        args.put("overthinkingType",OverthinkingPushPresentation.TYPES.contains(notification.getType()));
        args.put("overthinkingCapability",OverthinkingPushPresentation.CAPABILITY);
        args.put("customType",notification.getType()==com.berkayb.soundconnect.modules.notification.enums.NotificationType.ADMIN_BROADCAST);
        args.put("customCapability",CustomPushPresentation.CAPABILITY);
        var devices = jdbc.query("""
                select d.installation_id, d.generation from tbl_push_device d
                left join tbl_push_preference p on p.user_id=d.user_id
                where d.user_id=:recipient and d.revoked_at is null and d.token_ciphertext is not null
                  and d.permission in ('AUTHORIZED','PROVISIONAL') and d.last_seen_at>:stale
                  and (not :venue or (d.platform='ANDROID' and d.presentation_version in (:capability,:applicationCapability,:studioCapability,:followCapability,:mediaCapability,:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :applicationType or (d.platform='ANDROID' and d.presentation_version in (:applicationCapability,:studioCapability,:followCapability,:mediaCapability,:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :studioType or (d.platform='ANDROID' and d.presentation_version in (:studioCapability,:followCapability,:mediaCapability,:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :followType or (d.platform='ANDROID' and d.presentation_version in (:followCapability,:mediaCapability,:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :mediaType or (d.platform='ANDROID' and d.presentation_version in (:mediaCapability,:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :bandType or (d.platform='ANDROID' and d.presentation_version in (:bandCapability,:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :tableType or (d.platform='ANDROID' and d.presentation_version in (:tableCapability,:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :collabType or (d.platform='ANDROID' and d.presentation_version in (:collabCapability,:overthinkingCapability,:customCapability)))
                  and (not :overthinkingType or (d.platform='ANDROID' and d.presentation_version in (:overthinkingCapability,:customCapability)))
                  and (not :customType or (d.platform='ANDROID' and d.presentation_version=:customCapability))
                  and (d.application_scope_id is null or (:applicationType and d.application_scope_id=:applicationId))
                  and coalesce(p.enabled,true) and not (:category=any(coalesce(p.disabled_categories,'{}'::text[])))
                order by d.installation_id for key share of d
                """,args,(rs,index) -> Map.entry(rs.getObject("installation_id",UUID.class),rs.getLong("generation")));
        for (var device : devices) {
            jdbc.update("""
                    insert into tbl_push_delivery(id,notification_id,recipient_id,installation_id,device_generation,status,
                      attempt_count,next_attempt_at,expires_at,created_at,updated_at)
                    values(:id,:notification,:recipient,:installation,:generation,'PENDING',0,:now,:expires,:now,:now)
                    on conflict(notification_id,installation_id) do nothing
                    """,Map.of("id",UUID.randomUUID(),"notification",notification.getId(),"recipient",notification.getRecipientId(),
                    "installation",device.getKey(),"generation",device.getValue(),"now",Timestamp.from(now),"expires",Timestamp.from(expires)));
        }
    }
}
