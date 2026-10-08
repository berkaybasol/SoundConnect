package com.berkayb.soundconnect.modules.feed.musician.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record MusicianFeedReportDetail(MusicianFeedReportSummary report, UUID reporterUserId,
        JsonNode evidence, String scopeDescription, List<MusicianFeedReportDecision> allowedDecisions,
        boolean activeRestriction, List<MusicianFeedReportHistoryEntry> history) {
    public MusicianFeedReportDetail {
        evidence = evidence.deepCopy();
        allowedDecisions = List.copyOf(allowedDecisions);
        history = List.copyOf(history);
    }
}
