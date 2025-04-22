package com.berkayb.soundconnect.location.mapper;

import com.berkayb.soundconnect.location.dto.response.*;
import com.berkayb.soundconnect.location.entity.City;
import com.berkayb.soundconnect.location.entity.District;
import com.berkayb.soundconnect.location.entity.Neighborhood;
import org.springframework.stereotype.Component;

import java.util.List;

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