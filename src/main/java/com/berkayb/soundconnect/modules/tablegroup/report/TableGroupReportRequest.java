package com.berkayb.soundconnect.modules.tablegroup.report;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;

import java.util.Set;
import java.util.UUID;

public record TableGroupReportRequest(UUID requestId, Kind kind, Reason reason,
                                      String description, UUID messageId) {
    public enum Reason { INAPPROPRIATE, MISLEADING, MONEY_REQUEST, HARASSMENT, SPAM, SAFETY, OTHER }
    public enum Kind {
        LISTING(Reason.INAPPROPRIATE, Reason.MISLEADING, Reason.MONEY_REQUEST, Reason.OTHER),
        MESSAGE(Reason.MONEY_REQUEST, Reason.HARASSMENT, Reason.SPAM, Reason.OTHER),
        INCIDENT(Reason.HARASSMENT, Reason.MONEY_REQUEST, Reason.SAFETY, Reason.OTHER);

        private final Set<Reason> reasons;
        Kind(Reason... reasons) { this.reasons = Set.of(reasons); }
    }

    TableGroupReportRequest normalized() {
        String note = UsernameUtils.stripBoundaryWhitespace(description);
        if (note != null && note.isEmpty()) note = null;
        if (requestId == null || kind == null || reason == null || !kind.reasons.contains(reason)
                || (kind == Kind.MESSAGE) != (messageId != null)
                || (kind == Kind.INCIDENT && note == null)
                || (note != null && (note.indexOf(0) >= 0 || note.codePointCount(0, note.length()) > 500))) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        return new TableGroupReportRequest(requestId, kind, reason, note, messageId);
    }
}
