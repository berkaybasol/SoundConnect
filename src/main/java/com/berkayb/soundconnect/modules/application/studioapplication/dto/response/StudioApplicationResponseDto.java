package com.berkayb.soundconnect.modules.application.studioapplication.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record StudioApplicationResponseDto(
		UUID id,
		UUID applicantId,
		String applicantUsername,
		String studioName,
		String studioAddress,
		String phone,
		UUID cityId,
		String cityName,
		UUID districtId,
		String districtName,
		UUID neighborhoodId,
		String neighborhoodName,
		ApplicationStatus status,
		Instant applicationDate,
		Instant decisionDate,
		@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID reviewedById,
		String rejectionReason,
        @com.fasterxml.jackson.annotation.JsonIgnore UUID decisionId
) {}
