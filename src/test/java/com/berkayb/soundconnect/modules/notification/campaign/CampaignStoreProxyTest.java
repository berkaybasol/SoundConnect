package com.berkayb.soundconnect.modules.notification.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.*;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression for the real ApplicationRunner failure hidden by directly constructed JDBC fixtures. */
class CampaignStoreProxyTest {
    @Configuration static class Config {
        @Bean static PersistenceExceptionTranslationPostProcessor translation(){var p=new PersistenceExceptionTranslationPostProcessor();p.setProxyTargetClass(true);return p;}
        @Bean NamedParameterJdbcTemplate sql(){return mock(NamedParameterJdbcTemplate.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean CampaignStore store(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper){return new CampaignStore(jdbc,mapper);}
    }
    @Test void realRepositoryCglibProxySupportsStartupAndScheduledQueries() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var store=context.getBean(CampaignStore.class);var jdbc=context.getBean(NamedParameterJdbcTemplate.class);
            assertThat(AopUtils.isCglibProxy(store)).isTrue();
            assertThat(store.jdbc()).isSameAs(jdbc);assertThat(store.rowMapper()).isNotNull();
            when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(true);
            when(jdbc.queryForList(anyString(),any(SqlParameterSource.class),eq(UUID.class))).thenReturn(List.of());
            var worker=mock(CampaignWorker.class);var scheduler=new CampaignScheduler(store,worker);
            assertThatCode(()->scheduler.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
            assertThatCode(scheduler::tick).doesNotThrowAnyException();verifyNoInteractions(worker);
        }
    }
}
