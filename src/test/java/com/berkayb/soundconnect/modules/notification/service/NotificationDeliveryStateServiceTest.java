package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationDeliveryStateServiceTest {
    NotificationRepository repository = mock(NotificationRepository.class);
    NotificationServiceImpl service = new NotificationServiceImpl(repository, null, null, null, null, null);
    UUID owner = UUID.randomUUID();

    @Test void returnsOnlyRequestedNonRetainedIdsInFirstOccurrenceOrderWithoutAnyMutation() {
        UUID unread=UUID.randomUUID(), read=UUID.randomUUID(), missing=UUID.randomUUID();
        var requested=List.of(read, unread, missing, read);
        when(repository.findVisibleUnreadIds(owner,new LinkedHashSet<>(requested))).thenReturn(List.of(unread));
        assertThat(service.getDismissedDeliveryIds(owner, requested)).containsExactly(read,missing);
        verify(repository).findVisibleUnreadIds(owner,new LinkedHashSet<>(requested));
        verifyNoMoreInteractions(repository);
    }

    @Test void emptyRequestDoesNotQueryOrMutateAndMethodIsReadOnly() throws Exception {
        assertThat(service.getDismissedDeliveryIds(owner,List.of())).isEmpty();
        assertThat(NotificationServiceImpl.class.getMethod("getDismissedDeliveryIds",UUID.class,List.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        verifyNoInteractions(repository);
    }

    @Test void validatesBeforeDeduplicationAndBeforeDatabaseAccess() {
        for (List<UUID> ids : Arrays.asList(null, Arrays.asList((UUID)null), Collections.nCopies(101,owner))) {
            assertThatThrownBy(() -> service.getDismissedDeliveryIds(owner,ids)).isInstanceOf(SoundConnectException.class);
        }
        assertThatThrownBy(() -> service.getDismissedDeliveryIds(null,List.of())).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(repository);
    }

    @Test void maximumSizedRequestUsesOneBoundedLookupAndNoResponseContainsExtraIds() {
        var ids=java.util.stream.IntStream.range(0,100).mapToObj(i->UUID.randomUUID()).toList();
        when(repository.findVisibleUnreadIds(owner,new LinkedHashSet<>(ids))).thenReturn(List.of(ids.getFirst(),UUID.randomUUID()));
        assertThat(service.getDismissedDeliveryIds(owner,ids)).containsExactlyElementsOf(ids.subList(1,100));
        verify(repository).findVisibleUnreadIds(owner,new LinkedHashSet<>(ids));
        verifyNoMoreInteractions(repository);
    }

    @Test void infrastructureFailureIsNotMisreportedAsPermissionToDismissEverything() {
        UUID id=UUID.randomUUID();
        when(repository.findVisibleUnreadIds(owner,Set.of(id))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertThatThrownBy(()->service.getDismissedDeliveryIds(owner,List.of(id)))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
}
