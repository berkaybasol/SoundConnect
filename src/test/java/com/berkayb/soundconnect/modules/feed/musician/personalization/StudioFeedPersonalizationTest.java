package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioFeedPersonalizationTest {
    private final MusicianFeedPreferencesService preferences = mock(MusicianFeedPreferencesService.class);
    private final StudioProfileRepository studios = mock(StudioProfileRepository.class);
    private final PreferenceBackedMusicianFeedPersonalizationSource source =
            new PreferenceBackedMusicianFeedPersonalizationSource(preferences, null, null, studios);
    private final UUID viewer = UUID.randomUUID(), profileId = UUID.randomUUID(), cityId = UUID.randomUUID();

    @Test void studioUsesBusinessCityWithoutReadingMusicianPreferencesOrCreatingCompletion() {
        var profile = profile();
        var city = new City();
        city.setId(cityId);
        profile.setCity(city);
        var accountCity = new City();
        accountCity.setId(UUID.randomUUID());
        profile.getUser().setCity(accountCity);
        when(studios.findByUserId(viewer)).thenReturn(Optional.of(profile));
        var value = source.loadForStudio(viewer, profileId);
        assertThat(value.opportunityCityId()).isEqualTo(cityId);
        assertThat(value.instrumentIds()).isEmpty();
        assertThat(value.completion()).isNull();
        verifyNoInteractions(preferences);
    }

    @Test void missingBusinessCityIsAValidColdStartWithoutInventedAccountLocation() {
        var profile = profile();
        var accountCity = new City();
        accountCity.setId(cityId);
        profile.getUser().setCity(accountCity);
        when(studios.findByUserId(viewer)).thenReturn(Optional.of(profile));
        assertThat(source.loadForStudio(viewer, profileId).opportunityCityId()).isNull();
        verifyNoInteractions(preferences);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void pendingRejectedOrInactiveAccountCannotReusePersonalization(UserStatus status) {
        var profile = profile();
        profile.getUser().setStatus(status);
        when(studios.findByUserId(viewer)).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(preferences);
    }

    @Test void changedProfileUnverifiedErasedOrMismatchedOwnerCannotReusePersonalization() {
        var profile = profile();
        when(studios.findByUserId(viewer)).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> source.loadForStudio(viewer, UUID.randomUUID())).isInstanceOf(SoundConnectException.class);
        assertThatThrownBy(() -> source.loadForStudio(viewer, null)).isInstanceOf(SoundConnectException.class);
        profile.getUser().setEmailVerified(false);
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        profile.getUser().setEmailVerified(true);
        profile.getUser().setErasedAt(LocalDateTime.now());
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        profile.getUser().setErasedAt(null);
        profile.getUser().setId(UUID.randomUUID());
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        profile.setUser(null);
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        when(studios.findByUserId(viewer)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> source.loadForStudio(viewer, profileId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(preferences);
    }

    private StudioProfile profile() {
        return StudioProfile.builder().id(profileId)
                .user(User.builder().id(viewer).status(UserStatus.ACTIVE).emailVerified(true).build()).build();
    }
}
