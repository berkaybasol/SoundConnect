package com.berkayb.soundconnect.modules.contentreport;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import java.util.UUID;

public record ContentReportRequest(UUID requestId, ContentReportKind kind, UUID targetId, UUID profileId,
                                   Reason reason, String description) {
    public enum Reason { IMPERSONATION, HARASSMENT, INAPPROPRIATE, PRIVACY, SPAM, OTHER }

    ContentReportRequest normalized() {
        String note = UsernameUtils.stripBoundaryWhitespace(description);
        if (note != null && note.isEmpty()) note = null;
        if (requestId == null || kind == null || targetId == null || reason == null || (profileId != null && !kind.isProfile()) ||
                (note != null && (note.indexOf(0) >= 0 || note.codePointCount(0, note.length()) > 500))) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        return new ContentReportRequest(requestId, kind, targetId, profileId, reason, note);
    }
}
