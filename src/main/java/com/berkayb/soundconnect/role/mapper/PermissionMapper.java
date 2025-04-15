package com.berkayb.soundconnect.role.mapper;

import com.berkayb.soundconnect.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.role.entity.Permission;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PermissionMapper {
	PermissionMapper INSTANCE = Mappers.getMapper(PermissionMapper.class);
	
	
	PermissionResponse toDto(Permission permission);
	
	List<PermissionResponse>toDtoList(List<Permission> permissions);
}