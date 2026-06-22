package com.berkayb.soundconnect.modules.follow.band.mapper;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BandFollowMapper {
	
	@Mapping(source = "id", target = "followId")
	@Mapping(source = "follower.id", target = "followerId")
	@Mapping(source = "follower.username", target = "followerUsername")
	@Mapping(source = "follower.profilePicture", target = "followerProfilePicture")
	@Mapping(source = "band.id", target = "bandId")
	@Mapping(source = "band.name", target = "bandName")
	@Mapping(source = "band.profilePictureMediaId", target = "bandProfilePictureMediaId")
	@Mapping(target = "bandProfilePictureUrl", ignore = true)
	BandFollowResponseDto toDto(BandFollow bandFollow);
}