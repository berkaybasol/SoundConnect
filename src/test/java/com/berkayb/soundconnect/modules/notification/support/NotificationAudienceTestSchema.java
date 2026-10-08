package com.berkayb.soundconnect.modules.notification.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal role relations for notification-only, disposable PostgreSQL test contexts. */
@TestConfiguration(proxyBeanMethods = false)
public class NotificationAudienceTestSchema {
    @Bean @DependsOn("entityManagerFactory")
    InitializingBean notificationAudienceSchema(JdbcTemplate jdbc) {
        return () -> {
            jdbc.execute("create table if not exists tbl_role(id uuid primary key, name varchar(255))");
            jdbc.execute("create table if not exists user_roles(user_id uuid not null, role_id uuid not null, primary key(user_id,role_id))");
        };
    }
}
