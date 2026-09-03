package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingPostMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OverthinkingPostServiceImpl implements OverthinkingPostService {
	
	private final OverthinkingPostRepository postRepository;
	private final OverthinkingRevealRequestRepository revealRequestRepository;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingArtistResolverService artistResolver;
	private final OverthinkingPostMapper postMapper;
	private final LikeService likeService;
	private final CommentService commentService;
	private final SpotifyApiClient spotifyApiClient;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	private static final Pattern SPOTIFY_TRACK_PATH = Pattern.compile("track/([A-Za-z0-9]+)");
	
	@Override
	@Transactional
	public void delete(UUID postId, UUID authorId) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = postRepository.findById(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.getAuthor().getId().equals(author.getId())) {
			log.warn("[Overthinking] Yetkisiz silme girisimi post={}, user={}", postId, authorId);
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		postRepository.delete(post);
		
		log.info("[Overthinking] Post silindi. post={}, user={}", postId, authorId);
	}
	
	@Override
	public OverthinkingPostResponseDto getById(UUID postId, UUID viewerId) {
		OverthinkingPost post = postRepository.findById(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		return toViewerAwareDto(post, viewerId);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getMyPosts(UUID userId, Pageable pageable) {
		userEntityFinder.getUser(userId);
		
		Page<OverthinkingPost> page = postRepository.findByAuthorId(userId, pageable);
		return mapPage(page, userId);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getPostsByArtist(UUID artistId, UUID viewerId, Pageable pageable) {
		Page<OverthinkingPost> page = postRepository.findByArtistId(artistId, pageable);
		return mapPage(page, viewerId);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable) {
		Page<OverthinkingPost> page = postRepository.findAll(pageable);
		return mapPage(page, viewerId);
	}
	
	@Override
	@Transactional
	public OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto dto) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = OverthinkingPost.builder()
		                                        .author(author)
		                                        .title(dto.title())
		                                        .content(dto.content())
		                                        .visibilityType(dto.visibilityType())
		                                        .spotifyTrackUrl(dto.spotifyTrackUrl())
		                                        .spotifyArtistId(dto.spotifyArtistId())
		                                        .spotifyTrackName(dto.spotifyTrackName())
		                                        .spotifyArtistName(dto.spotifyArtistName())
		                                        .spotifyAlbumImageUrl(dto.spotifyAlbumImageUrl())
		                                        .musicianTrackId(dto.musicianTrackId())
		                                        .bandTrackId(dto.bandTrackId())
		                                        .build();
		
		artistResolver.resolveAndSetArtist(post, dto);
		
		OverthinkingPost saved = postRepository.save(post);
		
		log.info("[Overthinking] Yeni post olusturuldu. author={}, post={}", authorId, saved.getId());
		
		return toViewerAwareDto(saved, authorId);
	}
	
	@Override
	@Transactional
	public OverthinkingPostResponseDto update(UUID postId, UUID authorId, OverthinkingPostSaveRequestDto dto) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = postRepository.findById(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.getAuthor().getId().equals(author.getId())) {
			log.warn("[Overthinking] Yetkisiz guncelleme girisimi post={}, user={}", postId, authorId);
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		if (dto.title() != null) post.setTitle(dto.title());
		if (dto.content() != null) post.setContent(dto.content());
		if (dto.visibilityType() != null) post.setVisibilityType(dto.visibilityType());
		
		post.setSpotifyTrackUrl(dto.spotifyTrackUrl());
		post.setSpotifyArtistId(dto.spotifyArtistId());
		post.setSpotifyTrackName(dto.spotifyTrackName());
		post.setSpotifyArtistName(dto.spotifyArtistName());
		post.setSpotifyAlbumImageUrl(dto.spotifyAlbumImageUrl());
		post.setMusicianTrackId(dto.musicianTrackId());
		post.setBandTrackId(dto.bandTrackId());
		
		post.setArtistId(null);
		post.setArtistType(null);
		
		artistResolver.resolveAndSetArtist(post, dto);
		
		OverthinkingPost updated = postRepository.save(post);
		
		log.info("[Overthinking] Post guncellendi. post={}, user={}", postId, authorId);
		
		return toViewerAwareDto(updated, authorId);
	}
	
	private Page<OverthinkingPostResponseDto> mapPage(Page<OverthinkingPost> page, UUID viewerId) {
		List<OverthinkingPost> posts = page.getContent();
		
		Map<UUID, Long> likeCounts = getLikeCounts(posts);
		Map<UUID, Long> commentCounts = getCommentCounts(posts);
		Set<UUID> likedPostIds = getLikedPostIds(posts, viewerId);
		Set<UUID> approvedRevealPostIds = getApprovedRevealPostIds(posts, viewerId);
		Map<String, SpotifyTrackItemDto> spotifyTracks = getSpotifyTracks(posts);
		Map<UUID, GhostListenerIdentity> ghostIdentities = getVisibleGhostIdentities(
				posts,
				viewerId,
				approvedRevealPostIds
		);
		
		return page.map(post -> toViewerAwareDto(
				post,
				viewerId,
				likeCounts,
				commentCounts,
				likedPostIds,
				approvedRevealPostIds,
				spotifyTracks,
				ghostIdentities
		));
	}
	
	private OverthinkingPostResponseDto toViewerAwareDto(OverthinkingPost post, UUID viewerId) {
		Map<UUID, Long> likeCounts = getLikeCounts(List.of(post));
		Map<UUID, Long> commentCounts = getCommentCounts(List.of(post));
		Set<UUID> likedPostIds = getLikedPostIds(List.of(post), viewerId);
		Set<UUID> approvedRevealPostIds = getApprovedRevealPostIds(List.of(post), viewerId);
		Map<String, SpotifyTrackItemDto> spotifyTracks = getSpotifyTracks(List.of(post));
		Map<UUID, GhostListenerIdentity> ghostIdentities = getVisibleGhostIdentities(
				List.of(post),
				viewerId,
				approvedRevealPostIds
		);
		
		return toViewerAwareDto(
				post,
				viewerId,
				likeCounts,
				commentCounts,
				likedPostIds,
				approvedRevealPostIds,
				spotifyTracks,
				ghostIdentities
		);
	}
	
	private OverthinkingPostResponseDto toViewerAwareDto(
			OverthinkingPost post,
			UUID viewerId,
			Map<UUID, Long> likeCounts,
			Map<UUID, Long> commentCounts,
			Set<UUID> likedPostIds,
			Set<UUID> approvedRevealPostIds,
			Map<String, SpotifyTrackItemDto> spotifyTracks,
			Map<UUID, GhostListenerIdentity> ghostIdentities
	) {
		boolean canViewAuthor = canViewAuthor(post, viewerId, approvedRevealPostIds);
		String spotifyTrackId = extractSpotifyTrackId(post.getSpotifyTrackUrl());
		GhostListenerIdentity ghostIdentity = canViewAuthor && post.getAuthor() != null
				? ghostIdentities.get(post.getAuthor().getId())
				: null;
		
		return postMapper.toDto(
				post,
				canViewAuthor,
				likeCounts.getOrDefault(post.getId(), 0L),
				commentCounts.getOrDefault(post.getId(), 0L),
				likedPostIds.contains(post.getId()),
				spotifyTrackId == null ? null : spotifyTracks.get(spotifyTrackId),
				ghostIdentity
		);
	}

	private Map<UUID, GhostListenerIdentity> getVisibleGhostIdentities(
			List<OverthinkingPost> posts,
			UUID viewerId,
			Set<UUID> approvedRevealPostIds
	) {
		if (posts == null || posts.isEmpty()) return Map.of();

		LinkedHashSet<UUID> visibleAuthorIds = new LinkedHashSet<>();
		for (OverthinkingPost post : posts) {
			if (post == null || post.getAuthor() == null || post.getAuthor().getId() == null) continue;
			if (canViewAuthor(post, viewerId, approvedRevealPostIds)) {
				visibleAuthorIds.add(post.getAuthor().getId());
			}
		}
		if (visibleAuthorIds.isEmpty()) return Map.of();
		return ghostIdentityBatchResolver.resolve(visibleAuthorIds);
	}
	
	private boolean canViewAuthor(OverthinkingPost post, UUID viewerId, Set<UUID> approvedRevealPostIds) {
		if (post.isVisible()) {
			return true;
		}
		
		if (viewerId == null) {
			return false;
		}
		
		if (post.getAuthor() != null && post.getAuthor().getId() != null
				&& post.getAuthor().getId().equals(viewerId)) {
			return true;
		}
		
		return approvedRevealPostIds.contains(post.getId());
	}
	
	private Map<UUID, Long> getLikeCounts(List<OverthinkingPost> posts) {
		List<UUID> postIds = extractPostIds(posts);
		if (postIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		return likeService.countLikesByTargets(
				EngagementTargetType.OVERTHINKING,
				postIds
		);
	}
	
	private Map<UUID, Long> getCommentCounts(List<OverthinkingPost> posts) {
		List<UUID> postIds = extractPostIds(posts);
		if (postIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		return commentService.countCommentsByTargets(
				EngagementTargetType.OVERTHINKING,
				postIds
		);
	}
	
	private Set<UUID> getLikedPostIds(List<OverthinkingPost> posts, UUID viewerId) {
		if (viewerId == null) {
			return Collections.emptySet();
		}
		
		List<UUID> postIds = extractPostIds(posts);
		if (postIds.isEmpty()) {
			return Collections.emptySet();
		}
		
		return likeService.findLikedTargetIds(
				viewerId,
				EngagementTargetType.OVERTHINKING,
				postIds
		);
	}
	
	private List<UUID> extractPostIds(List<OverthinkingPost> posts) {
		if (posts == null || posts.isEmpty()) {
			return List.of();
		}
		
		return posts.stream()
		            .map(OverthinkingPost::getId)
		            .toList();
	}
	
	private Set<UUID> getApprovedRevealPostIds(List<OverthinkingPost> posts, UUID viewerId) {
		if (viewerId == null) {
			return Collections.emptySet();
		}
		
		List<UUID> postIds = extractPostIds(posts);
		if (postIds.isEmpty()) {
			return Collections.emptySet();
		}
		
		return revealRequestRepository.findPostIdsByRequesterIdAndStatusAndPostIdIn(
				viewerId,
				OverthinkingRevealRequestStatus.APPROVED,
				postIds
		);
	}
	
	private Map<String, SpotifyTrackItemDto> getSpotifyTracks(List<OverthinkingPost> posts) {
		List<String> trackIds = posts.stream()
		                             .map(post -> extractSpotifyTrackId(post.getSpotifyTrackUrl()))
		                             .filter(Objects::nonNull)
		                             .distinct()
		                             .toList();
		if (trackIds.isEmpty()) {
			return Collections.emptyMap();
		}
		
		try {
			return spotifyApiClient.getTracksByIds(trackIds)
			                       .stream()
			                       .filter(track -> track.spotifyTrackId() != null && !track.spotifyTrackId().isBlank())
			                       .collect(Collectors.toMap(
					                       SpotifyTrackItemDto::spotifyTrackId,
					                       track -> track,
					                       (first, ignored) -> first,
					                       HashMap::new
			                       ));
		} catch (Exception ex) {
			log.warn("[Overthinking] Spotify metadata could not be resolved for tracks={}", trackIds, ex);
			return Collections.emptyMap();
		}
	}
	
	private String extractSpotifyTrackId(String spotifyTrackUrl) {
		if (spotifyTrackUrl == null || spotifyTrackUrl.isBlank()) {
			return null;
		}
		
		String value = spotifyTrackUrl.trim();
		var matcher = SPOTIFY_TRACK_PATH.matcher(value);
		if (matcher.find()) {
			return matcher.group(1);
		}
		
		return value.matches("[A-Za-z0-9]+") ? value : null;
	}
}
