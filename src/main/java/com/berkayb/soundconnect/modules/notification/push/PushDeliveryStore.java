package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.push.transport.PushEnvelope;
import com.berkayb.soundconnect.modules.notification.push.transport.PushSendResult;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Short database transactions surround, but never contain, the provider HTTP call. */
@Service
@ConditionalOnProperty(name = "app.notification.push.enabled", havingValue = "true")
public class PushDeliveryStore {
    public record Claim(UUID id, UUID notificationId, UUID recipientId, UUID installationId,
                        long deviceGeneration, int attempts, Instant expiresAt, UUID leaseOwner) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationRepository notifications;
    private final NotificationDeliveryPolicy policy;
    private final PushTokenCipher cipher;
    private final PushProperties properties;
    private final Clock clock;
    private DmPushPresentation presentation;
    private VenuePushEligibility venueEligibility;
    private StudioPushEligibility studioEligibility;
    @Autowired
    public PushDeliveryStore(NamedParameterJdbcTemplate jdbc, NotificationRepository notifications,
                             NotificationDeliveryPolicy policy, PushTokenCipher cipher, PushProperties properties,
                             @Qualifier("pushClock") Clock clock, DmPushPresentation presentation,
                             VenuePushEligibility venueEligibility, StudioPushEligibility studioEligibility) {
        this(jdbc, notifications, policy, cipher, properties, clock);
        this.presentation = presentation;
        this.venueEligibility = venueEligibility;
        this.studioEligibility = studioEligibility;
    }
    /** Standalone fixture constructor: missing presentation always uses anonymous identity. */
    public PushDeliveryStore(NamedParameterJdbcTemplate jdbc, NotificationRepository notifications,
                             NotificationDeliveryPolicy policy, PushTokenCipher cipher, PushProperties properties,
                             @Qualifier("pushClock") Clock clock) {
        this.jdbc=jdbc; this.notifications=notifications; this.policy=policy; this.cipher=cipher;
        this.properties=properties; this.clock=clock;
        this.venueEligibility=new VenuePushEligibility(jdbc,clock);
        this.studioEligibility=new StudioPushEligibility(jdbc,clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<Claim> claimNext() {
        var now=clock.instant(); var owner=UUID.randomUUID();
        var rows=jdbc.query("""
                with candidate as (
                  select id from tbl_push_delivery
                  where (status='PENDING' and next_attempt_at<=:now)
                     or (status='IN_FLIGHT' and lease_until<=:now)
                  order by next_attempt_at,created_at,id for update skip locked limit 1
                )
                update tbl_push_delivery d set status='IN_FLIGHT',lease_owner=:owner,lease_until=:until,
                  attempt_count=attempt_count+1,updated_at=:now
                from candidate c where d.id=c.id returning d.*
                """,Map.of("now",Timestamp.from(now),"owner",owner,"until",Timestamp.from(now.plus(properties.getLeaseDuration()))),
                (rs,index)->new Claim(rs.getObject("id",UUID.class),rs.getObject("notification_id",UUID.class),
                        rs.getObject("recipient_id",UUID.class),rs.getObject("installation_id",UUID.class),
                        rs.getLong("device_generation"),rs.getInt("attempt_count"),rs.getTimestamp("expires_at").toInstant(),owner));
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Optional<PushEnvelope> prepare(Claim claim) {
        if (!properties.isEnabled()) return suppress(claim,"PUSH_DISABLED");
        if (!claim.expiresAt().isAfter(clock.instant())) return suppress(claim,"EXPIRED");
        if (claim.attempts()>properties.getMaxAttempts()) {
            finish(claim,"DEAD_LETTER","ATTEMPTS_EXHAUSTED",null); return Optional.empty();
        }
        var notification=notifications.findById(claim.notificationId()).orElse(null);
        if (notification==null || notification.isRead() || !claim.recipientId().equals(notification.getRecipientId()))
            return suppress(claim,"INBOX_UNAVAILABLE_OR_READ");
        if (!properties.getAllowedTypes().contains(notification.getType())) return suppress(claim,"TYPE_DISABLED");
        var event=NotificationInboundEvent.builder().eventId(notification.getSourceEventId())
                .recipientId(notification.getRecipientId()).type(notification.getType()).payload(notification.getPayload())
                .occurredAt(notification.getOccurredAt()).emailForce(false).build();
        if (!policy.eligible(event)) return suppress(claim,"SOURCE_OR_AUDIENCE_UNAVAILABLE");
        // Recheck inbox, live account, device ownership/version, permission and preferences
        // immediately before preparing the minimal payload. Token plaintext is never persisted on a job.
        boolean applicationType=VenueApplicationPushPresentation.TYPES.contains(notification.getType());
        UUID applicationId=NotificationDeliveryPolicy.uuid(notification.getPayload(),"applicationId");
        var encrypted=jdbc.query("""
                select d.token_ciphertext,d.platform,d.presentation_version from tbl_push_device d
                join tbl_push_delivery j on j.installation_id=d.installation_id
                join tbl_notification n on n.id=j.notification_id
                join tbl_user u on u.id=d.user_id
                left join tbl_push_preference p on p.user_id=d.user_id
                where j.id=:id and j.status='IN_FLIGHT' and j.lease_owner=:owner and j.lease_until>:now
                  and d.user_id=:recipient and d.generation=:generation and d.revoked_at is null
                  and d.token_ciphertext is not null and d.permission in ('AUTHORIZED','PROVISIONAL')
                  and d.last_seen_at>:stale and u.erased_at is null and u.email_verified
                  and (u.status='ACTIVE' or (:applicationType and u.status='PENDING_VENUE_REQUEST'))
                  and (d.application_scope_id is null or (:applicationType and d.application_scope_id=:applicationId))
                  and n.is_read=false and coalesce(p.enabled,true)
                  and not (:category=any(coalesce(p.disabled_categories,'{}'::text[])))
                """,Map.of("id",claim.id(),"owner",claim.leaseOwner(),"now",Timestamp.from(clock.instant()),
                "recipient",claim.recipientId(),"generation",claim.deviceGeneration(),
                "applicationType",applicationType,"applicationId",applicationId==null?new UUID(0,0):applicationId,
                "stale",Timestamp.from(clock.instant().minus(properties.getDeviceStaleAfter())),"category",notification.getType().getCategory()),
                (rs,index)->new DevicePresentation(rs.getString(1),rs.getString(2),rs.getString(3)));
        if (encrypted.isEmpty()) return suppress(claim,"DEVICE_OR_PREFERENCE_UNAVAILABLE");
        if (applicationType && (!"ANDROID".equals(encrypted.getFirst().platform())
                || !VenueApplicationPushPresentation.supportsApplication(encrypted.getFirst().version())))
            return suppress(claim,"PRESENTATION_UNAVAILABLE");
        boolean venueType=VenuePushPresentation.TYPES.contains(notification.getType());
        Optional<VenuePushPresentation.Variant> venueVariant=Optional.empty();
        if (venueType) {
            if (!"ANDROID".equals(encrypted.getFirst().platform())
                    || !VenuePushPresentation.supportsVenue(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            venueVariant=venueEligibility.resolve(notification);
            if (venueVariant.isEmpty()) return suppress(claim,"VENUE_SOURCE_UNAVAILABLE");
        }
        boolean studioType=StudioPushPresentation.TYPES.contains(notification.getType());
        Optional<StudioPushPresentation.Variant> studioVariant=Optional.empty();
        if(studioType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !StudioPushPresentation.supportsStudio(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            studioVariant=studioEligibility.resolve(notification);
            if(studioVariant.isEmpty()) return suppress(claim,"STUDIO_SOURCE_UNAVAILABLE");
        }
        boolean followType=FollowPushPresentation.TYPES.contains(notification.getType());
        if(followType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !FollowPushPresentation.supportsFollow(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            if(!FollowPushPresentation.eligible(notification,jdbc)) return suppress(claim,"FOLLOW_SOURCE_UNAVAILABLE");
        }
        boolean mediaType=MediaPushPresentation.TYPES.contains(notification.getType());
        if(mediaType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !MediaPushPresentation.supportsMedia(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            if(!MediaPushPresentation.eligible(notification,jdbc)) return suppress(claim,"MEDIA_SOURCE_UNAVAILABLE");
        }
        boolean bandType=BandPushPresentation.TYPES.contains(notification.getType());
        if(bandType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !BandPushPresentation.supportsBand(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            if(!BandPushPresentation.eligible(notification,jdbc)) return suppress(claim,"BAND_SOURCE_UNAVAILABLE");
        }
        boolean tableType=TablePushPresentation.TYPES.contains(notification.getType());
        Optional<String> tableVariant=Optional.empty();
        if(tableType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !TablePushPresentation.supportsTable(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            tableVariant=TablePushPresentation.resolve(notification,jdbc);
            if(tableVariant.isEmpty()) return suppress(claim,"TABLE_SOURCE_UNAVAILABLE");
        }
        boolean collabType=CollabPushPresentation.TYPES.contains(notification.getType());
        Optional<String> collabVariant=Optional.empty();
        if(collabType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !CollabPushPresentation.supportsCollab(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            collabVariant=CollabPushPresentation.resolve(notification,jdbc);
            if(collabVariant.isEmpty()) return suppress(claim,"COLLAB_SOURCE_UNAVAILABLE");
            // A persisted claim never extends the source occurrence's configured lifetime.
            if(claim.expiresAt().isAfter(notification.getOccurredAt().plus(properties.getMessageTtl())))
                return suppress(claim,"INVALID_EXPIRY");
        }
        boolean overthinkingType=OverthinkingPushPresentation.TYPES.contains(notification.getType());
        if(overthinkingType) {
            if(!"ANDROID".equals(encrypted.getFirst().platform())
                    || !OverthinkingPushPresentation.supportsOverthinking(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            if(!OverthinkingPushPresentation.eligible(notification,jdbc)) return suppress(claim,"OVERTHINKING_SOURCE_UNAVAILABLE");
            if(claim.expiresAt().isAfter(notification.getOccurredAt().plus(properties.getMessageTtl())))
                return suppress(claim,"INVALID_EXPIRY");
        }
        var data=new LinkedHashMap<String,String>();
        data.put("notificationId",claim.notificationId().toString());
        data.put("recipientId",claim.recipientId().toString());
        data.put("type",notification.getType().name());
        if(notification.getType()==NotificationType.ADMIN_BROADCAST) {
            if(!"ANDROID".equals(encrypted.getFirst().platform()) || !CustomPushPresentation.CAPABILITY.equals(encrypted.getFirst().version()))
                return suppress(claim,"PRESENTATION_UNAVAILABLE");
            data.put("presentationVersion",CustomPushPresentation.VERSION);
            data.put("title",notification.getTitle());data.put("body",notification.getMessage());
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            var envelope=new PushEnvelope(cipher.decrypt(encrypted.getFirst().ciphertext()),notification.getTitle(),notification.getMessage(),Map.copyOf(data),claim.notificationId().toString(),claim.expiresAt());
            return CustomPushPresentation.valid(envelope)?Optional.of(envelope):suppress(claim,"INVALID_CUSTOM_PRESENTATION");
        }
        if (notification.getType()==NotificationType.DM_NEW_MESSAGE) {
            var conversation=NotificationDeliveryPolicy.uuid(notification.getPayload(),"conversationId");
            if (conversation==null) return suppress(claim,"INVALID_TARGET");
            data.put("conversationId",conversation.toString());
            if ("ANDROID".equals(encrypted.getFirst().platform())
                    && VenuePushPresentation.supportsDm(encrypted.getFirst().version())) {
                var senderId=NotificationDeliveryPolicy.uuid(notification.getPayload(),"senderId");
                var sender=presentation==null ? DmPushPresentation.anonymous() : presentation.resolve(senderId);
                data.put("presentationVersion",DmPushPresentation.VERSION);
                data.put("senderName",sender.name());
                if (!sender.avatarUrl().isBlank()) data.put("senderAvatarUrl",sender.avatarUrl());
                data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
                data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
            }
        } else if (overthinkingType) {
            data.put("presentationVersion",OverthinkingPushPresentation.VERSION);
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (collabType) {
            data.put("presentationVersion",CollabPushPresentation.VERSION);
            data.put("displayVariant",collabVariant.orElseThrow());
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (tableType) {
            data.put("presentationVersion",TablePushPresentation.VERSION);
            data.put("displayVariant",tableVariant.orElseThrow());
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (bandType) {
            data.put("presentationVersion",BandPushPresentation.VERSION);
            data.put("displayVariant","DEFAULT");
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (mediaType) {
            data.put("presentationVersion",MediaPushPresentation.VERSION);
            data.put("displayVariant","DEFAULT");
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (followType) {
            data.put("presentationVersion",FollowPushPresentation.VERSION);
            data.put("displayVariant","DEFAULT");
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (studioType) {
            data.put("presentationVersion",StudioPushPresentation.VERSION);
            data.put("displayVariant",studioVariant.orElseThrow().name());
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (applicationType) {
            data.put("presentationVersion",VenueApplicationPushPresentation.VERSION);
            data.put("displayVariant","DEFAULT");
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        } else if (venueType) {
            data.put("presentationVersion",VenuePushPresentation.VERSION);
            data.put("displayVariant",venueVariant.orElseThrow().name());
            data.put("expiresAt",Long.toString(claim.expiresAt().toEpochMilli()));
            data.put("sentAt",Long.toString(clock.instant().toEpochMilli()));
        }
        return Optional.of(new PushEnvelope(cipher.decrypt(encrypted.getFirst().ciphertext()),"Soundconnect",
                notification.getType()==NotificationType.DM_NEW_MESSAGE ? "Yeni bir mesajın var." : "Yeni bir bildirimin var.",
                Map.copyOf(data),claim.notificationId().toString(),claim.expiresAt()));
    }
    private record DevicePresentation(String ciphertext,String platform,String version) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void complete(Claim claim, PushSendResult result, String submittedTokenHash) {
        // Provider callbacks must own the current lease before any device-side effect.
        // Lock it so another worker cannot reclaim between this check and token revocation.
        var owned=jdbc.query("select id from tbl_push_delivery where id=:id and status='IN_FLIGHT' and lease_owner=:owner for update",
                Map.of("id",claim.id(),"owner",claim.leaseOwner()),(rs,index)->rs.getObject("id",UUID.class));
        if(owned.isEmpty()) return;
        switch (result.outcome()) {
            case ACCEPTED -> finish(claim,"ACCEPTED",null,result.providerMessageId());
            case INVALID_DEVICE -> {
                int revoked=jdbc.update("""
                        update tbl_push_device set revoked_at=:now,token_hash=null,token_ciphertext=null,generation=generation+1
                        where installation_id=:installation and user_id=:recipient and generation=:generation and token_hash=:hash
                        """,Map.of("now",Timestamp.from(clock.instant()),"installation",claim.installationId(),
                        "recipient",claim.recipientId(),"generation",claim.deviceGeneration(),"hash",Objects.requireNonNull(submittedTokenHash)));
                if(revoked==0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from tbl_push_device where installation_id=:installation
                          and user_id=:recipient and generation=:generation and revoked_at is null
                          and token_hash is not null and token_hash<>:hash and permission in ('AUTHORIZED','PROVISIONAL'))
                        """,Map.of("installation",claim.installationId(),"recipient",claim.recipientId(),
                        "generation",claim.deviceGeneration(),"hash",submittedTokenHash),Boolean.class))) {
                    retry(claim,"TOKEN_ROTATED",null);
                } else finish(claim,"SUPPRESSED",safeCode(result.errorCode()),null);
            }
            case PERMANENT_FAILURE -> finish(claim,"DEAD_LETTER",safeCode(result.errorCode()),null);
            case RETRYABLE_FAILURE -> retry(claim,safeCode(result.errorCode()),result.retryAfter());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void transientFailure(Claim claim) { retry(claim,"INTERNAL_TRANSIENT_FAILURE",null); }

    private void retry(Claim claim,String error,Duration retryAfter) {
        if (claim.attempts()>=properties.getMaxAttempts()) { finish(claim,"DEAD_LETTER",error,null); return; }
        Duration delay=backoff(claim.attempts(),retryAfter);
        Instant next=clock.instant().plus(delay);
        if (!next.isBefore(claim.expiresAt())) { finish(claim,"SUPPRESSED","EXPIRED_BEFORE_RETRY",null); return; }
        jdbc.update("""
                update tbl_push_delivery set status='PENDING',next_attempt_at=:next,lease_owner=null,lease_until=null,
                    last_error_code=:error,updated_at=:now
                where id=:id and status='IN_FLIGHT' and lease_owner=:owner
                """,Map.of("next",Timestamp.from(next),"error",error,"now",Timestamp.from(clock.instant()),"id",claim.id(),"owner",claim.leaseOwner()));
    }

    Duration backoff(int attempts,Duration retryAfter) {
        long exponential=properties.getRetryInitialDelay().toMillis() * (1L << Math.min(20,Math.max(0,attempts-1)));
        long cap=Math.min(exponential,properties.getRetryMaxDelay().toMillis());
        long jittered=Math.max(1,(long)(cap*ThreadLocalRandom.current().nextDouble(0.8,1.0)));
        Duration result=Duration.ofMillis(jittered);
        return retryAfter!=null && retryAfter.compareTo(result)>0 ? retryAfter : result;
    }
    private Optional<PushEnvelope> suppress(Claim claim,String reason) {
        finish(claim,"SUPPRESSED",reason,null); return Optional.empty();
    }
    private void finish(Claim claim,String state,String error,String providerId) {
        var args=new HashMap<String,Object>(); args.put("id",claim.id()); args.put("owner",claim.leaseOwner());
        args.put("state",state); args.put("error",error); args.put("provider",providerId);
        args.put("now",Timestamp.from(clock.instant()));
        jdbc.update("""
                update tbl_push_delivery set status=:state,lease_owner=null,lease_until=null,last_error_code=:error,
                  provider_message_id=:provider,accepted_at=case when :state='ACCEPTED' then cast(:now as timestamptz) else null end,
                  finished_at=:now,updated_at=:now
                where id=:id and status='IN_FLIGHT' and lease_owner=:owner
                """,args);
    }
    private static String safeCode(String value) {
        return value!=null && value.matches("[A-Z0-9_]{1,80}") ? value : "PROVIDER_FAILURE";
    }
}
