package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerFeedPersonalizationTest {
    private final MusicianFeedPreferencesService preferences = mock(MusicianFeedPreferencesService.class);
    private final ListenerProfileRepository listeners = mock(ListenerProfileRepository.class);
    private final PreferenceBackedMusicianFeedPersonalizationSource source =
            new PreferenceBackedMusicianFeedPersonalizationSource(preferences, null, listeners);
    private final UUID viewer = UUID.randomUUID(), profileId = UUID.randomUUID(), cityId = UUID.randomUUID();

    @Test void listenerUsesItsAccountCityWithoutCreatingMusicianPreferencesOrCompletion() {
        var profile = profile();
        var city = new City();
        city.setId(cityId);
        profile.getUser().setCity(city);
        when(listeners.findByUserId(viewer)).thenReturn(Optional.of(profile));
        var value = source.loadForListener(viewer, profileId);
        assertThat(value.opportunityCityId()).isEqualTo(cityId);
        assertThat(value.instrumentIds()).isEmpty();
        assertThat(value.completion()).isNull();
        verifyNoInteractions(preferences);
    }

    @Test void missingCityIsAValidColdStartWithoutInventedLocalPreference() {
        when(listeners.findByUserId(viewer)).thenReturn(Optional.of(profile()));
        assertThat(source.loadForListener(viewer, profileId).opportunityCityId()).isNull();
        verifyNoInteractions(preferences);
    }

    @Test void changedProfileAndUnverifiedAccountCannotReusePersonalization() {
        var profile = profile();
        when(listeners.findByUserId(viewer)).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> source.loadForListener(viewer, UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        profile.getUser().setEmailVerified(false);
        assertThatThrownBy(() -> source.loadForListener(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(preferences);
    }

    private ListenerProfile profile() {
        return ListenerProfile.builder().id(profileId)
                .user(User.builder().id(viewer).status(UserStatus.ACTIVE).emailVerified(true).build()).build();
    }
}
