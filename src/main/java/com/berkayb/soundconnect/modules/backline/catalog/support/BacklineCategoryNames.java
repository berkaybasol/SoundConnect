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
        return Normalizer.normalize(displayName, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
    }
}
