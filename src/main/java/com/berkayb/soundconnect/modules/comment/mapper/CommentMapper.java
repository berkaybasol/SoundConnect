package com.berkayb.soundconnect.modules.comment.mapper;

import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

/**
 * Comment entity <-> DTO dönüşümleri için MapStruct mapper.
 */
@Mapper(componentModel = "spring")
public abstract class CommentMapper {
	
	@Autowired
	protected MediaAssetService mediaAssetService;
	
	@Autowired
	protected VenueRepository venueRepository;
	
	@Autowired
	protected VenueProfileRepository venueProfileRepository;
	
	protected UserSummaryDto toUserSummaryDto(User user) {
		if (user == null) {
			return null;
		}
		
		return new UserSummaryDto(
				user.getId(),
				user.getUsername(),
				resolveAvatarUrl(user)
		);
	}
	
	/**
	 * Root comment için Response DTO.
	 * maskAuthor true ise gerçek user bilgisi yerine anonim özet döner.
	 */
	@Mapping(target = "user", expression = "java(maskAuthor ? anonymousUserSummaryDto() : toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "anonymousAuthor", source = "maskAuthor")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	@Mapping(target = "replyCount", source = "replyCount")
	public abstract CommentResponseDto toCommentResponseDto(Comment comment, int replyCount, boolean maskAuthor);
	
	/**
	 * Reply yorumlar için Response DTO.
	 * maskAuthor true ise gerçek user bilgisi yerine anonim özet döner.
	 */
	@Mapping(target = "user", expression = "java(maskAuthor ? anonymousUserSummaryDto() : toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "anonymousAuthor", source = "maskAuthor")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	public abstract CommentReplyResponseDto toCommentReplyResponseDto(Comment comment, boolean maskAuthor);
	
	protected UserSummaryDto anonymousUserSummaryDto() {
		return new UserSummaryDto(
				null,
				"Anonymous Author",
				null
		);
	}
	
	protected String resolveAvatarUrl(User user) {
		if (user == null) return null;
		
		if (user.getMusicianProfile() != null &&
				user.getMusicianProfile().getProfilePictureMediaId() != null) {
			String musicianAvatar = resolveMediaUrl(user.getMusicianProfile().getProfilePictureMediaId());
			if (musicianAvatar != null && !musicianAvatar.isBlank()) {
				return musicianAvatar;
			}
		}
		
		List<Venue> ownedVenues = venueRepository.findAllByOwnerId(user.getId());
		if (ownedVenues != null && !ownedVenues.isEmpty()) {
			for (Venue venue : ownedVenues) {
				VenueProfile venueProfile = venueProfileRepository.findByVenueId(venue.getId()).orElse(null);
				if (venueProfile != null && venueProfile.getProfilePictureMediaId() != null) {
					String venueAvatar = resolveMediaUrl(venueProfile.getProfilePictureMediaId());
					if (venueAvatar != null && !venueAvatar.isBlank()) {
						return venueAvatar;
					}
				}
			}
		}
		
		final String raw = user.getProfilePicture();
		if (raw == null || raw.isBlank()) return null;
		
		if (raw.startsWith("http://") || raw.startsWith("https://")) {
			return raw;
		}
		
		try {
			UUID assetId = UUID.fromString(raw);
			return resolveMediaUrl(assetId);
		} catch (Exception ignored) {
		}
		
		return raw;
	}
	
	protected String resolveMediaUrl(UUID mediaId) {
		if (mediaId == null) return null;
		
		try {
			MediaAsset asset = mediaAssetService.getById(mediaId);
			
			if (asset.getSourceUrl() != null && !asset.getSourceUrl().isBlank()) {
				return asset.getSourceUrl();
			}
			
			if (asset.getPlaybackUrl() != null && !asset.getPlaybackUrl().isBlank()) {
				return asset.getPlaybackUrl();
			}
		} catch (Exception ignored) {
		}
		
		return null;
	}
}