package com.berkayb.soundconnect.shared.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

@IsolatedRecoveryTest
class RecoveryRuntimeDeltaTest {
    @BeforeAll static void hold() { RecoveryRuntime.quarantine(); }
    @Test void automaticExportersAndMailDlqMonitorDisabledBeforeBeanConditions() {
        try(var c=new GenericApplicationContext()) {
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("input",Map.of(
                "app.recovery.mode","HOLD","spring.jpa.hibernate.ddl-auto","validate",
                "mail.dlq-monitor.enabled","true","app.telemetry.metrics.enabled","true")));
            new RecoveryRuntimeInitializer().initialize(c);
            for(String key:new String[]{"mail.dlq-monitor.enabled","app.telemetry.metrics.enabled",
                    "management.otlp.metrics.export.enabled","management.tracing.enabled"})
                assertThat(c.getEnvironment().getProperty(key)).as(key).isEqualTo("false");
        }
    }
    @Test void undeletableReadinessMarkerFailsHoldStartup(@TempDir Path root) throws Exception {
        Path marker=Files.createDirectory(root.resolve("marker")); Files.writeString(marker.resolve("child"),"owned");
        var dependency=mock(com.berkayb.soundconnectworker.MediaWorkerDependencyHealthIndicator.class);
        var health=new com.berkayb.soundconnectworker.MediaWorkerHealthFile(dependency,marker.toString());
        assertThatThrownBy(health::onReady).hasMessage("RECOVERY_HOLD_READINESS_MARKER");
        verifyNoInteractions(dependency);
    }
    @Test void directHlsWorkCannotClaimLeaseOrCreateTemporaryWork() throws Exception {
        var workflow=mock(com.berkayb.soundconnect.modules.media.transcode.VideoHlsWorkflow.class,CALLS_REAL_METHODS);
        assertThatThrownBy(()->workflow.claim(null)).isInstanceOf(RecoveryRuntime.Held.class);
        assertThatThrownBy(()->workflow.processClaimed(null)).isInstanceOf(RecoveryRuntime.Held.class);
        assertThatThrownBy(()->workflow.process(null)).isInstanceOf(RecoveryRuntime.Held.class);
        var admission=mock(com.berkayb.soundconnect.modules.media.transcode.MediaHlsWorkExecutor.class,CALLS_REAL_METHODS);
        assertThatThrownBy(admission::tryReserve).isInstanceOf(RecoveryRuntime.Held.class);
        assertThatThrownBy(()->admission.submitReserved(null)).isInstanceOf(RecoveryRuntime.Held.class);
    }
}
