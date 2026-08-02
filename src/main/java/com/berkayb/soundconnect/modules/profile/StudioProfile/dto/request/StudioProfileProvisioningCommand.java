package com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request;

import java.util.UUID;

public record StudioProfileProvisioningCommand(
		UUID userId,
		String name,
		String address,
		String phone,
		UUID cityId,
		UUID districtId,
		UUID neighborhoodId
) {}
