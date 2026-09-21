package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class EventManagementService {
    private final EventManagementRepository management;
    private final EventRepository events;
    private final EventMapper mapper;
    private final VenueEntityFinder venues;
    private final UserEntityFinder users;
    private final EventScheduleClock clock;

    public EventManagementResponse management(UUID actorId, UUID venueId) {
        authorize(actorId, venueId);
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime localAsOf = LocalDateTime.ofInstant(asOf, EventScheduleClock.ZONE);
        return new EventManagementResponse(cards(venueId, management.upcomingIds(venueId, localAsOf)),
                management.pastCount(venueId, localAsOf), asOf);
    }

    public EventHistoryPage history(UUID actorId, UUID venueId, String rawAsOf, String rawCursor, int size) {
        authorize(actorId, venueId);
        if (size < 1 || size > 50) throw invalid();
        Instant asOf = parseAsOf(rawAsOf);
        EventHistoryCursor cursor = EventHistoryCursor.decode(rawCursor, venueId, asOf);
        var positions = management.pastPositions(venueId, LocalDateTime.ofInstant(asOf, EventScheduleClock.ZONE), cursor, size + 1);
        boolean hasNext = positions.size() > size;
        var page = positions.subList(0, Math.min(size, positions.size()));
        String nextCursor = hasNext ? page.getLast().encode(venueId, asOf) : null;
        return new EventHistoryPage(cards(venueId, page.stream().map(EventHistoryCursor::id).toList()), nextCursor, hasNext);
    }

    private List<EventResponseDto> cards(UUID venueId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, Event> byId = events.findOwnerCardsByIds(venueId, ids).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
        return mapper.toDtos(ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList());
    }

    private void authorize(UUID actorId, UUID venueId) {
        if (actorId == null || venueId == null) throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
        var actor = users.getUser(actorId);
        var venue = venues.getVenue(venueId);
        if (venue.getOwner() == null || !actor.getId().equals(venue.getOwner().getId())) {
            throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
        }
    }

    private Instant parseAsOf(String raw) {
        try {
            if (raw == null || raw.length() > 40) throw invalid();
            Instant value = Instant.parse(raw);
            if (value.isBefore(Instant.EPOCH) || value.isAfter(clock.instant().plusSeconds(5))) throw invalid();
            return value;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    private SoundConnectException invalid() { return new SoundConnectException(ErrorType.INVALID_PARAMETER); }
}
