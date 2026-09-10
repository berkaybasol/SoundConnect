package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.comment.service.CommentService;
import com.berkayb.soundconnect.modules.comment.support.CommentAuthorBatchResolver;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.like.service.LikeService;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.overthinking.enums.*;
import com.berkayb.soundconnect.modules.overthinking.mapper.OverthinkingPostMapper;
import com.berkayb.soundconnect.modules.overthinking.repository.*;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OverthinkingReadProjectionTest {
    private final OverthinkingPostRepository repository = mock(OverthinkingPostRepository.class);
    private final CommentAuthorBatchResolver identities = mock(CommentAuthorBatchResolver.class);
    private final PublicProfileResolverService navigationResolver = mock(PublicProfileResolverService.class);
    private final OverthinkingPostMapper mapper = Mappers.getMapper(OverthinkingPostMapper.class);
    private final OverthinkingPostServiceImpl posts = new OverthinkingPostServiceImpl(repository,
            mock(OverthinkingRevealRequestRepository.class), mock(UserEntityFinder.class),
            mock(OverthinkingArtistResolverService.class), mapper, mock(LikeService.class), mock(CommentService.class),
            identities, mock(CommentRepository.class), mock(LikeRepository.class), mock(OverthinkingPostCreationReceipts.class),
            mock(OverthinkingRevealNotificationRetractionService.class));

    @Test void sameAuthorIsResolvedOnceAndStoredMusicNeedsNoProfileNavigationEnrichment() {
        User author = User.builder().id(UUID.randomUUID()).username("raw-name-must-not-win").build();
        var page = IntStream.range(0,20).mapToObj(i -> post(author,OverthinkingVisibilityType.VISIBLE)).toList();
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(page));
        when(identities.resolve(Set.of(author.getId()))).thenReturn(Map.of(author.getId(),
                new UserSummaryDto(author.getId(),"ghost-handle","https://safe.test/avatar", ListenerVisibilityMode.GHOST)));
        ReflectionTestUtils.setField(mapper,"profileResolverService",navigationResolver);
        var result = posts.getAll(null,PageRequest.of(0,20));
        assertThat(result.getContent()).hasSize(20).allSatisfy(p -> {
            assertThat(p.authorUsername()).isEqualTo("ghost-handle");
            assertThat(p.authorVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
            assertThat(p.spotifyTrackName()).isEqualTo("Stored snapshot");
        });
        verify(identities).resolve(Set.of(author.getId()));
        verifyNoInteractions(navigationResolver);
    }

    @Test void anonymousAuthorsAreNotSentToIdentityResolutionAndLegacyTrackersAreRemoved() {
        var post = post(User.builder().id(UUID.randomUUID()).build(),OverthinkingVisibilityType.ANONYMOUS);
        post.setSpotifyAlbumImageUrl("https://tracker.example/pixel");
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(post)));
        var result = posts.getAll(null,PageRequest.of(0,20)).getContent().getFirst();
        assertThat(result.authorId()).isNull(); assertThat(result.canViewAuthor()).isFalse();
        assertThat(result.spotifyAlbumImageUrl()).isNull();
        verifyNoInteractions(identities,navigationResolver);
    }

    @Test void feedArtistAndOwnPagesApplySameBoundsAndIgnorePrivateSortKeys() {
        UUID user = UUID.randomUUID(), artist = UUID.randomUUID();
        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(repository.findByArtistId(eq(artist),any(Pageable.class))).thenReturn(Page.empty());
        when(repository.findByAuthorId(eq(user),any(Pageable.class))).thenReturn(Page.empty());
        Pageable attack = PageRequest.of(0,2000,Sort.by("author.email","author.id"));
        posts.getAll(null,attack); posts.getPostsByArtist(artist,null,attack); posts.getMyPosts(user,attack);
        Pageable safe = PageRequest.of(0,50,Sort.by(Sort.Direction.DESC,"createdAt","id"));
        verify(repository).findAll(safe); verify(repository).findByArtistId(artist,safe); verify(repository).findByAuthorId(user,safe);
        assertThatThrownBy(() -> posts.getAll(null,PageRequest.of(1001,20))).isInstanceOf(com.berkayb.soundconnect.shared.exception.SoundConnectException.class);
    }

    private OverthinkingPost post(User author, OverthinkingVisibilityType visibility) {
        return OverthinkingPost.builder().id(UUID.randomUUID()).author(author).title("Title").content("Body")
                .visibilityType(visibility).spotifyTrackUrl("https://open.spotify.com/track/0000000000000000000000")
                .spotifyTrackName("Stored snapshot").spotifyAlbumImageUrl("https://i.scdn.co/image/art").build();
    }
}
