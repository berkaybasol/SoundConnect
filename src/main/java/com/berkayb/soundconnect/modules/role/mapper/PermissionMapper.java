package com.berkayb.soundconnect.modules.role.mapper;

import com.berkayb.soundconnect.modules.role.dto.response.PermissionResponse;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PermissionMapper {
	PermissionMapper INSTANCE = Mappers.getMapper(PermissionMapper.class);
	
	
	PermissionResponse toDto(Permission permission);
	
	List<PermissionResponse>toDtoList(List<Permission> permissions);
}