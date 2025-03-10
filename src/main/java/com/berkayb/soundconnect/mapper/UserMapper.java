package com.berkayb.soundconnect.mapper;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.request.UserUpdateRequestDto;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
	@Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "password", ignore = true)
	User toEntity(UserSaveRequestDto dto);
	UserListDto toDto(User user);
	UserUpdateRequestDto toUpdateRequestDto(User user);
}