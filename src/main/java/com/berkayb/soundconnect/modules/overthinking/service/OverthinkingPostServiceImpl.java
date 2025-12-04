package com.berkayb.soundconnect.modules.overthinking.service;


import com.berkayb.soundconnect.modules.overthinking.dto.request.OverthinkingPostSaveRequestDto;
import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingPostMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverthinkingPostServiceImpl implements OverthinkingPostService{
	
	private final OverthinkingPostRepository postRepository;
	private final UserEntityFinder userEntityFinder;
	private final OverthinkingArtistResolverService artistResolver;
	private final OverthinkingPostMapper postMapper;
	
	@Override
	public void delete(UUID postId, UUID authorId) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = postRepository.findById(postId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.getAuthor().getId().equals(author.getId())) {
			log.warn("[Overthinking] Yetkisiz silme girisimi post={}, user={}", postId,authorId);
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		postRepository.delete(post);
		
		log.info("[Overthinking] Post silindi. post={}, user={}", postId, authorId);
	}
	
	@Override
	public OverthinkingPostResponseDto getById(UUID postId) {
		OverthinkingPost post = postRepository.findById(postId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		return postMapper.toDto(post);
	}
	
	
	@Override
	public Page<OverthinkingPostResponseDto> getMyPosts(UUID userId, Pageable pageable) {
		userEntityFinder.getUser(userId);
		
		return postRepository.findByAuthorId(userId, pageable)
				.map(postMapper::toDto);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getPostsByArtist(UUID artistId, Pageable pageable) {
		return postRepository.findByArtistId(artistId, pageable)
				.map(postMapper::toDto);
	}
	
	@Override
	public Page<OverthinkingPostResponseDto> getAll(Pageable pageable) {
		return postRepository.findAll(pageable)
				.map(postMapper::toDto);
	}
	
	@Override
	public OverthinkingPostResponseDto create(UUID authorId, OverthinkingPostSaveRequestDto dto) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = OverthinkingPost.builder()
				.author(author)
				.title(dto.title())
				.content(dto.content())
				.spotifyTrackUrl(dto.spotifyTrackUrl())
				.spotifyArtistId(dto.spotifyArtistId())
				.musicianTrackId(dto.musicianTrackId())
				.bandTrackId(dto.bandTrackId())
				.build();
		
		artistResolver.resolveAndSetArtist(post, dto);
		
		OverthinkingPost saved = postRepository.save(post);
		
		log.info("[Overthinking] Yeni post olusturuldu. author={}, post={}", authorId, saved.getId());
		
		return postMapper.toDto(saved);
	}
	
	@Override
	public OverthinkingPostResponseDto update(UUID postId, UUID authorId, OverthinkingPostSaveRequestDto dto) {
		User author = userEntityFinder.getUser(authorId);
		
		OverthinkingPost post = postRepository.findById(postId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.OVERTHINKING_POST_NOT_FOUND));
		
		if (!post.getAuthor().getId().equals(author.getId())) {
			log.warn("[Overthinking] Yetkisiz guncelleme girisimi post={}, user={}", postId,authorId);
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		
		if (dto.title() != null) post.setTitle(dto.title());
		if (dto.content() != null) post.setContent(dto.content());
		
		post.setSpotifyTrackUrl(dto.spotifyTrackUrl());
		post.setSpotifyArtistId(dto.spotifyArtistId());
		post.setMusicianTrackId(dto.musicianTrackId());
		post.setBandTrackId(dto.bandTrackId());
		
		// eski artist bilgisini temizle
		post.setArtistId(null);
		post.setArtistType(null);
		
		// yeniden
		artistResolver.resolveAndSetArtist(post, dto);
		
		OverthinkingPost updated = postRepository.save(post);
		
		log.info("[Overthinking] Post guncellendi. post={}, user={}", postId, authorId);
		
		return postMapper.toDto(updated);
		}
	}