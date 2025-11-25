package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BandServiceImpl implements BandService{
	
	private final BandRepository bandRepository;
	private final BandMemberRepository bandMemberRepository;
	private final UserEntityFinder userEntityFinder;
	private final BandEntityFinder bandEntityFinder;
	private final BandMapper bandMapper;
	
	
	@Override
	public Band getBandEntity(UUID bandId) {
		return bandRepository.findById(bandId)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}
	
	
	@Override
	@Transactional
	public void inviteMember(UUID bandId, UUID inviterId, UUID invitedUserId, String message) {
		// bandi getir
		Band band = bandEntityFinder.getBand(bandId);
		// davet eden ve davet edilen user'lari getir
		User inviter = userEntityFinder.getUser(inviterId);
		User invited = userEntityFinder.getUser(invitedUserId);
		
		// davet eden kisinin yetkisi founder olmali
		BandMember inviterMember = bandMemberRepository.findByBandIdAndUserId(bandId, inviterId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		if (inviterMember.getBandRole() != BandRole.FOUNDER) {
			log.warn("Kullanıcı yetkisiz davet girişimi: inviterId={}, bandId={}", inviterId, bandId);
			throw new SoundConnectException(ErrorType.BAND_INVITE_UNAUTHORIZED);
		}
		
		// davet edilen kisi zaten uye veya pending ise tekrar davet etme
		bandMemberRepository.findByBandIdAndUserId(bandId, invitedUserId).ifPresent(existing -> {
			log.warn("Kullanici zaten bandde: userId={}, bandId={}", invitedUserId, bandId);
			throw new SoundConnectException(ErrorType.BAND_MEMBER_ALREADY_EXISTS);
		});
		
		// BandMember olustur (pending)
		BandMember invite = BandMember.builder()
				.band(band)
				.user(invited)
				.bandRole(BandRole.MEMBER)
				.status(BandMemberShipStatus.PENDING)
				.build();
		
		bandMemberRepository.save(invite);
		
		log.info("Band daveti gönderildi. inviter={}, invited={}, band={}", inviterId, invitedUserId, bandId);
		
		//TODO bildirim tetikleme burda yapilcak
	}
	
	@Override
	@Transactional
	public void acceptInvite(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		member.setStatus(BandMemberShipStatus.ACTIVE);
		bandMemberRepository.save(member);
		
		log.info("Band daveti kabul edildi. userId={}, bandId={}", userId, bandId);
	}
	
	@Override
	@Transactional
	public void rejectInvite(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		if (member.getStatus() != BandMemberShipStatus.PENDING) {
			throw new SoundConnectException(ErrorType.BAND_INVITE_STATUS_INVALID);
		}
		member.setStatus(BandMemberShipStatus.REJECTED);
		bandMemberRepository.save(member);
		
		log.info("Band daveti reddedildi. userId={}, bandId={}", userId, bandId);
	}
	
	@Override
	public void removeMember(UUID bandId, UUID requesterId, UUID targetUserId) {
		// sadece founder uyeleri cikarabilir
		BandMember requester = bandMemberRepository.findByBandIdAndUserId(bandId, requesterId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		if (requester.getBandRole() != BandRole.FOUNDER) {
			log.warn("Yetkisiz cikarma girisimi: requesterId={}, bandId={}", requesterId, bandId);
			throw new SoundConnectException(ErrorType.BAND_REMOVE_UNAUTHORIZED);
		}
		
		BandMember member = bandMemberRepository.findByBandIdAndUserId(bandId,targetUserId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		
		// founder baska founderi cikaramasin
		if (member.getBandRole() == BandRole.FOUNDER)
			throw new SoundConnectException(ErrorType.BAND_CANNOT_REMOVE_FOUNDER);
		
			member.setStatus(BandMemberShipStatus.LEFT);
			bandMemberRepository.save(member);
		
		log.info("Band üyesi çıkarıldı. memberId={}, bandId={}", targetUserId, bandId);
		
	}
	
	@Override
	public void leaveBand(UUID bandId, UUID userId) {
		BandMember member = bandEntityFinder.getBandMember(bandId, userId);
		
		// founder ise cikamasin once baska founder atanmasi gerek
		if (member.getBandRole() == BandRole.FOUNDER) {
			throw new SoundConnectException(ErrorType.BAND_FOUNDER_CANNOT_LEAVE);
		}
		
		member.setStatus(BandMemberShipStatus.LEFT);
		bandMemberRepository.save(member);
	}
	
	// band olusturur. olusturan kullanici otomatik olarak founder ve active statusunde uye olur
	@Override
	@Transactional
	public BandResponseDto createBand(UUID userId, BandCreateRequestDto dto) {
		// kullaniciyi getir
		User user = userEntityFinder.getUser(userId);
		
		// band adi daha once kullanilmis mi?
		bandRepository.findByName(dto.name()).ifPresent(existing -> {
			log.warn("Band adi daha once kullanilmis. Band: {}", dto.name());
			throw new SoundConnectException(ErrorType.BAND_ALREADY_EXISTS);
		});
		
		// yeni band entitsini kur
		Band band = Band.builder()
				.name(dto.name())
				.description(dto.description())
				.profilePicture(dto.profilePicture())
				.instagramUrl(dto.instagramUrl())
				.youtubeUrl(dto.youtubeUrl())
				.soundCloudUrl(dto.soundCloudUrl())
				.spotifyArtistId(dto.spotifyArtistId())
				.build();
		
		// band founder'ii olarak ilk uyeyi ekle
		BandMember founderMember = BandMember.builder()
				.band(band)
				.user(user)
				.bandRole(BandRole.FOUNDER)
				.status(BandMemberShipStatus.ACTIVE)
				.build();
		
		// band ve uyelik iliskisini kur
		band.setMembers(new HashSet<>(List.of(founderMember)));
		
		// kaydet
		bandRepository.save(band); // cascade ile bandmember da kaydedilir
		log.info("Yeni band olusturuldu. [name: {}, founder: {}]", dto.name(), user.getId());
		
		// response DTO ile dondur
		return bandMapper.toDto(band);
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<BandResponseDto> getBandsByUser(UUID userId) {
		List<BandMember> memberships = bandMemberRepository.findByUserIdAndStatus(
				userId,
				BandMemberShipStatus.ACTIVE
		);
		
		return memberships.stream()
		                  .map(m -> bandMapper.toDto(m.getBand()))
		                  .toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public BandResponseDto getBandById(UUID bandId, UUID userId) {
		// kullanici band uyesi mi?
		BandMember member = bandMemberRepository.findByBandIdAndUserId(bandId, userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		if (member.getStatus() != BandMemberShipStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.BAND_MEMBER_NOT_ACTIVE);
		}
		// bandi getir ve dto yap
		Band band = member.getBand();
		return bandMapper.toDto(band);
	}
}