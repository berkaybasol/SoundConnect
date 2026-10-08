package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RabbitQueueHealthSourcesTest {
    @Test @SuppressWarnings("unchecked")
    void passiveObservationDistinguishesMissingConsumerBacklogAndDlqWithoutReturningQueueName() {
        var admin = mock(AmqpAdmin.class);
        ObjectProvider<AmqpAdmin> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(admin);
        var environment = new MockEnvironment().withProperty("mail.queueName", "private-mail-queue");
        var sources = new RabbitQueueHealthSources(provider, environment, new SystemHealthProperties());
        var mail = sources.probes().stream().filter(p -> p.id().equals("mailQueue")).findFirst().orElseThrow();
        when(admin.getQueueInfo("private-mail-queue")).thenReturn(new QueueInformation("private-mail-queue", 4, 0));
        var down = mail.read().get();
        assertThat(down.status()).isEqualTo(Status.DOWN);
        assertThat(down.metrics()).containsExactlyInAnyOrderEntriesOf(Map.of("readyMessages", 4d, "consumers", 0d));
        assertThat(down.toString()).doesNotContain("private-mail-queue");
        when(admin.getQueueInfo("private-mail-queue")).thenReturn(new QueueInformation("private-mail-queue", 0, 1));
        assertThat(mail.read().get().status()).isEqualTo(Status.UP);
        when(admin.getQueueInfo("private-mail-queue")).thenReturn(null);
        assertThat(mail.read().get().status()).isEqualTo(Status.UNKNOWN);
        var dlq = sources.probes().stream().filter(p -> p.id().equals("mailDlq")).findFirst().orElseThrow();
        when(admin.getQueueInfo("mail.queue.dlq")).thenReturn(new QueueInformation("mail.queue.dlq", 1, 0));
        assertThat(dlq.read().get().status()).isEqualTo(Status.DEGRADED);
        verify(admin, times(3)).getQueueInfo("private-mail-queue");
        verify(admin).getQueueInfo("mail.queue.dlq");
        verifyNoMoreInteractions(admin);
    }
}
