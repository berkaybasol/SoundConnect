package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EventPlanSchedulerTest {
    @Test void failedFirstPlanDoesNotStarveLaterPlansAndCursorResetsAfterTheEnd() {
        EventPlanRepository plans=mock(EventPlanRepository.class);
        EventPlanService service=mock(EventPlanService.class);
        EventScheduleClock clock=mock(EventScheduleClock.class);
        when(clock.localNow()).thenReturn(LocalDateTime.of(2026,9,21,12,0));
        UUID bad=new UUID(0,1),good=new UUID(0,2);
        LocalDate through=LocalDate.of(2026,10,18);
        when(plans.findGenerationCandidatesAfter(eq(through),isNull(),any(Pageable.class))).thenReturn(List.of(bad,good));
        when(plans.findGenerationCandidatesAfter(eq(through),eq(good),any(Pageable.class))).thenReturn(List.of());
        doThrow(new IllegalStateException("temporary unavailable dependency")).when(service).generate(bad);
        EventPlanScheduler scheduler=new EventPlanScheduler(plans,service,clock);
        scheduler.generateDue();scheduler.generateDue();scheduler.generateDue();
        verify(service,times(2)).generate(bad);verify(service,times(2)).generate(good);
        verify(plans).findGenerationCandidatesAfter(eq(through),eq(good),any(Pageable.class));
    }
}
