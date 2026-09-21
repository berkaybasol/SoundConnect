package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Base64;
import java.util.UUID;

/** A position, not an authorization token; every page independently verifies venue ownership. */
record EventHistoryCursor(LocalDate date, LocalTime startTime, UUID id) {
    String encode(UUID venueId, Instant asOf) {
        String value = "1|" + venueId + "|" + asOf + "|" + date + "|" + startTime + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static EventHistoryCursor decode(String cursor, UUID venueId, Instant asOf) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("1") || !UUID.fromString(fields[1]).equals(venueId)
                    || !Instant.parse(fields[2]).equals(asOf)) throw new IllegalArgumentException();
            LocalDate date = LocalDate.parse(fields[3]);
            if (date.getYear() < 1 || date.getYear() > 9999) throw new IllegalArgumentException();
            return new EventHistoryCursor(date, LocalTime.parse(fields[4]), UUID.fromString(fields[5]));
        } catch (RuntimeException invalid) {
            throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
        }
    }
}
