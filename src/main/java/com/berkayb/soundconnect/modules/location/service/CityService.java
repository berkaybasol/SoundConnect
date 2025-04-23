package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;

import java.util.List;
import java.util.UUID;

public interface CityService {

CityResponseDto save(CityRequestDto dto);

List<CityResponseDto> findAll();

List<CityPrettyDto> findAllPretty();


CityResponseDto findById(UUID id);

void delete(UUID id);


}