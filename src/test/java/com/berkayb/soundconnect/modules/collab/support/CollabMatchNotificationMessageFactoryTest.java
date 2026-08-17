package com.berkayb.soundconnect.modules.collab.support;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.enums.CollabBranch;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CollabMatchNotificationMessageFactoryTest {

    @Test
    void usesThePublicPublisherIdentityAndInstrumentSpecialty() {
        Collab listing = listing("  SoundConnect Ankara  ", CollabWantedType.MUSICIAN);
        Instrument instrument = new Instrument();
        instrument.setName("BAS GİTAR");
        listing.setInstrument(instrument);

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("SoundConnect Ankara artık bas gitar aramıyor.");
    }

    @Test
    void usesCustomOtherSpecialtyWithTurkishLowercasing() {
        Collab listing = listing("Stüdyo İstanbul", CollabWantedType.MUSICIAN);
        listing.setBranch(CollabBranch.OTHER);
        listing.setCustomSpecialty("  IŞIK TEKNİSYENİ  ");

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("Stüdyo İstanbul artık ışık teknisyeni aramıyor.");
    }

    @Test
    void retainsAUsableCustomSpecialtyWhenTheLegacyBranchIsMissing() {
        Collab listing = listing("Stüdyo İstanbul", CollabWantedType.MUSICIAN);
        listing.setCustomSpecialty("  Sahne Performansçısı  ");

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("Stüdyo İstanbul artık sahne performansçısı aramıyor.");
    }

    @ParameterizedTest
    @MethodSource("structuredBranches")
    void mapsStructuredBranchesToPublicTurkishLabels(CollabBranch branch, String expectedLabel) {
        Collab listing = listing("Mavi Sahne", CollabWantedType.MUSICIAN);
        listing.setBranch(branch);

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("Mavi Sahne artık " + expectedLabel + " aramıyor.");
    }

    private static Stream<Arguments> structuredBranches() {
        return Stream.of(
                Arguments.of(CollabBranch.VOCAL, "vokal"),
                Arguments.of(CollabBranch.SOUND_ENGINEER, "ses mühendisi"),
                Arguments.of(CollabBranch.PRODUCER, "prodüktör"),
                Arguments.of(CollabBranch.DJ, "DJ")
        );
    }

    @ParameterizedTest
    @MethodSource("wantedTypes")
    void fallsBackToWantedTypeWhenNoUsableSpecialtyExists(CollabWantedType wantedType,
                                                           String expectedLabel) {
        Collab listing = listing("Mavi Sahne", wantedType);
        listing.setBranch(CollabBranch.OTHER);
        listing.setCustomSpecialty("   ");

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("Mavi Sahne artık " + expectedLabel + " aramıyor.");
    }

    private static Stream<Arguments> wantedTypes() {
        return Stream.of(
                Arguments.of(CollabWantedType.MUSICIAN, "müzisyen"),
                Arguments.of(CollabWantedType.BAND, "grup"),
                Arguments.of(CollabWantedType.VENUE, "mekan"),
                Arguments.of(CollabWantedType.STUDIO, "stüdyo")
        );
    }

    @Test
    void skipsBlankInstrumentAndFallsBackToBranch() {
        Collab listing = listing("Mavi Sahne", CollabWantedType.MUSICIAN);
        Instrument instrument = new Instrument();
        instrument.setName("   ");
        listing.setInstrument(instrument);
        listing.setBranch(CollabBranch.VOCAL);

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("Mavi Sahne artık vokal aramıyor.");
    }

    @Test
    void usesNeutralFallbacksForMissingPublicIdentityAndWantedType() {
        Collab listing = new Collab();
        listing.setPublisherActor(CollabActor.builder().displayName("  ").build());

        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("İlan sahibi artık ekip arkadaşı aramıyor.");

        listing.setPublisherActor(null);
        assertThat(CollabMatchNotificationMessageFactory.create(listing))
                .isEqualTo("İlan sahibi artık ekip arkadaşı aramıyor.");
    }

    private Collab listing(String publisherDisplayName, CollabWantedType wantedType) {
        return Collab.builder()
                .publisherActor(CollabActor.builder().displayName(publisherDisplayName).build())
                .wantedType(wantedType)
                .build();
    }
}
