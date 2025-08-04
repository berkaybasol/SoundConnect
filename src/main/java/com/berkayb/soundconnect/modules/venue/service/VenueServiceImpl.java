package com.berkayb.soundconnect.modules.venue.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.mapper.VenueMapper;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {
	
	private final VenueRepository venueRepository;
	private final VenueMapper venueMapper;
	private final LocationEntityFinder locationEntityFinder;
	private final UserEntityFinder userEntityFinder;
	private final VenueEntityFinder venueEntityFinder;
	private final RoleRepository roleRepository;
	private final UserRepository userRepository;
	private final VenueProfileService venueProfileService;
	
	@Transactional
	@Override
	public VenueResponseDto save(VenueRequestDto dto) {
		log.info("Saving venue: {}", dto.name());
		
		City city = locationEntityFinder.getCity(dto.cityId());
		District district = locationEntityFinder.getDistrict(dto.districtId());
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(dto.neighborhoodId());
		User owner = userEntityFinder.getUser(dto.ownerId());
		
		Venue venue = venueMapper.toEntity(dto, city, district, neighborhood, owner);
		venue.setStatus(VenueStatus.APPROVED); // Admin CRUD'da otomatik onaylı
		
		// mekan kaydi yap
		Venue savedVenue = venueRepository.save(venue);
		
		
		Role venueRole = roleRepository .findByName(RoleEnum.ROLE_VENUE.name())
		                               .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// eger zaten venue rolu yoksa ekle
		if (owner.getRoles().stream().noneMatch(r -> r.getName().equals(RoleEnum.ROLE_VENUE.name()))) {
			owner.getRoles().add(venueRole);
		}
		
		// status u aktif yap
		owner.setStatus(UserStatus.ACTIVE);
		
		// useri guncelle
		userRepository.save(owner);
		
		// venue profile olustur
		VenueProfileSaveRequestDto profileDto = new VenueProfileSaveRequestDto(
				null, // bio
				null, // profilePicture
				null, // instagramUrl
				null, // youtubeUrl
				null  // websiteUrl
		);
		venueProfileService.createProfile(savedVenue.getId(), profileDto);
		
		return venueMapper.toResponse(savedVenue);
	}
	
	@Override
	public VenueResponseDto update(UUID id, VenueRequestDto dto) {
		log.info("Updating venue with id: {}", id);
		
		Venue venue = venueEntityFinder.getVenue(id);
		
		City city = locationEntityFinder.getCity(dto.cityId());
		District district = locationEntityFinder.getDistrict(dto.districtId());
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(dto.neighborhoodId());
		User owner = userEntityFinder.getUser(dto.ownerId());
		
		// manuel mapping
		venue.setName(dto.name());
		venue.setAddress(dto.address());
		venue.setPhone(dto.phone());
		venue.setWebsite(dto.website());
		venue.setDescription(dto.description());
		venue.setMusicStartTime(dto.musicStartTime());
		venue.setCity(city);
		venue.setDistrict(district);
		venue.setNeighborhood(neighborhood);
		venue.setOwner(owner);
		
		Venue updated = venueRepository.save(venue);
		return venueMapper.toResponse(updated);
	}
	
	@Override
	public List<VenueResponseDto> findAll() {
		log.info("Retrieving all venues");
		return venueMapper.toResponseList(venueRepository.findAll());
	}
	
	@Override
	public VenueResponseDto findById(UUID id) {
		log.info("Finding venue by id: {}", id);
		Venue venue = venueEntityFinder.getVenue(id);
		return venueMapper.toResponse(venue);
	}
	
	@Override
	public void delete(UUID id) {
		log.info("Deleting venue by id: {}", id);
		if (!venueRepository.existsById(id)) {
			log.error("Venue not found for deletion: {}", id);
			throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		}
		venueRepository.deleteById(id);
	}
}