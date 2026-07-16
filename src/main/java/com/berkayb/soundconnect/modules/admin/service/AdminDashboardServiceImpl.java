package com.berkayb.soundconnect.modules.admin.service;

import com.berkayb.soundconnect.modules.admin.dto.AdminDashboardSummaryDto;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {
	private final UserRepository userRepository;
	private final VenueApplicationRepository venueApplicationRepository;
	private final PromotionRepository promotionRepository;

	@Override
	public AdminDashboardSummaryDto getSummary() {
		return new AdminDashboardSummaryDto(
				userRepository.count(),
				venueApplicationRepository.countByStatus(ApplicationStatus.PENDING),
				venueApplicationRepository.countByStatus(ApplicationStatus.APPROVED),
				venueApplicationRepository.countByStatus(ApplicationStatus.REJECTED),
				promotionRepository.countByStatus(PromotionStatus.ACTIVE)
		);
	}
}
