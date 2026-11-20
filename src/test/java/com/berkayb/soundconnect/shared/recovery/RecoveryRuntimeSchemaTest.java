package com.berkayb.soundconnect.shared.recovery;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Real Hibernate/H2 startup: hostile native DDL cannot mutate or bypass validate. */
@IsolatedRecoveryTest
class RecoveryRuntimeSchemaTest {
    @Entity @Table(name="recovery_sentinel")
    public static class Sentinel { @Id public Long id; public String preserved; }
    @Configuration @EnableScheduling
    static class ScheduledConfiguration {
        @Scheduled(fixedDelay=1) public void forbidden() { throw new AssertionError("scheduler ran"); }
    }
    private AnnotationConfigApplicationContext context(String url) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("input", Map.of(
                "app.recovery.mode", "HOLD", "spring.jpa.hibernate.ddl-auto", "validate",
                "management.server.port", "9090", "spring.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
        new RecoveryRuntimeInitializer().initialize(context);
        context.register(ScheduledConfiguration.class);
        context.registerBean("dataSource", HikariDataSource.class, () -> {
            var source = new HikariDataSource(); source.setJdbcUrl(url); source.setUsername("sa"); return source;
        });
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(context.getBean(HikariDataSource.class));
            emf.setPackagesToScan(getClass().getPackageName());
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "jakarta.persistence.schema-generation.database.action", "drop-and-create",
                    "javax.persistence.schema-generation.database.action", "drop-and-create",
                    "hibernate.hbm2ddl.import_files", "missing-dangerous.sql"));
            return emf;
        });
        return context;
    }
    private String database(boolean complete) throws Exception {
        String url = "jdbc:h2:mem:held_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.execute("CREATE TABLE recovery_sentinel(id BIGINT PRIMARY KEY"+(complete?", preserved VARCHAR(255)":"")+")");
            s.execute("INSERT INTO recovery_sentinel(id) VALUES(17)");
        }
        return url;
    }
    private void intact(String url) throws Exception {
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement(); var r=s.executeQuery("SELECT id FROM recovery_sentinel")) {
            assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(17); assertThat(r.next()).isFalse();
        }
    }
    @Test void nativeDdlCannotRecreateSchemaAndSchedulerIsRemoved() throws Exception {
        String url=database(true);
        try(var context=context(url)) {
            context.refresh();
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(context.getBean(HikariDataSource.class).isReadOnly()).isTrue();
            assertThat(context.getEnvironment().getProperty("management.server.port")).isEqualTo("-1");
            var emf=context.getBean("&entityManagerFactory",LocalContainerEntityManagerFactoryBean.class);
            assertThat(emf.getJpaPropertyMap()).containsEntry("hibernate.hbm2ddl.auto","validate")
                .doesNotContainKeys("jakarta.persistence.schema-generation.database.action", "hibernate.hbm2ddl.import_files");
            intact(url);
        }
        intact(url); // create-drop must not run on context shutdown either.
    }
    @Test void missingColumnStillFailsValidationWithoutSchemaMutation() throws Exception {
        String url=database(false);
        try(var context=context(url)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining("missing column [preserved]");
        }
        intact(url);
    }
}
