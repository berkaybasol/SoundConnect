package com.berkayb.soundconnect.location.service;

import com.berkayb.soundconnect.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.location.dto.response.CityResponseDto;

import java.util.List;
import java.util.UUID;

public interface CityService {

CityResponseDto save(CityRequestDto dto);

List<CityResponseDto> findAll();

List<CityPrettyDto> findAllPretty();


CityResponseDto findById(UUID id);

void delete(UUID id);


}