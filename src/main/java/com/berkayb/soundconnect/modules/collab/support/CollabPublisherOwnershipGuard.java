package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.modules.collab.spec.CollabSpecifications;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CollabPublisherOwnershipGuard {
    private final CollabRepository listingRepository;

    @Transactional(readOnly = true)
    public void requireValid(Collab listing) {
        if (listing == null
                || listing.getId() == null
                || !listingRepository.exists(CollabSpecifications.publisherOwnershipValid(listing.getId()))) {
            throw new SoundConnectException(ErrorType.COLLAB_NOT_FOUND);
        }
    }
}
