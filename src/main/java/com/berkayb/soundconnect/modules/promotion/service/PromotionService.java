package com.berkayb.soundconnect.modules.promotion.service;


import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionSaveRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionUpdateRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;

import java.util.List;
import java.util.UUID;

public interface PromotionService {
	
	// promotion kaydetme
	PromotionResponseDto save(PromotionSaveRequestDto dto);
	
	// promotion kaydini guncelle
	PromotionResponseDto update(UUID id, PromotionUpdateRequestDto dto);
	
	// promotion kaydini id'ye gore getir
	PromotionResponseDto getById(UUID id);
	
	// gosterime uygun promotionlari placement alanina gore getir
	List<PromotionResponseDto> getDisplayableByPlacement(PromotionPlacement placement);
	
	// belirli bir placement alanindaki tum promotion kayitlarini getir
	List<PromotionResponseDto> getAllByPlacement(PromotionPlacement placement);
	
	// belirli bir statuse sahip tum promotion kayitlarini getir
	List<PromotionResponseDto> getAllByStatus(PromotionStatus status);
	
	// belirli bir type'a sahip tum promotion kayitlarini getir
	List<PromotionResponseDto> getAllByType(PromotionType type);
	
	// promotion kaydini id'ye gore sil
	void deleteById(UUID id);
}