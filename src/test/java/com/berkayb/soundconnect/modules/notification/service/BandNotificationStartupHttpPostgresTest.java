package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.auth.ratelimit.*;
import com.berkayb.soundconnect.auth.security.*;
import com.berkayb.soundconnect.auth.service.CustomUserDetailsService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.performer.outbox.*;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestServiceImpl;
import com.berkayb.soundconnect.modules.event.plan.*;
import com.berkayb.soundconnect.modules.event.support.*;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.controller.user.NotificationController;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.service.*;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileChoiceStatusReader;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.config.SecurityConfig;
import com.berkayb.soundconnect.shared.exception.GlobalExceptionHandler;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.*;
import com.berkayb.soundconnect.shared.security.*;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.beans.factory.InitializingBean;
import java.nio.file.*;
import com.berkayb.soundconnect.modules.notification.config.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller.BandUserController;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandServiceImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.mapper.BandMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real ApplicationRunner rejection/retry and normal BAND controller over loopback TCP.
 * Auth, domain, identity guard and persistence are real. Only outbound decoration,
 * websocket/mail and unrelated event-performer side effects are test doubles.
 */
@Testcontainers
class BandNotificationStartupHttpPostgresTest {
    static final String OWNER = "bil007-band-startup";
    static final String KEY = UUID.randomUUID().toString() + UUID.randomUUID();
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil007_band_startup").withUsername("fixture").withPassword("disposable")
            .withLabel("soundconnect.fixture", OWNER).withReuse(false);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5-alpine")
            .withExposedPorts(6379).withLabel("soundconnect.fixture", OWNER).withReuse(false);

    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.class)
    @EntityScan("com.berkayb.soundconnect")
    @EnableJpaRepositories("com.berkayb.soundconnect")
    @EnableTransactionManagement(proxyTargetClass=true)
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
            CustomUserDetailsService.class, JwtUtil.class, ListenerProfileChoiceGate.class,
            ListenerProfileChoiceStatusReader.class, VenueApplicationSessionAccess.class,
            AuthRateLimitConfiguration.class, AuthRateLimiter.class, SecurityErrorResponseWriter.class,
            RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, GlobalExceptionHandler.class,
            BandUserController.class, BandServiceImpl.class, BandEntityFinder.class, UserEntityFinder.class,
            TransactionalNotificationService.class, NotificationController.class, NotificationServiceImpl.class, AfterCommitDeliveryExecutor.class,
            NotificationDeliveryPolicy.class, AccountDeliveryFence.class, GhostListenerIdentityBatchResolver.class,
            NotificationBadgeCacheHelper.class, BandNotificationIdentitySchema.class, MediaNotificationIdentitySchema.class,
            com.berkayb.soundconnect.shared.config.JpaAuditingConfig.class})
    static class Fixture {
        @Bean DataSource dataSource() throws Exception {
            assertThat(PG.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
            var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
            try(var c=ds.getConnection()) {
                assertThat(c.getMetaData().getURL()).isEqualTo(PG.getJdbcUrl());
                assertThat(c.getCatalog()).isEqualTo("bil007_band_startup");
            }
            return ds;
        }
        @Bean @DependsOn("entityManagerFactory") InitializingBean prerequisites(JdbcTemplate jdbc) {
            return () -> {
                for(String name:List.of("2026-09-10-listener-account-erasure.sql", "2026-09-28-media-notification-identity.sql"))
                    jdbc.execute(Files.readString(Path.of("scripts/db",name)));
            };
        }
        @Bean BandMapper bandMapper() { return org.mapstruct.factory.Mappers.getMapper(BandMapper.class); }
        @Bean NotificationMapper notificationMapper() { return org.mapstruct.factory.Mappers.getMapper(NotificationMapper.class); }
        @Bean MediaAssetService media() { return mock(MediaAssetService.class); }
        @Bean NotificationWebSocketService websocket() { return mock(NotificationWebSocketService.class); }
        @Bean MailProducer mail() { return mock(MailProducer.class); }
        @Bean com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService performers() {
            return mock(com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService.class);
        }
    }
    ServletWebServerApplicationContext start() {
        var app = new SpringApplication(Fixture.class);
        return (ServletWebServerApplicationContext) app.run(
                "--spring.config.location=optional:classpath:/bil007-owned.yml", "--spring.config.import=",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.main.web-application-type=servlet",
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.open-in-view=false",
                "--spring.data.redis.host="+REDIS.getHost(), "--spring.data.redis.port="+REDIS.getMappedPort(6379),
                "--app.jwt.secret="+KEY, "--app.jwt.expiration=600000", "--app.jwt.issuer=bil007-fixture",
                "--app.share-base-url=https://bil007.invalid", "--app.notification.push.enabled=false",
                "--logging.level.org.hibernate.SQL=OFF", "--logging.level.org.hibernate.orm.jdbc.bind=OFF");
    }
    JdbcTemplate jdbc;
    EntityManager em;
    JwtTokenProvider tokens;
    ObjectMapper json;
    int port;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test void missingBandGuardBlocksActualStartupThenRegistryAllowsAuthenticatedBandHttp() throws Exception {
        assertThat(REDIS.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", OWNER);
        var rejected = catchThrowable(this::start);
        assertThat(rejected).isNotNull();
        Throwable cause=rejected;
        while(cause.getCause()!=null) cause=cause.getCause();
        assertThat(cause).isInstanceOf(IllegalStateException.class)
                .hasMessage("Apply 2026-09-29-band-notification-identity.sql before this binary");
        assertThat(Arrays.stream(cause.getStackTrace()).anyMatch(f -> f.getClassName().equals(BandNotificationIdentitySchema.class.getName()))).isTrue();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        new MediaNotificationIdentitySchema(jdbc).run(null);
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations where migration_id='2026-09-29-band-notification-identity'", Integer.class)).isZero();
        // An unrelated fixture row must survive registry application and the second startup.
        UUID unrelated=UUID.randomUUID(), source=UUID.randomUUID(), recipient=UUID.randomUUID();
        jdbc.update("insert into tbl_notification(id,recipient_id,type,title,message,payload,is_read,source_event_id,created_at,updated_at,occurred_at) values(?,?,'DM_NEW_MESSAGE','untouched','fixture','{}'::jsonb,true,?,now(),now(),now())",unrelated,recipient,source);
        String before=jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,unrelated);
        bootstrap();
        String marker=jdbc.queryForObject("select applied_at::text from soundconnect_schema_migrations where migration_id='2026-09-29-band-notification-identity'",String.class);
        bootstrap();
        assertThat(jdbc.queryForObject("select applied_at::text from soundconnect_schema_migrations where migration_id='2026-09-29-band-notification-identity'",String.class)).isEqualTo(marker);
        try(var app=start()) {
            assertThat(app.getBean(BandNotificationIdentitySchema.class)).isNotNull();
            port=app.getWebServer().getPort();assertThat(port).isPositive().isNotEqualTo(8080);
            em=app.getBean(EntityManager.class);tokens=app.getBean(JwtTokenProvider.class);json=app.getBean(ObjectMapper.class);
            var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            Actor founder=tx.execute(s -> actor()); Actor member=tx.execute(s -> actor()); Actor stranger=tx.execute(s -> actor());
            String bandPath="/api/v1/user/bands";
            request("POST",bandPath+"/create",null,"{\"name\":\"BIL007 owned band\"}",401);
            var band=request("POST",bandPath+"/create",founder.token,"{\"name\":\"BIL007 owned band\"}",200).at("/data/id").asText();
            assertThat(band).isNotBlank();
            request("POST",bandPath+"/"+band+"/invite?invitedUserId="+member.id,founder.token,null,200);
            UUID invitation=jdbc.queryForObject("select invitation_id from tbl_band_member where band_id=? and user_id=?",UUID.class,UUID.fromString(band),member.id);
            UUID notification=jdbc.queryForObject("select id from tbl_notification where recipient_id=? and type='BAND_INVITE_RECEIVED'",UUID.class,member.id);
            UUID event=jdbc.queryForObject("select source_event_id from tbl_notification where id=?",UUID.class,notification);
            String stored=jdbc.queryForObject("select payload::text from tbl_notification where id=?",String.class,notification);
            assertThat(stored).contains(band,invitation.toString(),"bandIdentityVersion").doesNotContain("BIL007 owned band","bandName","Username");
            assertThat(jdbc.queryForObject("select recipient_id from tbl_notification_receipt where source_event_id=?",UUID.class,event)).isEqualTo(member.id);
            String inbox="/api/v1/user/notifications";
            request("GET",inbox+"/"+notification,stranger.token,null,404);
            var exact=request("GET",inbox+"/"+notification,member.token,null,200);
            assertThat(exact.at("/data/payload/invitationId").asText()).isEqualTo(invitation.toString());
            assertThat(exact.at("/data/read").asBoolean()).isFalse();
            assertThat(request("GET",inbox,member.token,null,200).at("/data/content/0/id").asText()).isEqualTo(notification.toString());
            assertThat(request("GET",inbox+"/unread-count",member.token,null,200).at("/data/unread").asInt()).isEqualTo(1);
            request("POST",inbox+"/"+notification+"/read",member.token,null,200);
            assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,notification)).isTrue();
            request("POST",bandPath+"/"+band+"/accept?invitationId="+invitation,member.token,null,200);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification where recipient_id=? and type='BAND_INVITE_ACCEPTED'",Integer.class,founder.id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from tbl_notification_receipt where source_event_id=?",Integer.class,event)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select is_read from tbl_notification where id=?",Boolean.class,notification)).isTrue();
            assertThat(request("GET",inbox+"/unread-count",member.token,null,200).at("/data/unread").asInt()).isZero();
            assertThat(jdbc.queryForObject("select to_jsonb(n)::text from tbl_notification n where id=?",String.class,unrelated)).isEqualTo(before);
            verifyNoInteractions(app.getBean(MailProducer.class));
            System.out.println("BIL007_HTTP_PASS port="+port+" notification="+notification+" sourceEventId="+event+" recipient="+member.id+" invitation="+invitation+" read=true receipt=1 unrelated=preserved pg="+PG.getContainerId());
        }
    }
    void bootstrap() throws Exception {
        var process=new ProcessBuilder("pwsh","-NoProfile","-File","scripts/verify-band-bootstrap.ps1","-ContainerId",PG.getContainerId())
                .redirectErrorStream(true).start();
        var output=java.util.concurrent.CompletableFuture.supplyAsync(() -> {try{return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new RuntimeException(e);}});
        if(!process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();fail("Registry adapter timed out");}
        String result=output.get(5,java.util.concurrent.TimeUnit.SECONDS);
        System.out.println(result);
        assertThat(process.exitValue()).as(result).isZero();assertThat(result).contains("BIL007_REGISTRY_START","BIL007_REGISTRY_PASS");
    }
    Actor actor() {
        Role role=em.createQuery("select r from Role r where r.name='ROLE_MUSICIAN'",Role.class).getResultStream().findFirst().orElseGet(() -> {var r=Role.builder().name("ROLE_MUSICIAN").build();em.persist(r);return r;});
        User user=User.builder().username("bil007_"+UUID.randomUUID().toString().substring(0,12)).email(UUID.randomUUID()+"@test.invalid")
                .password("fixture-only").status(UserStatus.ACTIVE).emailVerified(true).roles(Set.of(role)).build();em.persist(user);
        em.persist(MusicianProfile.builder().user(user).stageName("BIL007 synthetic musician").build());
        return new Actor(user.getId(),tokens.generateToken(UserDetailsImpl.fromUser(user)));
    }
    record Actor(UUID id,String token) {} // Never log ephemeral authentication credentials.
    JsonNode request(String method,String path,String token,String body,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        System.out.println("BIL007_HTTP "+method+" "+path+" expected="+expected+" observed="+response.statusCode());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
