package com.berkayb.soundconnect.modules.collab.exception;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

/**
 * Preserves the public not-found response after a read materializes an expiry.
 * The detail transaction commits this outcome so the lifecycle transition,
 * application invalidations and notification outbox rows are not rolled back.
 */
public final class CollabExpiredListingNotFoundException extends SoundConnectException {
    public CollabExpiredListingNotFoundException() {
        super(ErrorType.COLLAB_NOT_FOUND);
    }
}
