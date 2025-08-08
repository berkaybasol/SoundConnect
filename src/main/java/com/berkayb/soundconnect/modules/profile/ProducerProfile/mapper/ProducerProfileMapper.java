package com.berkayb.soundconnect.modules.profile.ProducerProfile.mapper;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.entity.ProducerProfile;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProducerProfileMapper {
	ProducerProfileResponseDto toDto(ProducerProfile producerProfile);
}