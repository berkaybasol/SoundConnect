package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.Local;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TableGroupServiceImpl implements TableGroupService{
	
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupMapper tableGroupMapper;
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	
	
	@Override
	public TableGroupResponseDto createTableGroup(UUID ownerId, TableGroupCreateRequestDto requestDto) {
		// Validasyonlar
		if (requestDto.venueId() != null && requestDto.venueName() != null && !requestDto.venueName().isBlank()) {
			throw new SoundConnectException(ErrorType.VENUE_ID_AND_NAME_CONFLICT);
		}
		if ((requestDto.venueId() == null) && (requestDto.venueName() == null || requestDto.venueName().isBlank())) {
			throw new SoundConnectException(ErrorType.VENUE_INFORMATION_REQUIRED);
		}
		if (requestDto.ageMin() > requestDto.ageMax()) {
			throw new SoundConnectException(ErrorType.INVALID_AGE_RANGE);
		}
		if (requestDto.genderPrefs().size() != requestDto.maxPersonCount()) {
			throw new SoundConnectException(ErrorType.GENDER_AND_COUNT_MISMATCH);
		}
		if (requestDto.expiresAt().isBefore(LocalDateTime.now())){
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
		// Location lookup
		City city = cityRepository.findById(requestDto.cityId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.CITY_NOT_FOUND));
		
		District district = null;
		if (requestDto.districtId() != null) {
			district = districtRepository.findById(requestDto.districtId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND));
			if (!district.getCity().getId().equals(city.getId())) {
				throw new SoundConnectException(ErrorType.DISTRICT_CITY_MISMATCH);
			}
		}
		Neighborhood neighborhood = null;
		if (requestDto.neighborhoodId() != null) {
			neighborhood = neighborhoodRepository.findById(requestDto.neighborhoodId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.NEIGHBORHOOD_NOT_FOUND));
			if (district != null && !neighborhood.getDistrict().getId().equals(district.getId()) ) {
				throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
			}
		}
		
		// Entity mapping
		TableGroup entity = tableGroupMapper.toEntity(requestDto);
		entity.setOwnerId(ownerId);
		entity.setStatus(TableGroupStatus.ACTIVE);
		entity.setCity(city);
		entity.setDistrict(district);
		entity.setNeighborhood(neighborhood);
		
		// owner katilimci olarak eklencek yani her zaman accepted
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
				.userId(ownerId)
				.joinedAt(LocalDateTime.now())
				.status(ParticipantStatus.ACCEPTED)
				.build();
		entity.getParticipants().add(ownerParticipant);
		
		entity = tableGroupRepository.save(entity);
		log.info("TableGroup oluşturuldu: id={}, owner={}, venueId={}, venueName={}, city={}",
		         entity.getId(), ownerId, entity.getVenueId(), entity.getVenueName(), city.getName());
		
		// TODO NOTIFICATION HOOK
		// notificationEventPublisher.publish(...);
		
		return tableGroupMapper.toDto(entity);
	}
	
	@Override
	public Page<TableGroupResponseDto> listActiveTableGroups(UUID cityId, UUID districtId, UUID neighborhoodId, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Page<TableGroup> page;
		
		if (neighborhoodId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(
					cityId, districtId, neighborhoodId, TableGroupStatus.ACTIVE, now, pageable
			);
		}
		else if (districtId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(
					cityId, districtId, TableGroupStatus.ACTIVE, now, pageable
			);
		} else  {
			page = tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
					cityId, TableGroupStatus.ACTIVE, now, pageable
			);
		}
		return page.map(tableGroupMapper::toDto);
	}
	
	@Override
	public TableGroupResponseDto getTableGroupDetail(UUID tableGroupId) {
		TableGroup entity = tableGroupRepository.findById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
				return tableGroupMapper.toDto(entity);
	}
}