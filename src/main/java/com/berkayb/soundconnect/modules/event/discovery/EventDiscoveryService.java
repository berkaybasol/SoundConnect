package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.event.support.EventShareUrlBuilder;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EventDiscoveryService {
    private final EventDiscoveryRepository repository;
    private final EventScheduleClock clock;
    private final MediaAssetService media;
    private final EventShareUrlBuilder shareUrls;

    // One snapshot for the page, count and batched public decorations during concurrent edits.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventDiscoveryPage discover(LocalDate requestedDate, UUID cityId, UUID districtId,
            UUID neighborhoodId, int page, int size) {
        LocalDate today = clock.localNow().toLocalDate();
        LocalDate date = requestedDate == null ? today : requestedDate;
        if (cityId == null || (neighborhoodId != null && districtId == null)
                || page < 0 || page > 1000 || size < 1 || size > 50
                || date.isBefore(today) || date.isAfter(today.plusDays(6))) {
            throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        }
        var result = repository.findEvents(date, cityId, districtId, neighborhoodId, PageRequest.of(page, size));
        var content = present(result.getContent());
        return new EventDiscoveryPage(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isLast());
    }

    /** Shared bounded public event-card decoration for discovery and audience plan/profile pages. */
    public List<EventResponseDto> present(List<EventDiscoveryRow> rows) {
        if (rows.size() > 50) throw new IllegalArgumentException("Event card pages are bounded to 50 items");
        var posterIds = rows.stream().map(EventDiscoveryRow::posterImage)
                .map(EventDiscoveryService::parseMediaId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> posters = posterIds.isEmpty() ? Map.of() : media.getDisplayUrlMap(posterIds);
        return rows.stream().map(row -> toDto(row, posters)).toList();
    }

    /** Mirrors public names/links. Discovery does not use or expand a band's member list. */
    private EventResponseDto toDto(EventDiscoveryRow row, Map<UUID, String> posters) {
        PerformerType type = row.bandId() != null ? PerformerType.BAND
                : row.musicianProfileId() != null ? PerformerType.MUSICIAN
                : StringUtils.hasText(row.manualPerformerName()) ? PerformerType.MANUAL : null;
        String name = row.bandId() != null ? row.bandName()
                : firstText(row.musicianUsername(), row.musicianStageName(), row.manualPerformerName(), "Belirtilmemiş");
        UUID mediaId = parseMediaId(row.posterImage());
        String poster = mediaId != null ? posters.get(mediaId)
                : StringUtils.hasText(row.posterImage()) ? row.posterImage() : null;
        return new EventResponseDto(row.id(), row.title(), poster, name,
                row.musicianProfileId(), row.bandId(), type,
                Set.of(), row.venueId(), row.venueName(), row.venueCity(),
                row.venueDistrict(), row.venueNeighborhood(), row.eventDate(), row.startTime(), row.endTime(),
                row.description(), shareUrls.buildEventShareUrl(row.id()));
    }

    private static String firstText(String... values) {
        return Arrays.stream(values).filter(StringUtils::hasText).findFirst().orElse(null);
    }

    private static UUID parseMediaId(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
