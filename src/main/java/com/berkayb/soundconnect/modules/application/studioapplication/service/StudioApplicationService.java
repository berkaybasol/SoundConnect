package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;

import java.util.List;
import java.util.UUID;

public interface StudioApplicationService {
	StudioApplicationResponseDto createApplication(UUID applicantUserId, StudioApplicationCreateRequestDto request);
	List<StudioApplicationResponseDto> getApplicationsByUser(UUID applicantUserId);
	StudioApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId);
	List<StudioApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status);
	StudioApplicationResponseDto getById(UUID applicationId);
	StudioApplicationResponseDto approveApplication(UUID applicationId, UUID adminId);
	StudioApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason);
}
