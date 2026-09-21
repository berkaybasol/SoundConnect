package com.berkayb.soundconnect.modules.event.management;

import com.berkayb.soundconnect.modules.event.entity.Event;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.springframework.stereotype.Component;
import java.sql.Time;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalTime;
import java.util.TimeZone;

/** Mirrors the actual Hibernate LocalTime storage without changing any existing event data. */
@Component
public class EventManagementTimeStorage {
    private final long logicalTimeOffsetSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public EventManagementTimeStorage(EntityManagerFactory factory) {
        var sessionFactory = factory.unwrap(SessionFactoryImplementor.class);
        var attribute = (BasicValuedModelPart) sessionFactory.getRuntimeMetamodels().getMappingMetamodel()
                .getEntityDescriptor(Event.class).findAttributeMapping("startTime");
        TimeZone jdbcZone = sessionFactory.getSessionFactoryOptions().getJdbcTimeZone();
        // Direct java.time JDBC types have no java.sql.Time/Calendar conversion.
        logicalTimeOffsetSeconds = attribute.getJdbcMapping().getJdbcType().getJdbcTypeCode() == Types.TIME
                ? offsetSeconds(jdbcZone) : 0;
    }

    EventManagementTimeStorage(long offsetSeconds) { logicalTimeOffsetSeconds = offsetSeconds; }

    String sqlInterval() { return logicalTimeOffsetSeconds + " seconds"; }

    static long offsetSeconds(TimeZone jdbcZone) {
        if (jdbcZone == null) return 0;
        // Hibernate unwraps LocalTime as java.sql.Time (1970-01-01 in the JVM zone),
        // then binds it with the configured JDBC Calendar. Today's zone offset is wrong here.
        LocalTime logical = LocalTime.NOON;
        LocalTime stored = Instant.ofEpochMilli(Time.valueOf(logical).getTime()).atZone(jdbcZone.toZoneId()).toLocalTime();
        return logical.toSecondOfDay() - stored.toSecondOfDay();
    }
}
