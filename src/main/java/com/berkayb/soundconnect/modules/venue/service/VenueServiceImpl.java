package com.berkayb.soundconnect.modules.venue.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.mapper.VenueMapper;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {
	private static final int SEARCH_QUERY_MAX_LENGTH = 100;
	private static final int SEARCH_PAGE_MAX = 1000;
	private static final int SEARCH_PAGE_SIZE_MAX = 100;
	
	private final VenueRepository venueRepository;
	private final VenueMapper venueMapper;
	private final LocationEntityFinder locationEntityFinder;
	private final VenueEntityFinder venueEntityFinder;
	private final RoleRepository roleRepository;
	private final UserRepository userRepository;
	private final VenueProfileService venueProfileService;
	private final PersonalProfileTypePolicy personalProfileTypePolicy;
	
	@Override
	@Transactional(readOnly = true)
	public Page<VenueResponseDto> searchByName(String q, Pageable pageable) {
		String query = q == null ? "" : q.trim();
		if (query.isEmpty()) {
			throw new SoundConnectException(ErrorType.VENUE_SEARCH_QUERY_REQUIRED);
		}
		if (query.length() > SEARCH_QUERY_MAX_LENGTH) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"Venue search query cannot exceed " + SEARCH_QUERY_MAX_LENGTH + " characters"
			);
		}
		if (pageable == null
				|| pageable.getPageNumber() < 0
				|| pageable.getPageNumber() > SEARCH_PAGE_MAX
				|| pageable.getPageSize() < 1
				|| pageable.getPageSize() > SEARCH_PAGE_SIZE_MAX) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"Venue search page must be between 0 and " + SEARCH_PAGE_MAX
							+ " and size must be between 1 and " + SEARCH_PAGE_SIZE_MAX
			);
		}
		
		return venueRepository
				.searchByName(
						query,
						PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
				)
				.map(venueMapper::toResponse);
	}
	
	@Transactional
	@Override
	public VenueResponseDto save(VenueRequestDto dto) {
		log.info("Saving venue: {}", dto.name());
		
		City city = locationEntityFinder.getCity(dto.cityId());
		District district = locationEntityFinder.getDistrict(dto.districtId());
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(dto.neighborhoodId());
		// ROLE_VENUE assignment shares the user-row serialization point with
		// TableGroup admission checks and application-based venue approval.
		User owner = personalProfileTypePolicy.lockAndAssertCanAcquire(
				dto.ownerId(), RoleEnum.ROLE_VENUE);
		
		// degistirildi: owner'in zaten venue'su varsa ikinci venue olusturulmasi engellenir
		if (venueRepository.existsByOwner_Id(owner.getId())) {
			log.warn("User already has a venue. ownerId={}", owner.getId());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
		// degistirildi: district secilen city'ye ait olmali
		if (!district.getCity().getId().equals(city.getId())) {
			log.warn("District does not belong to city. cityId={}, districtId={}", city.getId(), district.getId());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
		// degistirildi: neighborhood secilen district'e ait olmali
		if (!neighborhood.getDistrict().getId().equals(district.getId())) {
			log.warn("Neighborhood does not belong to district. districtId={}, neighborhoodId={}",
			         district.getId(), neighborhood.getId());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
		Venue venue = venueMapper.toEntity(dto, city, district, neighborhood, owner);
		venue.setStatus(VenueStatus.APPROVED); // Admin CRUD'da otomatik onaylı
		
		// mekan kaydi yap
		Venue savedVenue = venueRepository.save(venue);
		
		Role venueRole = roleRepository.findByName(RoleEnum.ROLE_VENUE.name())
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
	@Transactional
	public VenueResponseDto update(UUID id, VenueRequestDto dto) {
		log.info("Updating venue with id: {}", id);
		
		Venue venue = venueEntityFinder.getVenue(id);
		User owner = venue.getOwner();
		if (owner == null || owner.getId() == null) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		if (!Objects.equals(owner.getId(), dto.ownerId())) {
			throw new SoundConnectException(ErrorType.VENUE_OWNER_IMMUTABLE);
		}
		
		City city = locationEntityFinder.getCity(dto.cityId());
		District district = locationEntityFinder.getDistrict(dto.districtId());
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(dto.neighborhoodId());
		
		// degistirildi: district secilen city'ye ait olmali
		if (!district.getCity().getId().equals(city.getId())) {
			log.warn("District does not belong to city. cityId={}, districtId={}", city.getId(), district.getId());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
		// degistirildi: neighborhood secilen district'e ait olmali
		if (!neighborhood.getDistrict().getId().equals(district.getId())) {
			log.warn("Neighborhood does not belong to district. districtId={}, neighborhoodId={}",
			         district.getId(), neighborhood.getId());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
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
	@Transactional(readOnly = true)
	public List<VenueResponseDto> findAll() {
		log.info("Retrieving all venues");
		return venueMapper.toResponseList(venueRepository.findAllPubliclyVisible());
	}
	
	@Override
	@Transactional(readOnly = true)
	public VenueResponseDto findById(UUID id) {
		log.info("Finding venue by id: {}", id);
		Venue venue = venueRepository.findPubliclyVisibleById(id)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
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
