package com.berkayb.soundconnect.modules.profile.shared.factory;


import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.service.OrganizerProfileService;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrganizerProfileCreator implements ProfileCreator {
	private final OrganizerProfileService organizerProfileService;
	
	
	@Override
	public void createProfile(User user) {
		OrganizerProfileSaveRequestDto dto = new OrganizerProfileSaveRequestDto(
				null, null, null,
				null, null, null, null);
		organizerProfileService.createProfile(user.getId(), dto);
	}
	
	@Override
	public RoleEnum getSupportedRole() {
		return RoleEnum.ROLE_ORGANIZER;
	}
}