package com.berkayb.soundconnect.modules.tablegroup.service;

import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
import com.berkayb.soundconnect.modules.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.mapper.TableGroupMapper;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.Local;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TableGroupServiceImpl implements TableGroupService{
	
	private final NotificationProducer notificationProducer;
	private final TableGroupRepository tableGroupRepository;
	private final TableGroupMapper tableGroupMapper;
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	private final TableGroupEntityFinder tableGroupEntityFinder;
	
	// Owner bir katilimciyi masadan kickler
	@Override
	public void removeParticipantFromTableGroup(UUID ownerId, UUID tableGroupId, UUID participantId) {
		
		// masa var mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		if (!tableGroup.getOwnerId().equals(ownerId)) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		TableGroupParticipant participant = tableGroup.getParticipants().stream()
				.filter(p -> p.getUserId().equals(participantId) && p.getStatus() == ParticipantStatus.ACCEPTED)
				.findFirst()
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		
		participant.setStatus(ParticipantStatus.KICKED);
		tableGroupRepository.save(tableGroup);
		
		notificationProducer.publish(
				NotificationInboundEvent.builder()
						.recipientId(participantId)
						.type(NotificationType.TABLE_REMOVED)
						.title("masadan cikarildin")
						.message("bir masa etkinliginden cikarildin")
						.payload(Map.of(
								"tableGroupId",tableGroupId,
								"ownerId", ownerId
						))
						.build()
		);
		log.info("Participant {} kicked from tableGroup {}", participantId, tableGroupId);
	}
	
	// Owner masayi iptal eder ve kabul edilmis kullanicilara bildirim gider
	@Override
	public void cancelTableGroup(UUID ownerId, UUID tableGroupId) {
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		if (!tableGroup.getOwnerId().equals(ownerId)) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		tableGroup.setStatus(TableGroupStatus.CANCELLED);
		tableGroupRepository.save(tableGroup);
		
		tableGroup.getParticipants().stream()
		          .filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED && !p.getUserId().equals(ownerId))
		          .forEach(participant -> {
			          notificationProducer.publish(
					          NotificationInboundEvent.builder()
					                                  .recipientId(participant.getUserId())
					                                  .type(NotificationType.TABLE_CANCELLED)
					                                  .title("Masa iptal edildi")
					                                  .message("Katıldığın masa etkinliği iptal edildi.")
					                                  .payload(Map.of(
							                                  "tableGroupId", tableGroupId
					                                  ))
					                                  .build()
			          );
		          });
		log.info("TableGroup {} cancelled by owner {}", tableGroupId, ownerId);
	}
	
	// kullanicinin masaya katilma basvurusu
	@Override
	public void joinTableGroup(UUID userId, UUID tableGroupId) {
		// masa var mi aktif mi ve suresi gecmis mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		if (tableGroup.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
		
		// katilimci limiti dolmus mu?
		long activeParticipants = tableGroup.getParticipants().stream()
				.filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED || p.getStatus() == ParticipantStatus.PENDING)
				.count();
		if (activeParticipants >= tableGroup.getMaxPersonCount()) {
			throw new SoundConnectException(ErrorType.MAX_PARTICIPANT_LIMIT);
		}
		
		// zaten katilimci mi?
		boolean alreadyParticipant = tableGroup.getParticipants().stream()
				.anyMatch(p-> p.getUserId().equals(userId) &&
						(p.getStatus() == ParticipantStatus.ACCEPTED || p.getStatus() == ParticipantStatus.PENDING));
		
		if (alreadyParticipant) {
			throw new SoundConnectException(ErrorType.ALREADY_PARTICIPANT);
		}
		
		// katilim istegi olarak yeni participant ekle (pending)
		TableGroupParticipant joinRequest = TableGroupParticipant.builder()
				.userId(userId)
				.joinedAt(LocalDateTime.now())
				.status(ParticipantStatus.PENDING)
				.build();
		
		tableGroup.getParticipants().add(joinRequest);
		tableGroupRepository.save(tableGroup);
		
		log.info("Join request: user={} tableGroup={} status={}", userId, tableGroupId, joinRequest.getStatus());
		
		// Notification Event (owner'a basvuru bildirimi fire et)
		if (!tableGroup.getOwnerId().equals(userId)) {
			notificationProducer.publish(
					NotificationInboundEvent.builder()
					                        .recipientId(tableGroup.getOwnerId())
					                        .type(NotificationType.TABLE_JOIN_REQUEST_RECEVIED)
					                        .title("Yeni masa başvurusu!")
					                        .message("Masana yeni bir başvuru geldi. Katılımcı onayı bekliyor.")
					                        .payload(Map.of(
							                        "tableGroupId", tableGroup.getId(),
							                        "applicantId", userId
					                        ))
					                        .build()
			);
		}
	}
	
	// owner basvurani kabul eder
	@Override
	public void approveJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId) {
		// masa var mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		if (!tableGroup.getOwnerId().equals(ownerId)) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE || tableGroup.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND);
		}
		
		// participant pending mi?
		TableGroupParticipant participant = tableGroup.getParticipants().stream()
				.filter(p -> p.getUserId().equals(participantId) && p.getStatus() == ParticipantStatus.PENDING)
				.findFirst()
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
				
		// katilimci limiti dolmus mu
		long activeCount = tableGroup.getParticipants().stream()
				.filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED)
				.count();
		if (activeCount >= tableGroup.getMaxPersonCount()) {
			throw new SoundConnectException(ErrorType.MAX_PARTICIPANT_LIMIT);
		}
		
		participant.setStatus(ParticipantStatus.ACCEPTED);
		tableGroupRepository.save(tableGroup);
		
		log.info("Join request APPROVED: tableGroup={}, participant={}", tableGroupId, participantId);
		
		notificationProducer.publish(
				NotificationInboundEvent.builder()
						.recipientId(participantId)
						.type(NotificationType.TABLE_JOIN_REQUEST_APPROVED)
						.title("Basvurun onaylandi")
						.message("Katildigin masa basvurun onaylandi")
						.payload(Map.of(
								"tableGroupId",tableGroup.getId(),
								"ownerId", ownerId
						))
						.build()
		);
		
	}
	
	// owner basvuruyu reddeder
	@Override
	public void rejectJoinRequest(UUID ownerId, UUID tableGroupId, UUID participantId) {
		// masa ve owner kontrolu
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		if (!tableGroup.getOwnerId().equals(ownerId)) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED,"Sadece owner onaylayabilir.");
		}
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE || tableGroup.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND, "Masa aktif degil");
		}
		
		// participant pending mi?
		TableGroupParticipant participant = tableGroup.getParticipants().stream()
				.filter(p -> p.getUserId().equals(participantId) && p.getStatus() == ParticipantStatus.PENDING)
				.findFirst()
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND));
		
		
		// status rejectle
		participant.setStatus(ParticipantStatus.REJECTED);
		
		tableGroupRepository.save(tableGroup);
		log.info("Join request REJECTED: tableGroup={}, participant={}", tableGroupId, participantId);
		
		notificationProducer.publish(
				NotificationInboundEvent.builder()
						.recipientId(participantId)
						.type(NotificationType.TABLE_JOIN_REQUEST_REJECTED)
						.title("Basvurun reddedildi")
						.message("Katildigin masa basvurun reddedildi")
						.payload(Map.of(
								"tableGroupId",tableGroup.getId(),
								"ownerId", ownerId
						))
						.build()
		);
	}
	
	// kullanici masadan ayrilir (owner ayrilamaz)
	@Override
	public void leaveTableGroup(UUID userId, UUID tableGroupId) {
		// masa var mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		// kullanici owner mi ownersa masadan ayrilamaz
		if (tableGroup.getOwnerId().equals(userId)) {
			throw new SoundConnectException(ErrorType.OWNER_CANNOT_LEAVE);
		}
		
		// kullanici participant mi? oyleyse accepted olanlar ayrilabilir
		TableGroupParticipant participant = tableGroup.getParticipants().stream()
				.filter(p -> p.getUserId().equals(userId) && p.getStatus() == ParticipantStatus.ACCEPTED)
				.findFirst()
				.orElseThrow(() -> new SoundConnectException(ErrorType.PARTICIPANT_NOT_FOUND, "Kullanici aktif bir " +
						"katilimci degil"));
		// statuyu left yap
		participant.setStatus(ParticipantStatus.LEFT);
		
		tableGroupRepository.save(tableGroup);
		log.info("User {} left table group {}", userId, tableGroupId);
		
		notificationProducer.publish(
				NotificationInboundEvent.builder()
						.recipientId(tableGroup.getOwnerId())
						.type(NotificationType.TABLE_PARTICIPANT_LEFT)
						.title("Katilimci ayrildi")
						.message("Masandaki bir katilimci ayrildi")
						.payload(Map.of(
								"tableGroupId",tableGroupId,
								"leaverId",userId
						))
						.build()
		);
		
		
	}
	
	// yeni masa olusturma owner otomatik accepted.
	@Override
	public TableGroupResponseDto createTableGroup(UUID ownerId, TableGroupCreateRequestDto requestDto) {
		// Validasyonlar
		if (requestDto.venueId() != null &&
				requestDto.venueName() != null &&
				!requestDto.venueName().isBlank()) {
			throw new SoundConnectException(ErrorType.VENUE_ID_AND_NAME_CONFLICT);
		}
		
		if (requestDto.venueId() == null &&
				(requestDto.venueName() == null || requestDto.venueName().isBlank())) {
			throw new SoundConnectException(ErrorType.VENUE_INFORMATION_REQUIRED);
		}
		
		// Yaş aralığı
		if (requestDto.ageMin() > requestDto.ageMax()) {
			throw new SoundConnectException(ErrorType.INVALID_AGE_RANGE);
		}
		
		// Cinsiyet dağılımı kişi sayısıyla uyuşmalı
		if (requestDto.genderPrefs().size() != requestDto.maxPersonCount()) {
			throw new SoundConnectException(ErrorType.GENDER_AND_COUNT_MISMATCH);
		}
		
		// Bitiş zamanı geçmiş tarih olamaz
		if (requestDto.expiresAt().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED);
		}
		
		// Lokasyon doğrulama
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
			
			if (district != null &&
					!neighborhood.getDistrict().getId().equals(district.getId())) {
				throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
			}
		}
		
		// Entity build
		TableGroup entity = tableGroupMapper.toEntity(requestDto);
		entity.setOwnerId(ownerId);
		entity.setStatus(TableGroupStatus.ACTIVE);
		entity.setCity(city);
		entity.setDistrict(district);
		entity.setNeighborhood(neighborhood);
		
		// Owner'ı otomatik ACCEPTED participant olarak ekle
		TableGroupParticipant ownerParticipant = TableGroupParticipant.builder()
		                                                              .userId(ownerId)
		                                                              .joinedAt(LocalDateTime.now())
		                                                              .status(ParticipantStatus.ACCEPTED)
		                                                              .build();
		
		entity.getParticipants().add(ownerParticipant);
		
		entity = tableGroupRepository.save(entity);
		
		log.info("TableGroup created: id={}, owner={}, venueId={}, venueName={}, city={}",
		         entity.getId(), ownerId, entity.getVenueId(), entity.getVenueName(),
		         city.getName()
		);
		
		return tableGroupMapper.toDto(entity);
	}
	
	// aktif masalari lokasyona gore listele.
	@Override
	public Page<TableGroupResponseDto> listActiveTableGroups(
			UUID cityId,
			UUID districtId,
			UUID neighborhoodId,
			Pageable pageable
	) {
		LocalDateTime now = LocalDateTime.now();
		Page<TableGroup> page;
		
		if (neighborhoodId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(
					cityId,
					districtId,
					neighborhoodId,
					TableGroupStatus.ACTIVE,
					now,
					pageable
			);
		} else if (districtId != null) {
			page = tableGroupRepository.findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(
					cityId,
					districtId,
					TableGroupStatus.ACTIVE,
					now,
					pageable
			);
		} else {
			page = tableGroupRepository.findByCityIdAndStatusAndExpiresAtAfter(
					cityId,
					TableGroupStatus.ACTIVE,
					now,
					pageable
			);
		}
		
		return page.map(tableGroupMapper::toDto);
	}
	
	// tek masa detayi
	@Override
	public TableGroupResponseDto getTableGroupDetail(UUID tableGroupId) {
		TableGroup entity = tableGroupRepository.findById(tableGroupId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND));
				return tableGroupMapper.toDto(entity);
	}
	
	public void expireExpiredTableGroups() {
		LocalDateTime now = LocalDateTime.now();
		
		var expiredActives = tableGroupRepository.findByStatusAndExpiresAtBefore(
				TableGroupStatus.ACTIVE,
				now
		);
		
		if (expiredActives.isEmpty()) {
			return;
		}
		
		expiredActives.forEach(group -> {
			expireTableGroup(group);
		});
		
		log.info("Expired {} table groups at {}", expiredActives.size(), now);
	}
	
	private void expireTableGroup(TableGroup group) {
		// status ACTIVE -> INACTIVE
		group.setStatus(TableGroupStatus.INACTIVE);
		tableGroupRepository.save(group);
		
		// Accepted katilimcilara (owner haric) masa suresi doldu bildirimi gonder
		group.getParticipants().stream()
		     .filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED)
		     .filter(p -> !p.getUserId().equals(group.getOwnerId()))
		     .forEach(participant -> notificationProducer.publish(
				     NotificationInboundEvent.builder()
				                             .recipientId(participant.getUserId())
				                             .type(NotificationType.TABLE_EXPIRED)
				                             .title("Masa süresi doldu")
				                             .message("Katıldığın masa etkinliğinin süresi doldu.")
				                             .payload(Map.of(
						                             "tableGroupId", group.getId(),
						                             "ownerId", group.getOwnerId()
				                             ))
				                             .build()
		     ));
		
		log.debug(
				"TableGroup {} marked INACTIVE (expiredAt={}), notifications sent to accepted participants",
				group.getId(),
				group.getExpiresAt()
		);
		
	}
}