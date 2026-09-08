package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.*;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor
public class AnalyticsService {
    private final AnalyticsStore store;
    private final AnalyticsIdentity identity;
    private final AnalyticsRateGuard guard;
    private final EventScheduleClock clock;

    public AnalyticsResponse.Acknowledgement observe(UUID userId, AnalyticsRequest request) {
        identity.requireEnabled(); validate(request);
        guard.observations(userId, request.clientId(), request.observations().size());
        return storage(() -> {
            store.observe(userId, request, clock.instant());
            return new AnalyticsResponse.Acknowledgement(request.observations().stream().map(AnalyticsRequest.Observation::id).toList());
        });
    }
    public AnalyticsResponse.Summary summary(UUID owner, UUID venue, UUID event, int days) {
        requireReportingEnabled(); validateRead(owner, venue, days);
        return storage(() -> store.summary(owner, venue, event, days, clock.instant()));
    }
    public AnalyticsResponse.EventPage events(UUID owner, UUID venue, int days, int page, int size) {
        requireReportingEnabled(); validateRead(owner, venue, days);
        if (page < 0 || page > 1000 || size < 1 || size > 50) throw invalid();
        return storage(() -> store.events(owner, venue, days, page, size, clock.instant()));
    }
    public AnalyticsResponse.EventPage events(UUID owner, UUID venue, int days, int page, int size, AnalyticsResponse.EventSort sort) {
        requireReportingEnabled(); validateRead(owner, venue, days);
        if (page < 0 || page > 1000 || size < 1 || size > 50 || sort == null) throw invalid();
        return storage(() -> store.events(owner, venue, days, page, size, sort, clock.instant()));
    }
    public void requireReportingEnabled() { identity.requireReportingEnabled(); }
    private void validateRead(UUID owner, UUID venue, int days) {
        if (owner == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        if (venue == null || !Set.of(7, 30, 90).contains(days)) throw invalid();
    }
    private void validate(AnalyticsRequest request) {
        if (request == null || request.clientId() == null || request.clientId().equals(AnalyticsIdentity.NONE)
                || request.observations() == null || request.observations().isEmpty() || request.observations().size() > 20) throw invalid();
        var ids = new HashSet<UUID>();
        for (var observation : request.observations()) {
            if (observation == null || observation.id() == null || observation.id().equals(AnalyticsIdentity.NONE)
                    || !ids.add(observation.id()) || observation.type() == null || observation.observedAt() == null) throw invalid();
            if (AnalyticsIdentity.NONE.equals(observation.eventId()) || AnalyticsIdentity.NONE.equals(observation.venueId())
                    || AnalyticsIdentity.NONE.equals(observation.sourceEventId())) throw invalid();
            if (observation.type() == AnalyticsRequest.Type.VENUE_PROFILE_VIEW) {
                if (observation.venueId() == null || observation.eventId() != null) throw invalid();
            } else if (observation.eventId() == null || observation.venueId() != null || observation.sourceEventId() != null) throw invalid();
        }
    }
    private <T> T storage(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException | TransactionException unavailable) { throw AnalyticsIdentity.unavailable(); }
    }
    private static SoundConnectException invalid() { return new SoundConnectException(ErrorType.ANALYTICS_INVALID); }
}
