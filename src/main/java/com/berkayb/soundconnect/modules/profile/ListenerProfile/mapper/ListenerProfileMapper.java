package com.berkayb.soundconnect.modules.profile.ListenerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ListenerProfileMapper {
	
	@Mapping(source = "description", target = "bio")
	@Mapping(source = "user.id", target = "userId")
	@Mapping(source = "user.username", target = "username")
	@Mapping(target = "profilePictureUrl", ignore = true)
	@Mapping(target = "followerCount", ignore = true)
	@Mapping(target = "followingCount", ignore = true)
	ListenerProfileResponseDto toDto(ListenerProfile entity);
}