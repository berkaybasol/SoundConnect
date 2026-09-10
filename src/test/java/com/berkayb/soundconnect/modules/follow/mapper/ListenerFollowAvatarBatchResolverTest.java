package com.berkayb.soundconnect.modules.follow.mapper;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerFollowAvatarBatchResolverTest {
    @Test void currentListenerAvatarReplacesTheLegacyValueAndAnExplicitRemovalStaysNull() {
        var profiles=mock(ListenerProfileRepository.class); var media=mock(MediaAssetService.class);
        UUID first=UUID.randomUUID(), second=UUID.randomUUID(), image=UUID.randomUUID();
        when(profiles.findAvatarReferences(anyCollection())).thenReturn(List.of(reference(first,image),reference(second,null)));
        when(media.getDisplayUrlMap(List.of(image))).thenReturn(Map.of(image,"https://media.test/current.webp"));
        var avatars=new ListenerFollowAvatarBatchResolver(profiles,media).resolve(List.of(first,second,first));
        var follow=Follow.builder().follower(User.builder().id(first).username("first").profilePicture("old.png").build())
                .following(User.builder().id(second).username("second").profilePicture("removed.png").build()).build();
        var mapper=Mappers.getMapper(FollowMapper.class);
        var dto=mapper.toDto(follow,Map.of(),avatars);
        assertThat(dto.followerProfilePicture()).isEqualTo("https://media.test/current.webp");
        assertThat(dto.followingProfilePicture()).isNull();
        var masked=mapper.toDto(follow,Map.of(first,new GhostListenerIdentity(first,"Kullanici",null,ListenerVisibilityMode.GHOST)),avatars);
        assertThat(masked.followerProfilePicture()).isNull();
        assertThat(masked.followerUsername()).isEqualTo("Kullanici");
        verify(profiles,times(1)).findAvatarReferences(anyCollection()); verify(media,times(1)).getDisplayUrlMap(anyList());
    }

    @Test void largeListsUseBoundedBatchesAndNeverLoadAnAvatarPerFollower() {
        var profiles=mock(ListenerProfileRepository.class); var media=mock(MediaAssetService.class);
        var ids=java.util.stream.IntStream.range(0,121).mapToObj(value -> UUID.randomUUID()).toList();
        when(profiles.findAvatarReferences(anyCollection())).thenAnswer(call -> {
            Collection<UUID> batch=call.getArgument(0); assertThat(batch).hasSizeLessThanOrEqualTo(50);
            return batch.stream().map(id -> reference(id,null)).toList();
        });
        assertThat(new ListenerFollowAvatarBatchResolver(profiles,media).resolve(ids)).hasSize(121);
        verify(profiles,times(3)).findAvatarReferences(anyCollection()); verifyNoInteractions(media);
    }

    private ListenerProfileRepository.AvatarReference reference(UUID user,UUID media) {
        return new ListenerProfileRepository.AvatarReference() {
            public UUID getUserId() { return user; } public UUID getMediaId() { return media; }
        };
    }
}
