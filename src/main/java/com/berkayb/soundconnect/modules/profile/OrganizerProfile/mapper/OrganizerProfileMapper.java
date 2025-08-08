package com.berkayb.soundconnect.modules.profile.OrganizerProfile.mapper;


import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.entity.OrganizerProfile;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrganizerProfileMapper {
	OrganizerProfileResponseDto toDto(OrganizerProfile organizerProfile);
}