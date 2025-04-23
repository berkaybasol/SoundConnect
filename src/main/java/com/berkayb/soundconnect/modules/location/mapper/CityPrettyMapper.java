package com.berkayb.soundconnect.modules.location.mapper;

import com.berkayb.soundconnect.modules.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictPrettyDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import org.springframework.stereotype.Component;

@Component
public class CityPrettyMapper {
	
	public CityPrettyDto toPretty(City city) {
		return new CityPrettyDto(
				city.getName(),
				city.getDistricts().stream().map(this::toPretty).toList()
		);
	}
	
	private DistrictPrettyDto toPretty(District district) {
		return new DistrictPrettyDto(
				district.getName(),
				district.getNeighborhoods().stream().map(Neighborhood::getName).toList()
		);
	}
}