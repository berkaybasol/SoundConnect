package com.berkayb.soundconnect.shared.recovery;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.test.util.ReflectionTestUtils;

/** Restores the test JVM after a class exercises the process-lifetime quarantine. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Isolated("Recovery quarantine affects every application context in this JVM")
@ExtendWith(IsolatedRecoveryTest.RestoreQuarantine.class)
@interface IsolatedRecoveryTest {
    final class RestoreQuarantine implements BeforeAllCallback, AfterAllCallback {
        private boolean heldBeforeClass;

        @Override
        public void beforeAll(ExtensionContext context) {
            heldBeforeClass = RecoveryRuntime.isHeld();
        }

        @Override
        public void afterAll(ExtensionContext context) {
            ReflectionTestUtils.setField(RecoveryRuntime.class, "held", heldBeforeClass);
        }
    }
}
