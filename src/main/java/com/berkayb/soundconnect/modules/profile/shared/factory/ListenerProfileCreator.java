package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class ListenerProfileCreator implements ProfileCreator {
	private final ListenerProfileService listenerProfileService;
	
	@Override
	public void createProfile(User user) {
	ListenerSaveRequestDto dto = new ListenerSaveRequestDto(
			null,null
		);
		listenerProfileService.createProfile(user.getId(), dto);
	}
	
	@Override
	public RoleEnum getSupportedRole() {
		return RoleEnum.ROLE_LISTENER;
	}
}