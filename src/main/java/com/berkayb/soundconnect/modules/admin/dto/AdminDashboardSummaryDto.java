package com.berkayb.soundconnect.modules.admin.dto;

public record AdminDashboardSummaryDto(
		long totalUsers,
		long pendingVenueApplications,
		long approvedVenueApplications,
		long rejectedVenueApplications,
		long pendingStudioApplications,
		long approvedStudioApplications,
		long rejectedStudioApplications,
		long activePromotions
) {
}
