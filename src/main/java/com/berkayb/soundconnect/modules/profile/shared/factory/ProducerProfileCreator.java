package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.service.ProducerProfileService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProducerProfileCreator implements ProfileCreator {
	private final ProducerProfileService producerProfileService;
	
	
	@Override
	public void createProfile(User user) {
		ProducerProfileSaveRequestDto dto = new ProducerProfileSaveRequestDto(
				null,null,null,null,null,
				null, null, null
		);
		producerProfileService.createProfile(user.getId(), dto);
	}
	
	@Override
	public RoleEnum getSupportedRole() {
		return RoleEnum.ROLE_PRODUCER;
	}
}