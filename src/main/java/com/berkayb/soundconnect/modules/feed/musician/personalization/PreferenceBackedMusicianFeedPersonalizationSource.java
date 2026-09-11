package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPayloads;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PreferenceBackedMusicianFeedPersonalizationSource implements MusicianFeedPersonalizationSource {
    private final MusicianFeedPreferencesService preferences;

    public PreferenceBackedMusicianFeedPersonalizationSource(MusicianFeedPreferencesService preferences) {
        this.preferences = preferences;
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
            case STAGE_NAME_AND_BIO -> new MusicianFeedPayloads.CompletionTask(code.name(),
                    "Sahne adını ve biyografini tamamla", "Müzik çevren seni daha kolay tanısın.",
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
