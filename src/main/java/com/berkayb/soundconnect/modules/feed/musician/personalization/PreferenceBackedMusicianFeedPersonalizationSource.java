package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PreferenceBackedMusicianFeedPersonalizationSource implements MusicianFeedPersonalizationSource {
    private final MusicianFeedPreferencesService preferences;
    private final VenueRepository venues;
    private final ListenerProfileRepository listeners;
    private final StudioProfileRepository studios;

    public PreferenceBackedMusicianFeedPersonalizationSource(MusicianFeedPreferencesService preferences) {
        this(preferences, null);
    }

    public PreferenceBackedMusicianFeedPersonalizationSource(MusicianFeedPreferencesService preferences,
                                                             VenueRepository venues) {
        this(preferences, venues, null);
    }

    public PreferenceBackedMusicianFeedPersonalizationSource(MusicianFeedPreferencesService preferences,
                                                             VenueRepository venues, ListenerProfileRepository listeners) {
        this(preferences, venues, listeners, null);
    }

    @Autowired
    public PreferenceBackedMusicianFeedPersonalizationSource(MusicianFeedPreferencesService preferences,
                                                             VenueRepository venues, ListenerProfileRepository listeners,
                                                             StudioProfileRepository studios) {
        this.preferences = preferences;
        this.venues = venues;
        this.listeners = listeners;
        this.studios = studios;
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public MusicianFeedPersonalizationSnapshot loadForStudio(UUID userId, UUID studioProfileId) {
        if (studios == null) throw new IllegalStateException("Studio feed personalization requires studio storage");
        var profile = studios.findByUserId(userId)
                .filter(value -> studioProfileId != null && studioProfileId.equals(value.getId())
                        && value.getUser() != null && userId.equals(value.getUser().getId())
                        && value.getUser().getStatus() == UserStatus.ACTIVE
                        && Boolean.TRUE.equals(value.getUser().getEmailVerified())
                        && value.getUser().getErasedAt() == null)
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
        UUID cityId = profile.getCity() == null ? null : profile.getCity().getId();
        return new MusicianFeedPersonalizationSnapshot(cityId, Set.of(), null);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public MusicianFeedPersonalizationSnapshot loadForListener(UUID userId, UUID listenerProfileId) {
        if (listeners == null) throw new IllegalStateException("Listener feed personalization requires listener storage");
        var profile = listeners.findByUserId(userId)
                .filter(value -> listenerProfileId.equals(value.getId()) && value.getUser() != null
                        && userId.equals(value.getUser().getId()) && value.getUser().getStatus() == UserStatus.ACTIVE
                        && Boolean.TRUE.equals(value.getUser().getEmailVerified()) && value.getUser().getErasedAt() == null)
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
        UUID city = profile.getUser().getCity() == null ? null : profile.getUser().getCity().getId();
        return new MusicianFeedPersonalizationSnapshot(city, Set.of(), null);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public MusicianFeedPersonalizationSnapshot loadForVenue(UUID userId, UUID venueId) {
        if (venues == null) throw new IllegalStateException("Venue feed personalization requires venue storage");
        var venue = venues.findPubliclyVisibleById(venueId)
                .filter(value -> value.getOwner() != null && userId.equals(value.getOwner().getId()))
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
        return new MusicianFeedPersonalizationSnapshot(venue.getCity().getId(), Set.of(), null);
    }

    @Override
    public MusicianFeedPersonalizationSnapshot load(UUID userId, UUID musicianProfileId) {
        var response = preferences.get(userId);
        UUID cityId = response.opportunityCity() == null ? null : response.opportunityCity().id();
        Set<UUID> instrumentIds = response.instruments() == null ? Set.of() : response.instruments().stream()
                .map(value -> value.id()).collect(Collectors.toUnmodifiableSet());
        var progress = response.completion() == null ? null : response.completion().overall();
        List<MusicianFeedPayloads.CompletionTask> tasks = response.completion() == null
                || response.completion().incompleteTasks() == null ? List.of()
                : response.completion().incompleteTasks().stream()
                .map(value -> task(value.code(), value.order()))
                .toList();
        MusicianFeedPayloads.Completion completion = progress == null || tasks.isEmpty() ? null
                : new MusicianFeedPayloads.Completion(progress.completed(), progress.total(), tasks);
        return new MusicianFeedPersonalizationSnapshot(cityId, instrumentIds, completion);
    }

    private MusicianFeedPayloads.CompletionTask task(MusicianFeedCompletionTaskCode code, int order) {
        return switch (code) {
            case OPPORTUNITY_CITY -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Fırsat şehrini seç", "Sana yakın Collab fırsatlarını öne çıkaralım.",
                    "Şehir seç", "/backstage/feed/preferences", order, false);
            case INSTRUMENTS -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Enstrümanlarını ekle", "Aradığın rolle eşleşen ilanları daha doğru sıralayalım.",
                    "Enstrüman ekle", "/profile/musician/edit", order, false);
            case BIO, STAGE_NAME_AND_BIO -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Biyografini tamamla", "Müzik çevren seni daha kolay tanısın.",
                    "Profili düzenle", "/profile/musician/edit", order, false);
            case PORTFOLIO -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Portföyünü oluştur", "Herkese açık bir parça veya profil medyası ekle.",
                    "Portföye git", "/profile/musician/portfolio", order, false);
            case PROFILE_PHOTO_AND_SOCIAL_LINKS -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Fotoğraf ve bağlantılarını ekle", "Profilini güvenilir ve ulaşılabilir hale getir.",
                    "Profili düzenle", "/profile/musician/edit", order, false);
        };
    }
}
