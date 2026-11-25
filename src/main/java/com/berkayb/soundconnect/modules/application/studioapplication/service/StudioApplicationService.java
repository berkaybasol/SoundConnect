package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.shared.response.PageResponse;

import com.berkayb.soundconnect.shared.exception.StudioApplicationRejectedException;

import java.util.UUID;

public interface StudioApplicationService {
	StudioApplicationResponseDto createApplication(UUID applicantUserId, StudioApplicationCreateRequestDto request);
	PageResponse<StudioApplicationResponseDto> getApplicationsByUser(UUID applicantUserId, int page, int size);
	StudioApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId);
	PageResponse<StudioApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status, int page, int size);
	StudioApplicationResponseDto getById(UUID applicationId);
    StudioApplicationRejectedException rejectionForApplicant(UUID applicantUserId);
	StudioApplicationResponseDto approveApplication(UUID applicationId, UUID adminId);
	StudioApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason);
}
