package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus; //eklendi
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface BandMapper {
	@Mapping(target = "members", source = "members", qualifiedByName = "mapMembers")
	@Mapping(target = "profilePictureUrl", ignore = true) //eklendi
	@Mapping(target = "countsTowardCreationLimit", ignore = true)
	BandResponseDto toDto(Band band);
	
	@org.mapstruct.Named("mapMembers")
	default Set<BandMemberResponseDto> mapMembers(Set<BandMember> members) {
		if (members == null) return null;
		return members.stream()
		              .filter(member -> member.getStatus() == BandMemberShipStatus.ACTIVE) //eklendi
		              .map(this::toMemberDto)
		              .collect(Collectors.toSet());
	}
	
	@Mapping(target = "userId", source = "user.id")
	@Mapping(target = "username", expression = "java(member.getUser().getUsername())")
	@Mapping(target = "profilePicture", expression = "java(member.getUser().getProfilePicture())")
	@Mapping(target = "role", source = "bandRole")
	@Mapping(target = "status", source = "status")
	@Mapping(target = "memberTitle", source = "memberTitle")
	@Mapping(target = "titleVersion", source = "titleVersion")
	BandMemberResponseDto toMemberDto(BandMember member);
}
