package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;

import java.util.Locale;
import java.util.Objects;

/** Builds public, human-readable copy for applications invalidated by a Collab match. */
public final class CollabMatchNotificationMessageFactory {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final String PUBLISHER_FALLBACK = "İlan sahibi";
    private static final String SPECIALTY_FALLBACK = "ekip arkadaşı";

    private CollabMatchNotificationMessageFactory() {
    }

    public static String create(Collab listing) {
        Objects.requireNonNull(listing, "listing is required");
        return publisherDisplayName(listing) + " artık " + specialtyLabel(listing) + " aramıyor.";
    }

    private static String publisherDisplayName(Collab listing) {
        if (listing.getPublisherActor() == null) return PUBLISHER_FALLBACK;
        String displayName = strippedOrNull(listing.getPublisherActor().getDisplayName());
        return displayName == null ? PUBLISHER_FALLBACK : displayName;
    }

    private static String specialtyLabel(Collab listing) {
        if (listing.getInstrument() != null) {
            String instrumentName = lowerOrNull(listing.getInstrument().getName());
            if (instrumentName != null) return instrumentName;
        }

        CollabBranch branch = listing.getBranch();
        if (branch != null) {
            if (branch == CollabBranch.OTHER) {
                String customSpecialty = lowerOrNull(listing.getCustomSpecialty());
                if (customSpecialty != null) return customSpecialty;
            } else {
                return switch (branch) {
                    case VOCAL -> "vokal";
                    case SOUND_ENGINEER -> "ses mühendisi";
                    case PRODUCER -> "prodüktör";
                    case DJ -> "DJ";
                    case OTHER -> throw new IllegalStateException("OTHER is handled above");
                };
            }
        }

        // Be defensive for legacy/corrupt snapshots where the custom label
        // survived but the OTHER discriminator did not.
        String customSpecialty = lowerOrNull(listing.getCustomSpecialty());
        if (customSpecialty != null) return customSpecialty;

        return wantedTypeLabel(listing.getWantedType());
    }

    private static String wantedTypeLabel(CollabWantedType wantedType) {
        if (wantedType == null) return SPECIALTY_FALLBACK;
        return switch (wantedType) {
            case MUSICIAN -> "müzisyen";
            case BAND -> "grup";
            case VENUE -> "mekan";
            case STUDIO -> "stüdyo";
        };
    }

    private static String lowerOrNull(String value) {
        String stripped = strippedOrNull(value);
        return stripped == null ? null : stripped.toLowerCase(TURKISH);
    }

    private static String strippedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
