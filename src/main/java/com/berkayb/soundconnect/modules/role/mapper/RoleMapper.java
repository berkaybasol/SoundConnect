package com.berkayb.soundconnect.modules.role.mapper;

import com.berkayb.soundconnect.modules.role.dto.response.RoleResponse;
import com.berkayb.soundconnect.modules.role.entity.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring", uses = {PermissionMapper.class}) // permissionlari da otomatik mapleyebilmesi icin
public interface RoleMapper {
	RoleMapper INSTANCE = Mappers.getMapper(RoleMapper.class);
	
	// Role entity -> RoleResponse dönüşümünde Permission alanlarını da map et
	@Mapping(source = "permissions", target = "permissions")
	RoleResponse toDto(Role role);
	
	// Liste dönüşümü için MapStruct otomatik olarak tüm listeyi mapler
	List<RoleResponse> toDtoList(List<Role> roles);
}