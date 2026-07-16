package com.berkayb.soundconnect.modules.media.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Fail-closed switch for every component capable of reaching native image work. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(
		prefix = "media.image-variants.worker",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = false
)
public @interface ConditionalOnImageVariantWorker {
}
