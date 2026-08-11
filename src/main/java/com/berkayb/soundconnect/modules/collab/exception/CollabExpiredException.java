package com.berkayb.soundconnect.modules.collab.exception;

import com.berkayb.soundconnect.shared.exception.*;

/**
 * Signals an expired listing after its lifecycle transition has been persisted.
 * Mutation transactions explicitly commit this one domain outcome so expiry and
 * pending-application invalidation are never rolled back with the client error.
 */
public final class CollabExpiredException extends SoundConnectException {
    public CollabExpiredException() {
        super(ErrorType.COLLAB_EXPIRED);
    }
}
