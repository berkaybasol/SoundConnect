package com.berkayb.soundconnect.shared.recovery;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/** Loaded by Boot for BOTH API and the separately scanned media worker.
 * Reuses the existing RestoreIsolationInitializer scheduler/listener lifecycle.
 * The selected profile remains HOLD throughout this process; OPEN is unsupported.
 */
public final class RecoveryRuntimeInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException("RECOVERY_HOLD_" + reason);
    }
    @Override public void initialize(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        String mode = env.getProperty("app.recovery.mode");
        boolean profile = Arrays.asList(env.getActiveProfiles()).contains("recovery");
        if (mode == null && !profile && !RecoveryRuntime.isHeld()) return;
        RecoveryRuntime.quarantine();
        require("HOLD".equals(mode), "MODE_REQUIRED_OPEN_UNSUPPORTED");
        require("validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")), "DDL_VALIDATE_REQUIRED");
        require(env.getProperty("app.recovery.authority-enabled", "false").equals("false"), "AUTHORITY_UNPROVEN");
        Map<String,Object> fixed = new LinkedHashMap<>();
        for (String key : new String[]{"app.notification.push.enabled", "app.notification.campaigns.enabled",
                "app.data.init.enabled", "app.data.init.owner.enabled", "app.location.seed.enabled",
                "app.instrument.catalog.seed.enabled", "app.backline.catalog.seed.enabled",
                "spring.rabbitmq.listener.simple.auto-startup", "spring.rabbitmq.listener.direct.auto-startup",
                "app.websocket.broker-relay.enabled", "spring.rabbitmq.dynamic", "mail.dlq-monitor.enabled",
                "app.telemetry.metrics.enabled", "management.otlp.metrics.export.enabled",
                "management.tracing.enabled", "app.observability.http-telemetry.enabled",
                "app.observability.dependency-telemetry.enabled"}) fixed.put(key, "false");
        fixed.put("spring.sql.init.mode", "never");
        fixed.put("management.server.port", "-1");
        fixed.put("spring.jmx.enabled", "false");
        fixed.put("spring.jpa.generate-ddl", "false");
        fixed.put("spring.jpa.properties.hibernate.hbm2ddl.auto", "validate");
        for (String prefix : new String[]{"jakarta.persistence", "javax.persistence"}) {
            fixed.put("spring.jpa.properties." + prefix + ".schema-generation.database.action", "none");
            fixed.put("spring.jpa.properties." + prefix + ".schema-generation.scripts.action", "none");
        }
        env.getPropertySources().addFirst(new MapPropertySource("recovery-quarantine", fixed));
        context.addBeanFactoryPostProcessor(factory -> {
            require(factory instanceof BeanDefinitionRegistry, "REGISTRY");
            var registry = (BeanDefinitionRegistry) factory;
            String scheduled = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            require(!factory.containsSingleton(scheduled), "SCHEDULER_ORDER");
            if (registry.containsBeanDefinition(scheduled)) registry.removeBeanDefinition(scheduled);
            require(factory.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class, false, false).length == 0,
                    "EXTRA_SCHEDULER");
            factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof com.zaxxer.hikari.HikariDataSource source) {
                        String sql = source.getConnectionInitSql();
                        require(sql == null || sql.isBlank() || "SET TIME ZONE 'UTC'".equals(sql), "CONNECTION_INIT_SQL");
                        source.setReadOnly(true);
                        if (source.getJdbcUrl() != null && source.getJdbcUrl().startsWith("jdbc:postgresql:")) {
                            source.addDataSourceProperty("readOnlyMode", "always");
                        }
                    }
                    if (bean instanceof org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean emf) {
                        // Native JPA properties outrank Boot's ddl-auto property.
                        // Enforce after builder/customizers, before provider startup.
                        var properties = emf.getJpaPropertyMap();
                        // JPA action=none would also suppress Hibernate validation.
                        // Remove every alternate/contributor action before selecting validate.
                        properties.keySet().removeIf(key -> key.startsWith("hibernate.hbm2ddl.")
                                || key.startsWith("jakarta.persistence.schema-generation.")
                                || key.startsWith("javax.persistence.schema-generation."));
                        properties.put("hibernate.hbm2ddl.auto", "validate");
                    }
                    if (bean instanceof AbstractMessageListenerContainer listener) {
                        require(!listener.isRunning(), "LISTENER_ALREADY_RUNNING");
                        listener.setAutoStartup(false);
                    }
                    if (bean instanceof RabbitAdmin admin) admin.setAutoStartup(false);
                    return bean;
                }
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) factory.setAutoStartup(false);
                    return bean;
                }
            });
        });
        context.addApplicationListener(event -> {
            if (event instanceof ContextRefreshedEvent refreshed && refreshed.getApplicationContext() == context) {
                require(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(), "SCHEDULER_PRESENT");
                context.getBeansOfType(AbstractMessageListenerContainer.class).values().forEach(listener ->
                        require(!listener.isRunning() && !listener.isAutoStartup(), "LISTENER_RUNNING"));
                context.getBeansOfType(RabbitListenerEndpointRegistry.class).values().forEach(registry ->
                        registry.getListenerContainers().forEach(listener ->
                                require(!listener.isRunning() && !listener.isAutoStartup(), "REGISTRY_LISTENER_RUNNING")));
            }
        });
    }
}
