package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.analytics.AnalyticsIdentity;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.*;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_PROMOTIONS')")
@Transactional
public class AnnouncementAdminService {
    private final AnnouncementAccess access;
    private final AnnouncementReadService reads;
    private final PromotionRepository promotions;
    private final MediaAssetRepository assets;
    private final MediaAssetService media;
    private final NamedParameterJdbcTemplate jdbc;
    private final AnalyticsIdentity analyticsIdentity;

    public AnnouncementResponse create(UUID actor, AnnouncementWrite request) {
        access.requireManage(actor);
        validate(request);
        if (request.mediaAssetId() != null) throw invalidMedia(); // The draft ID owns its subsequent upload.
        Promotion value = Promotion.builder().type(PromotionType.ANNOUNCEMENT).placement(PromotionPlacement.FEED)
                .status(PromotionStatus.DRAFT).priority(0).createdBy(actor).updatedBy(actor).build();
        apply(value, actor, request);
        promotions.saveAndFlush(value);
        return response(actor, value);
    }

    public AnnouncementResponse update(UUID actor, UUID id, AnnouncementWrite request) {
        validate(request);
        Promotion value = locked(actor, id, request.expectedVersion());
        if (value.getStatus() == PromotionStatus.ARCHIVED) throw stateConflict();
        MediaAsset previous = value.getMediaAsset();
        apply(value, actor, request);
        if (value.getStatus() == PromotionStatus.ACTIVE) requireReady(value.getMediaAsset());
        promotions.saveAndFlush(value);
        if (previous != null && !Objects.equals(previous.getId(), request.mediaAssetId())) {
            media.delete(previous.getId(), actor, MediaOwnerType.PROMOTION, value.getId());
        }
        return response(actor, value);
    }

    public AnnouncementResponse publish(UUID actor, UUID id, AnnouncementPublish request) {
        if (request == null) throw invalid();
        validateTime(request.startsAt()); validateTime(request.endsAt());
        Promotion value = locked(actor, id, request.expectedVersion());
        analyticsIdentity.requireEnabled();
        if (value.getStatus() == PromotionStatus.ARCHIVED) throw stateConflict();
        Instant now = microseconds(Instant.now());
        AnnouncementStatus current = AnnouncementReadService.status(value, now);
        Instant start = request.startsAt() == null ? now : microseconds(request.startsAt());
        if (current == AnnouncementStatus.PUBLISHED) {
            // Live edits/end-date changes cannot boost an already published card's order.
            if (request.startsAt() != null && !microseconds(request.startsAt()).equals(AnnouncementReadService.instant(value.getStartDate()))) {
                throw stateConflict();
            }
            start = AnnouncementReadService.instant(value.getStartDate());
        } else if (start.isBefore(now.minusSeconds(30))) {
            throw invalid();
        }
        if (request.endsAt() != null && (!request.endsAt().isAfter(start) || !request.endsAt().isAfter(now))) throw invalid();
        if (value.getTargetProfiles().isEmpty()) throw invalid();
        requireReady(value.getMediaAsset());
        // A canceled future schedule has never appeared to a viewer. Publishing it
        // now must not retain tomorrow's timestamp and disappear from today's feed.
        if (value.getFirstPublishedAt() == null || current == AnnouncementStatus.SCHEDULED
                || value.getFirstPublishedAt().isAfter(now)) {
            value.setFirstPublishedAt(start);
        }
        value.setStartDate(utc(start));
        value.setEndDate(utc(request.endsAt()));
        value.setStatus(PromotionStatus.ACTIVE);
        value.setUpdatedBy(actor);
        promotions.saveAndFlush(value);
        return response(actor, value);
    }

    public AnnouncementResponse end(UUID actor, UUID id, AnnouncementVersionAction request) {
        Promotion value = locked(actor, id, request == null ? null : request.expectedVersion());
        if (value.getStatus() != PromotionStatus.ACTIVE) throw stateConflict();
        value.setStatus(PromotionStatus.INACTIVE);
        value.setEndDate(utc(Instant.now()));
        value.setUpdatedBy(actor);
        promotions.saveAndFlush(value);
        return response(actor, value);
    }

    public AnnouncementResponse archive(UUID actor, UUID id, AnnouncementVersionAction request) {
        Promotion value = locked(actor, id, request == null ? null : request.expectedVersion());
        if (value.getStatus() == PromotionStatus.ARCHIVED) throw stateConflict();
        value.setStatus(PromotionStatus.ARCHIVED);
        value.setArchivedAt(microseconds(Instant.now()));
        value.setUpdatedBy(actor);
        promotions.saveAndFlush(value);
        return response(actor, value);
    }

    public void delete(UUID actor, UUID id, Long version) {
        Promotion value = locked(actor, id, version);
        if (value.getStatus() != PromotionStatus.DRAFT && value.getStatus() != PromotionStatus.ARCHIVED) throw stateConflict();
        value.setMediaAsset(null);
        promotions.saveAndFlush(value);
        var parameters = Map.of("id", id);
        jdbc.update("""
                delete from tbl_like where target_type='COMMENT' and target_id in
                    (select id from tbl_comment where target_type='ANNOUNCEMENT' and target_id=:id)
                """, parameters);
        jdbc.update("delete from tbl_comment where target_type='ANNOUNCEMENT' and target_id=:id and parent_comment_id is not null", parameters);
        jdbc.update("delete from tbl_comment where target_type='ANNOUNCEMENT' and target_id=:id", parameters);
        jdbc.update("delete from tbl_like where target_type='ANNOUNCEMENT' and target_id=:id", parameters);
        // The aggregate lock prevents new owner uploads while all its assets enter the
        // existing durable deletion pipeline. No storage I/O occurs in this transaction.
        while (true) {
            List<UUID> owned = jdbc.queryForList("""
                    select id from tbl_media_asset where owner_type='PROMOTION' and owner_id=:id
                        and status<>'DELETION_PENDING' order by id limit 50
                    """, parameters, UUID.class);
            if (owned.isEmpty()) break;
            for (UUID asset : owned) media.delete(asset, actor, MediaOwnerType.PROMOTION, id);
        }
        promotions.delete(value);
        promotions.flush();
    }

    public AnnouncementResponse get(UUID actor, UUID id) {
        access.requireManage(actor);
        Promotion value = promotions.findAnnouncementForUpdate(id).orElseThrow(AnnouncementAccess::notFound);
        return response(actor, value);
    }

    public AnnouncementPage list(UUID actor, AnnouncementStatus status, String encoded, int limit) {
        access.requireManage(actor);
        if (limit < 1 || limit > 50) throw invalid();
        String scope = "ADMIN:" + actor + ":" + (status == null ? "ALL" : status.name());
        var cursor = AnnouncementReadService.Cursor.decode(encoded, scope, null);
        Instant now = Instant.now(), anchor = cursor == null ? now : cursor.anchor();
        var params = new MapSqlParameterSource("limit", limit + 1)
                .addValue("now", AnnouncementAccess.utcTimestamp(now)).addValue("anchor", AnnouncementAccess.utcTimestamp(anchor));
        String filter = status == null ? "" : switch (status) {
            case DRAFT -> " and p.status='DRAFT'";
            case ARCHIVED -> " and p.status='ARCHIVED'";
            case SCHEDULED -> " and p.status='ACTIVE' and p.start_date>:now and (p.end_date is null or p.end_date>:now)";
            case PUBLISHED -> " and p.status='ACTIVE' and p.start_date<=:now and (p.end_date is null or p.end_date>:now)";
            case ENDED -> " and (p.status in ('INACTIVE','EXPIRED') or p.status='ACTIVE' and p.end_date<=:now)";
        };
        if (cursor != null) {
            filter += " and (p.created_at,p.id)<(:afterTime,:afterId)";
            params.addValue("afterTime", AnnouncementAccess.utcTimestamp(cursor.time())).addValue("afterId", cursor.id());
        }
        List<UUID> ids = jdbc.queryForList("""
                select p.id from tlb_promotion p where p.placement='FEED' and p.type='ANNOUNCEMENT'
                    and p.created_at<=:anchor
                """ + filter + " order by p.created_at desc,p.id desc limit :limit", params, UUID.class);
        boolean hasMore = ids.size() > limit;
        List<AnnouncementResponse> items = reads.project(actor, ids.subList(0, Math.min(ids.size(), limit)));
        String next = null;
        if (hasMore && !items.isEmpty()) {
            var last = items.getLast();
            next = new AnnouncementReadService.Cursor(scope, anchor, last.createdAt(), last.id()).encode();
        }
        return new AnnouncementPage(items, next, hasMore);
    }

    private Promotion locked(UUID actor, UUID id, Long version) {
        access.requireManage(actor);
        if (id == null || version == null || version < 0) throw invalid();
        Promotion value = promotions.findAnnouncementForUpdate(id).orElseThrow(AnnouncementAccess::notFound);
        if (value.getVersion() != version) throw new SoundConnectException(ErrorType.ANNOUNCEMENT_VERSION_CONFLICT);
        return value;
    }

    private void apply(Promotion value, UUID actor, AnnouncementWrite request) {
        value.setTitle(request.title().strip());
        value.setDescription(request.body().strip());
        value.getTargetProfiles().clear();
        value.getTargetProfiles().addAll(request.targetProfiles());
        value.setUpdatedBy(actor);
        MediaAsset attachment = request.mediaAssetId() == null ? null : assets.findByIdAndOwnerForUpdate(
                request.mediaAssetId(), MediaOwnerType.PROMOTION, value.getId()).orElseThrow(AnnouncementAdminService::invalidMedia);
        if (attachment != null && (attachment.getVisibility() != MediaVisibility.PRIVATE
                || !Set.of(MediaKind.IMAGE, MediaKind.VIDEO).contains(attachment.getKind())
                || Set.of(MediaStatus.DELETION_PENDING, MediaStatus.CLEANUP_PENDING, MediaStatus.FAILED,
                        MediaStatus.HLS_CLEANUP).contains(attachment.getStatus()))) throw invalidMedia();
        value.setMediaAsset(attachment);
    }

    private static void requireReady(MediaAsset asset) {
        if (asset != null && (asset.getStatus() != MediaStatus.READY || asset.getVisibility() != MediaVisibility.PRIVATE
                || asset.getKind() == MediaKind.VIDEO && asset.getStreamingProtocol() != MediaStreamingProtocol.PROGRESSIVE)) throw invalidMedia();
    }

    private AnnouncementResponse response(UUID actor, Promotion value) {
        return reads.project(actor, List.of(value.getId())).getFirst();
    }

    private static void validate(AnnouncementWrite request) {
        if (request == null || invalidText(request.title(), 150, false) || invalidText(request.body(), 5000, true)
                || request.targetProfiles() == null || request.targetProfiles().isEmpty()
                || !AnnouncementAccess.PROFILES.containsAll(request.targetProfiles())) throw invalid();
    }

    private static boolean invalidText(String value, int maximum, boolean multiline) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum
                || value.codePoints().anyMatch(code -> Character.isISOControl(code) && !(multiline && (code == '\n' || code == '\t'))
                        || code >= 0xd800 && code <= 0xdfff);
    }

    private static void validateTime(Instant value) {
        if (value != null && (value.isBefore(Instant.EPOCH) || value.isAfter(Instant.parse("2100-01-01T00:00:00Z")))) throw invalid();
    }
    private static Instant microseconds(Instant value) { return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(microseconds(value), ZoneOffset.UTC); }
    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.ANNOUNCEMENT_INVALID); }
    private static SoundConnectException invalidMedia() { return new SoundConnectException(ErrorType.ANNOUNCEMENT_MEDIA_INVALID); }
    private static SoundConnectException stateConflict() { return new SoundConnectException(ErrorType.ANNOUNCEMENT_STATE_CONFLICT); }
}
