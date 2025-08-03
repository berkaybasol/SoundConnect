package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;

import java.util.List;
import java.util.UUID;

public interface VenueApplicationService {
	
	// basvuru olustur
	VenueApplicationResponseDto createApplication(UUID applicantUserId, VenueApplicationCreateRequestDto dto);
	
	// kullanicinin tum basvurularini getir
	List<VenueApplicationResponseDto> getApplicationsByUser (UUID applicantUserId);
	
	// admin: tum basvurulari basvuru durumlarina gore getir
	List<VenueApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status);
	
	// bekleyen basvuru var mi?
	VenueApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId);
	
	// id ie basvuru getir
	VenueApplicationResponseDto getById(UUID applicationId);
	
	// basvuru onay
	VenueApplicationResponseDto approveApplication(UUID applicationId, UUID adminId);
	
	// basvuru red
	VenueApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason);
	
}