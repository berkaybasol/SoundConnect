package com.berkayb.soundconnect.modules.application.admin;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public record PendingApplicationRow(UUID id, String businessName, String applicantUsername,
                                    String cityName, String districtName, String neighborhoodName,
                                    Instant applicationDate, ApplicationStatus status,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String address) {
}
