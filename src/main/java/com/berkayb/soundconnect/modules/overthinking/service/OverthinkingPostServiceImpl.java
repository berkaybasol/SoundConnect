package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingFeedOrder;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingPostMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingRevealRequestRepository;
import com.berkayb.soundconnect.modules.overthinking.support.OverthinkingPagination;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
	private final CommentAuthorBatchResolver authorIdentityResolver;
	private final CommentRepository commentRepository;
	private final LikeRepository likeRepository;
	private final OverthinkingPostCreationReceipts creationReceipts;
	private final OverthinkingRevealNotificationRetractionService notificationRetraction;
	
	@Override
	@Transactional
	public void delete(UUID postId, UUID authorId) {
		requireActiveActor(authorId);
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = postRepository.findByIdForUpdate(postId)
		                                      .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.getAuthor().getId().equals(author.getId())) {
			log.warn("[Overthinking] Yetkisiz silme girisimi post={}, user={}", postId, authorId);
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		
		// Engagement/reveal creation locks the same post first. Once this writer
		// owns it, no subordinate row can be inserted behind the cleanup.
		notificationRetraction.retractPost(authorId, postId);
		revealRequestRepository.deleteByPostId(postId);
		likeRepository.deleteOverthinkingCommentReferences(postId);
		commentRepository.deleteRepliesByTarget(EngagementTargetType.OVERTHINKING, postId);
		commentRepository.deleteRootsByTarget(EngagementTargetType.OVERTHINKING, postId);
		likeRepository.deleteOverthinkingTargetReferences(postId);
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
	public Map<UUID, OverthinkingPostResponseDto> getByIdsForViewer(UUID viewerId, List<UUID> postIds) {
		if (postIds.isEmpty()) return Map.of();
		if (postIds.size() > 50) throw new IllegalArgumentException("Overthinking projection page exceeds 50");
		return mapPage(new PageImpl<>(postRepository.findForViewerProjection(postIds)), viewerId).stream()
				.collect(Collectors.toMap(OverthinkingPostResponseDto::id, java.util.function.Function.identity()));
	}

	@Override
	public Page<OverthinkingPostResponseDto> getMyPosts(UUID userId, Pageable pageable) {
		userEntityFinder.getUser(userId);
		
		Page<OverthinkingPost> page = postRepository.findByAuthorId(userId, OverthinkingPagination.newest(pageable));
		return mapPage(page, userId);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getPostsByArtist(UUID artistId, UUID viewerId, Pageable pageable) {
		Page<OverthinkingPost> page = postRepository.findByArtistId(artistId, OverthinkingPagination.newest(pageable));
		return mapPage(page, viewerId);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable) {
		return getAll(viewerId, pageable, OverthinkingFeedOrder.NEWEST);
	}

	@Override
	public Page<OverthinkingPostResponseDto> getAll(UUID viewerId, Pageable pageable, OverthinkingFeedOrder order) {
		// The explicit feed order is authoritative; arbitrary Pageable.sort must
		// not append a conflicting database order or replace the stable tie-breaker.
		Page<OverthinkingPost> page;
		if (order == OverthinkingFeedOrder.MOST_LIKED) {
			page = postRepository.findMostLiked(OverthinkingPagination.page(pageable, Sort.unsorted()));
		} else {
			Sort.Direction direction = order == OverthinkingFeedOrder.OLDEST ? Sort.Direction.ASC : Sort.Direction.DESC;
			page = postRepository.findAll(OverthinkingPagination.page(pageable, Sort.by(direction, "createdAt", "id")));
		}
		return mapPage(page, viewerId);
	}
	
	@Override
	@Transactional
	public OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto dto) {
		return createWithSnapshot(authorId, dto, null);
	}

	@Override
	public OverthinkingPostResponseDto createWithSnapshot(UUID authorId, OverthinkingPostSaveRequestDto dto,
            SpotifyTrackItemDto snapshot) {
		requireActiveActor(authorId);
		var receipt = creationReceipts.findMatching(authorId, dto);
		if (receipt.isPresent()) {
            var original = postRepository.findById(receipt.get())
                    .orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_CREATE_ALREADY_DELETED));
            return toViewerAwareDto(original, authorId);
        }
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
		
		artistResolver.resolveAndSetArtist(post, dto, snapshot);
		
		OverthinkingPost saved = postRepository.save(post);
		creationReceipts.record(authorId, dto, saved.getId());
		
		log.info("[Overthinking] Yeni post olusturuldu. author={}, post={}", authorId, saved.getId());
		
		return toViewerAwareDto(saved, authorId);
	}
	
	@Override
	@Transactional
	public OverthinkingPostResponseDto update(UUID postId, UUID authorId, OverthinkingPostSaveRequestDto dto) {
		requireActiveActor(authorId);
		User author = userEntityFinder.getUser(authorId);
		OverthinkingPost post = postRepository.findById(postId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		if (!post.getAuthor().getId().equals(author.getId())) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
		// Published content, identity and music are immutable: existing likes and
		// reveal consent must continue to refer to the post the reader encountered.
		throw new SoundConnectException(ErrorType.OVERTHINKING_POST_IMMUTABLE);
	}

	private void requireActiveActor(UUID userId) {
		if (userId == null || likeRepository.lockActiveActor(userId).isEmpty()) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
	}
	
	private Page<OverthinkingPostResponseDto> mapPage(Page<OverthinkingPost> page, UUID viewerId) {
		List<OverthinkingPost> posts = page.getContent();
		
		Map<UUID, Long> likeCounts = getLikeCounts(posts);
		Map<UUID, Long> commentCounts = getCommentCounts(posts);
		Set<UUID> likedPostIds = getLikedPostIds(posts, viewerId);
		Set<UUID> approvedRevealPostIds = getApprovedRevealPostIds(posts, viewerId);
		Set<UUID> pendingRevealPostIds = getPendingRevealPostIds(posts, viewerId);
		Map<UUID, UserSummaryDto> identities = getVisibleIdentities(
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
				identities,
				pendingRevealPostIds
		));
	}
	
	private OverthinkingPostResponseDto toViewerAwareDto(OverthinkingPost post, UUID viewerId) {
		return mapPage(new PageImpl<>(List.of(post)), viewerId).getContent().getFirst();
	}
	
	private OverthinkingPostResponseDto toViewerAwareDto(
			OverthinkingPost post,
			UUID viewerId,
			Map<UUID, Long> likeCounts,
			Map<UUID, Long> commentCounts,
			Set<UUID> likedPostIds,
			Set<UUID> approvedRevealPostIds,
			Map<UUID, UserSummaryDto> identities,
			Set<UUID> pendingRevealPostIds
	) {
		boolean canViewAuthor = canViewAuthor(post, viewerId, approvedRevealPostIds);
		UserSummaryDto identity = canViewAuthor && post.getAuthor() != null
				? identities.get(post.getAuthor().getId())
				: null;
		
		return postMapper.toResolvedDto(
				post,
				canViewAuthor,
				likeCounts.getOrDefault(post.getId(), 0L),
				commentCounts.getOrDefault(post.getId(), 0L),
				likedPostIds.contains(post.getId()),
				identity
		).withRevealRequestPending(pendingRevealPostIds.contains(post.getId()));
	}

	private Map<UUID, UserSummaryDto> getVisibleIdentities(
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
		return authorIdentityResolver.resolve(visibleAuthorIds);
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
	
	private Set<UUID> getPendingRevealPostIds(List<OverthinkingPost> posts, UUID viewerId) {
		List<UUID> postIds = extractPostIds(posts);
		if (viewerId == null || postIds.isEmpty()) return Set.of();
		return revealRequestRepository.findPostIdsByRequesterIdAndStatusAndPostIdIn(
				viewerId, OverthinkingRevealRequestStatus.PENDING, postIds);
	}
}
