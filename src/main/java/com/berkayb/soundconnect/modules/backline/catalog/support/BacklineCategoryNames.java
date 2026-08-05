package com.berkayb.soundconnect.modules.backline.catalog.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class BacklineCategoryNames {

    public String displayName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Category name cannot be blank");
        }
        String displayName = rawName.strip().replaceAll("\\s+", " ");
        if (displayName.length() > 160) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "Category name cannot exceed 160 characters");
        }
        return displayName;
    }

    public String normalizedName(String displayName) {
        // Locale-sensitive database lower() results differ for the four
        // Turkish I variants. Collapse all of them to ASCII i before the
        // locale-neutral case fold so idempotency and uniqueness keys remain
        // identical on every JVM and PostgreSQL cluster locale.
        return Normalizer.normalize(displayName, Normalizer.Form.NFKC)
                .replace('\u0130', 'I')
                .replace('\u0131', 'i')
                .toLowerCase(Locale.ROOT)
                .strip();
    }
}
