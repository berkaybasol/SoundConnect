package com.berkayb.soundconnect.modules.application.venueapplication.dto.response;

import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record VenueApplicationResponseDto(
		UUID id,
		String applicantUsername,
		String venueName,
		String venueAddress,
		String phone, // applicant.phone!
		ApplicationStatus status,
		LocalDateTime applicationDate,
		LocalDateTime decisionDate
) {}