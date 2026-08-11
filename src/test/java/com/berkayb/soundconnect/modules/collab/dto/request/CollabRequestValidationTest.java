package com.berkayb.soundconnect.modules.collab.dto.request;

import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CollabRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void expectedVersionRequestRejectsAnAbsentVersion() {
        assertThat(validator.validate(new ExpectedVersionRequest(null)))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactly("expectedVersion");
    }

    @Test
    void updateRequestRejectsAnAbsentVersion() {
        CollabUpdateRequest request = new CollabUpdateRequest(
                null,
                UUID.randomUUID(),
                CollabCadence.REGULAR,
                CollabWantedType.BAND,
                null,
                null,
                null,
                "Akustik grup arıyoruz",
                "Düzenli sahne programımız için akustik bir grup arıyoruz.",
                UUID.randomUUID(),
                List.of("Akustik"),
                null,
                null,
                null
        );

        assertThat(validator.validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactly("expectedVersion");
    }

    @Test
    void discoveryFilterCapsInstrumentIdsBeforeBuildingAnInClause() {
        Set<UUID> instrumentIds = IntStream.range(0, 51)
                .mapToObj(ignored -> UUID.randomUUID())
                .collect(Collectors.toSet());
        CollabFilterRequest request = new CollabFilterRequest(
                CollabCadence.REGULAR,
                null,
                CollabWantedType.MUSICIAN,
                instrumentIds,
                null,
                null,
                null,
                null
        );

        assertThat(validator.validate(request))
                .extracting(value -> value.getPropertyPath().toString())
                .containsExactly("instrumentIds");
    }
}
