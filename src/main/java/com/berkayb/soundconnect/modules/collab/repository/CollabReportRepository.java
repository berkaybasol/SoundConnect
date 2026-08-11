package com.berkayb.soundconnect.modules.collab.repository;

import com.berkayb.soundconnect.modules.collab.entity.CollabReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface CollabReportRepository extends JpaRepository<CollabReport, UUID> {
    Optional<CollabReport> findByReporterUserIdAndClientRequestId(UUID reporterUserId, UUID clientRequestId);
    Optional<CollabReport> findByReporterUserIdAndListingId(UUID reporterUserId, UUID listingId);
}
