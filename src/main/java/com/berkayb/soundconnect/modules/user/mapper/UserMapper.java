package com.berkayb.soundconnect.modules.user.mapper;

import com.berkayb.soundconnect.modules.user.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.modules.user.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.modules.user.dto.response.UserListDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.role.entity.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface UserMapper {
	
	@Mapping(target = "followers", expression = "java(user.getFollowers() != null ? user.getFollowers().size() : 0)")
	@Mapping(target = "following", expression = "java(user.getFollowing() != null ? user.getFollowing().size() : 0)")
	@Mapping(target = "roles", source = "roles", qualifiedByName = "mapRolesToNames")
	UserListDto toDto(User user);
	
	UserUpdateRequestDto toUpdateRequestDto(User user);
	
	@Named("mapRolesToNames")
	default Set<String> mapRolesToNames(Set<Role> roles) {
		if (roles == null) return null;
		return roles.stream()
		            .map(Role::getName)
		            .collect(Collectors.toSet());
	}
}