package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class VenueSuggestionService {
    private final VenueSuggestionStore store;
    private final List<String> recipients;

    public VenueSuggestionService(VenueSuggestionStore store, Validator validator,
            @Value("${soundconnect.admin-notifications.venue-application-emails:backstage@soundconnect.com.tr,berkay@soundconnect.com.tr}") String configuredRecipients) {
        this.store = store;
        recipients = Arrays.stream(configuredRecipients.split(",", -1)).map(String::strip)
                .map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        if (recipients.isEmpty() || recipients.size() > 10 || recipients.stream().anyMatch(value ->
                value.length() > 254 || !validator.validate(new Recipient(value)).isEmpty())) {
            throw new IllegalStateException("Venue suggestion admin recipients must contain 1-10 valid addresses");
        }
    }

    public void accept(VenueSuggestionRequest request) {
        if (request == null || request.requestId() == null || request.cityId() == null
                || request.districtId() == null || request.liveMusic() == null) throw VenueSuggestionNormalizer.invalid();
        String name = VenueSuggestionNormalizer.name(request.venueName());
        String dedupeKey = VenueSuggestionNormalizer.hash(VenueSuggestionNormalizer.folded(name)
                + "\n" + request.cityId() + "\n" + request.districtId());
        String payloadHash = VenueSuggestionNormalizer.hash(dedupeKey + "\n" + request.liveMusic().name());
        try {
            store.accept(request, name, payloadHash, dedupeKey, recipients);
        } catch (DataAccessException | TransactionException failure) {
            // A transaction/commit failure is never represented as accepted. Same requestId can safely retry.
            throw new ServiceUnavailableRetryException(ErrorType.VENUE_SUGGESTION_UNAVAILABLE, 30);
        }
    }
    private record Recipient(@NotBlank @Email String email) { }
}
