package com.berkayb.soundconnect.modules.comment.mapper;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset; //eklendi
import com.berkayb.soundconnect.modules.media.service.MediaAssetService; //eklendi
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile; //eklendi
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository; //eklendi
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue; //eklendi
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository; //eklendi
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired; //eklendi

import java.util.List; //eklendi
import java.util.UUID; //eklendi

/**
 * Comment entity <-> DTO dönüşümleri için MapStruct mapper.
 */
@Mapper(componentModel = "spring")
public abstract class CommentMapper { //eklendi
	
	@Autowired //eklendi
	protected MediaAssetService mediaAssetService; //eklendi
	
	@Autowired //eklendi
	protected VenueRepository venueRepository; //eklendi
	
	@Autowired //eklendi
	protected VenueProfileRepository venueProfileRepository; //eklendi
	
	/**
	 * User entity'den UI'da kullanılacak minimal user özet DTO'su.
	 */
	@Mapping(target = "id", source = "id")
	@Mapping(target = "username", source = "username")
	@Mapping(target = "avatarUrl", expression = "java(resolveAvatarUrl(user))") //eklendi
	public abstract UserSummaryDto toUserSummaryDto(User user); //eklendi
	
	/**
	 * Root comment için Response DTO.
	 * replyCount servisten parametre olarak gelir.
	 */
	@Mapping(target = "user", expression = "java(toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	@Mapping(target = "replyCount", source = "replyCount")
	public abstract CommentResponseDto toCommentResponseDto(Comment comment, int replyCount); //eklendi
	
	/**
	 * Reply (cevap) yorumlar için Response DTO.
	 * replyCount içermiyor; sadece temel bilgiler.
	 */
	@Mapping(target = "user", expression = "java(toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	public abstract CommentReplyResponseDto toCommentReplyResponseDto(Comment comment); //eklendi
	
	protected String resolveAvatarUrl(User user) { //eklendi
		if (user == null) return null; //eklendi
		
		if (user.getMusicianProfile() != null &&
				user.getMusicianProfile().getProfilePictureMediaId() != null) { //eklendi
			String musicianAvatar = resolveMediaUrl(user.getMusicianProfile().getProfilePictureMediaId()); //eklendi
			if (musicianAvatar != null && !musicianAvatar.isBlank()) { //eklendi
				return musicianAvatar; //eklendi
			} //eklendi
		} //eklendi
		
		List<Venue> ownedVenues = venueRepository.findAllByOwnerId(user.getId()); //eklendi
		if (ownedVenues != null && !ownedVenues.isEmpty()) { //eklendi
			for (Venue venue : ownedVenues) { //eklendi
				VenueProfile venueProfile = venueProfileRepository.findByVenueId(venue.getId()).orElse(null); //eklendi
				if (venueProfile != null && venueProfile.getProfilePictureMediaId() != null) { //eklendi
					String venueAvatar = resolveMediaUrl(venueProfile.getProfilePictureMediaId()); //eklendi
					if (venueAvatar != null && !venueAvatar.isBlank()) { //eklendi
						return venueAvatar; //eklendi
					} //eklendi
				} //eklendi
			} //eklendi
		} //eklendi
		
		final String raw = user.getProfilePicture(); //eklendi
		if (raw == null || raw.isBlank()) return null; //eklendi
		
		if (raw.startsWith("http://") || raw.startsWith("https://")) { //eklendi
			return raw; //eklendi
		} //eklendi
		
		try { //eklendi
			UUID assetId = UUID.fromString(raw); //eklendi
			return resolveMediaUrl(assetId); //eklendi
		} catch (Exception ignored) { //eklendi
		} //eklendi
		
		return raw; //eklendi
	} //eklendi
	
	protected String resolveMediaUrl(UUID mediaId) { //eklendi
		if (mediaId == null) return null; //eklendi
		try { //eklendi
			MediaAsset asset = mediaAssetService.getById(mediaId); //eklendi
			if (asset.getSourceUrl() != null && !asset.getSourceUrl().isBlank()) { //eklendi
				return asset.getSourceUrl(); //eklendi
			} //eklendi
			if (asset.getPlaybackUrl() != null && !asset.getPlaybackUrl().isBlank()) { //eklendi
				return asset.getPlaybackUrl(); //eklendi
			} //eklendi
		} catch (Exception ignored) { //eklendi
		} //eklendi
		return null; //eklendi
	} //eklendi
}