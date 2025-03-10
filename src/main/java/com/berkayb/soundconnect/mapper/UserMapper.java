package com.berkayb.soundconnect.mapper;

import com.berkayb.soundconnect.dto.request.UserSaveRequestDto;
import com.berkayb.soundconnect.dto.response.UserListDto;
import com.berkayb.soundconnect.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
	@Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
	@Mapping(target = "id", ignore = true)
	User toEntity(UserSaveRequestDto dto);
	
	// Kullanıcı entity'den UserListDto'ya dönüşüm eklenmeli
	UserListDto toDto(User user);
}