package com.berkayb.soundconnect.modules.backline.catalog.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BacklineCategoryNamesTest {

    private final BacklineCategoryNames names = new BacklineCategoryNames();

    @Test
    void canonicalKeyIsIndependentFromTurkishIRepresentation() {
        assertThat(names.normalizedName("IŞIK"))
                .isEqualTo(names.normalizedName("İŞİK"))
                .isEqualTo(names.normalizedName("ışık"))
                .isEqualTo(names.normalizedName("işik"))
                .isEqualTo("işik");
    }
}
