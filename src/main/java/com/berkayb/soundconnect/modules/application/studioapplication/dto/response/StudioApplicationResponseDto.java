package com.berkayb.soundconnect.modules.application.studioapplication.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;

import java.time.LocalDateTime;
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
		LocalDateTime applicationDate,
		LocalDateTime decisionDate,
		UUID reviewedById,
		String rejectionReason
) {}
