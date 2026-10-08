package com.berkayb.soundconnect.modules.notification.campaign;

import java.time.*;
import java.util.*;

/** Closed administration contract; none of these targeting fields are accepted from FCM. */
public final class CampaignContract {
    private CampaignContract() { }
    public enum Mode { ALL, PROFILE_TYPES, USERS }
    public enum Kind { HOME, EVENTS, PROFILE, EVENT, CONTENT, TABLES, COLLAB, MARKETPLACE }
    public enum Repeat { ONCE, DAILY, WEEKLY, INTERVAL }
    public enum Status { DRAFT, SCHEDULED, PAUSED, COMPLETED, CANCELLED }
    public record Audience(Mode mode, Set<String> profileTypes, Set<UUID> userIds) { }
    public record Target(Kind kind, UUID targetId) { }
    public record Schedule(Instant startsAt, String zoneId, Repeat repeat, Integer intervalDays,
                           Set<Integer> weekDays, Instant endsAt, Integer maxOccurrences,
                           LocalDateTime localStartsAt, LocalDateTime localEndsAt) { }
    public record Write(UUID requestId, Long expectedVersion, String title, String message,
                        Audience audience, Target target, Schedule schedule) { }
    public record Version(Long expectedVersion) { }
    public record Stats(long occurrences, long recipients, long notifications, long skipped) { }
    public record UserOption(UUID id, String username, String displayName, String profileType) { }
    public record TargetOption(UUID id, String label, Kind kind, String profileType) { }
    public record Campaign(UUID id, long version, String title, String message, Audience audience,
                           Target target, Schedule schedule, Status status, Instant nextRunAt,
                           Stats stats, Instant createdAt, Instant updatedAt,
                           List<UserOption> selectedUsers, TargetOption selectedTarget) { }
    public record Page(List<Campaign> items, int page, int size, long total) { }
    public record Resolved(UUID notificationId, UUID recipientId, String type, boolean read,
                           Target target, String state, String message, Object event, Object media, Object profile) { }
}
