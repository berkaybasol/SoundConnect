package com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper;


import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ListenerProfileMapper {
	@Mapping(source = "description", target = "bio")
	@Mapping(source = "user.id", target = "userId")
	ListenerProfileResponseDto toDto(ListenerProfile entity);
}