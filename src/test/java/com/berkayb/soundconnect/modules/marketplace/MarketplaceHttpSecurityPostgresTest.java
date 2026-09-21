package com.berkayb.soundconnect.modules.marketplace;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.location.entity.*;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionDispatcher;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.storage.StorageAccessUrl;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP filters, JWT, DB account loading and domain services; no listening application port. */
@SpringBootTest(classes={SoundConnectApplication.class,MarketplaceHttpSecurityPostgresTest.MessagingIsolation.class}, webEnvironment=SpringBootTest.WebEnvironment.MOCK,
        properties={"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
                "app.jwt.secret=marketplace-regression-only-synthetic-signing-key",
                "app.jwt.issuer=marketplace-regression", "app.jwt.expiration=600000",
                "app.messaging.notification.exchange=marketplace-regression-notifications",
                "app.share-base-url=https://soundconnect.invalid",
                "mail.queueName=marketplace-regression-mail", "mail.dlq=marketplace-regression-mail-dlq",
                "mailersend.connectTimeoutMs=500", "mailersend.readTimeoutSec=1"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MarketplaceHttpSecurityPostgresTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class MessagingIsolation {
        @Bean static BeanPostProcessor disableExternalConsumers() {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean,String name) {
                    // The legacy mail factory does not consume Boot's global auto-startup property.
                    if(bean instanceof org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory<?> factory)
                        factory.setAutoStartup(false);
                    return bean;
                }
            };
        }
    }
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",DB::getJdbcUrl);
        properties.add("spring.datasource.username",DB::getUsername);
        properties.add("spring.datasource.password",DB::getPassword);
        properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    // Only external I/O and asynchronous delivery are replaced, never auth/domain/repositories.
    @MockitoBean StorageClient storage;
    @MockitoBean MediaDeletionDispatcher deletionDispatcher;
    @MockitoBean org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @MockitoBean(name="rabbitConnectionFactory") org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnection;
    @MockitoBean org.springframework.amqp.support.converter.Jackson2JsonMessageConverter rabbitJson;
    @MockitoBean org.springframework.data.redis.connection.RedisConnectionFactory redisConnection;
    @MockitoBean org.springframework.data.redis.core.RedisTemplate<String,String> redis;
    @MockitoBean org.springframework.data.redis.core.StringRedisTemplate strings;
    @MockitoBean OtpService otp;
    @MockitoBean MailJobHelper mailJobs;
    @MockitoBean MailSenderClient mail;
    @MockitoBean com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer mailConsumer;
    boolean migrated;
    City city;
    District district;
    Neighborhood neighborhood;
    static final String BASE="/api/v1/user/marketplace";

    @BeforeEach void prepare() throws Exception {
        if(!migrated) {
            for(String file:List.of("2026-09-20-marketplace-domain.sql","2026-09-20-marketplace-media.sql",
                    "2026-09-20-protected-image-thumbnails.sql")) jdbc.execute(Files.readString(Path.of("scripts/db",file)));
            migrated=true;
        }
        new TransactionTemplate(transactions).executeWithoutResult(ignored->{
            String suffix=UUID.randomUUID().toString().substring(0,8);
            city=City.builder().name("Test City "+suffix).build();em.persist(city);
            district=District.builder().name("Test District "+suffix).city(city).build();em.persist(district);
            neighborhood=Neighborhood.builder().name("Test Neighborhood "+suffix).district(district).build();em.persist(neighborhood);
        });
        reset(storage);
    }

    @Test void anonymousMalformedExpiredAndUnknownAccountBearerAreRejectedBeforeDomainIo() throws Exception {
        Actor owner=actor("MUSICIAN");
        String expired=bearer(owner.id(),Instant.now().minusSeconds(60),"marketplace-regression",
                "marketplace-regression-only-synthetic-signing-key");
        String unknown=bearer(UUID.randomUUID(),Instant.now().plusSeconds(60),"marketplace-regression",
                "marketplace-regression-only-synthetic-signing-key");
        String wrongIssuer=bearer(owner.id(),Instant.now().plusSeconds(60),"untrusted-issuer",
                "marketplace-regression-only-synthetic-signing-key");
        String wrongSignature=bearer(owner.id(),Instant.now().plusSeconds(60),"marketplace-regression",
                "different-synthetic-regression-signing-key-not-trusted");
        mvc.perform(get(BASE+"/categories")).andExpect(status().isUnauthorized());
        for(String token:List.of("invalid.token.value",expired,unknown,wrongIssuer,wrongSignature)) {
            mvc.perform(get(BASE+"/categories").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        }
        jdbc.update("update tbl_user set status='INACTIVE' where id=?",owner.id());
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isUnauthorized());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest @ValueSource(strings={"MUSICIAN","STUDIO","VENUE"})
    void eachBackstageProfileCreatesAndReadsOnlyItsOwnDraftThroughRealBearer(String role) throws Exception {
        Actor owner=actor(role),other=actor("MUSICIAN");
        UUID request=UUID.randomUUID();
        JsonNode first=draft(owner,request),retry=draft(owner,request);
        assertThat(retry.path("id").asText()).isEqualTo(first.path("id").asText());
        mvc.perform(auth(get(BASE+"/listings/"+first.path("id").asText()),other)).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE+"/listings/"+first.path("id").asText()),owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store, private"))
                .andExpect(jsonPath("$.data.seller.profileType").value(role));
        mvc.perform(auth(put(BASE+"/listings/"+first.path("id").asText())
                .contentType("application/json").content("{\"expectedVersion\":0,\"photoIds\":[],\"negotiable\":false}"),other))
                .andExpect(status().isNotFound());
    }

    @Test void sameBearerImmediatelyLosesAccessWhenAccountRolesOrProfileChange() throws Exception {
        Actor owner=actor("MUSICIAN");
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isOk());
        addRole(owner.id(),"LISTENER");
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isForbidden());
        jdbc.update("delete from user_roles where user_id=? and role_id in(select id from tbl_role where name='ROLE_LISTENER')",owner.id());
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isForbidden());
        jdbc.update("delete from \"tbl_listener-profile\" where user_id=?",owner.id());
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isOk());
        jdbc.update("delete from tbl_musician_profile where user_id=?",owner.id());
        mvc.perform(auth(get(BASE+"/categories"),owner)).andExpect(status().isForbidden());
        Actor listener=actor("LISTENER");
        addRole(listener.id(),"MUSICIAN");
        mvc.perform(auth(post(BASE+"/drafts").contentType("application/json")
                .content("{\"clientRequestId\":\""+UUID.randomUUID()+"\"}"),listener)).andExpect(status().isForbidden());
    }

    @Test void invalidHttpMoneyVersionAndAttachmentsCannotPartiallyMutateDraft() throws Exception {
        Actor owner=actor("MUSICIAN");
        UUID listing=UUID.fromString(draft(owner,UUID.randomUUID()).path("id").asText());
        for(String price:List.of("1.99","\"199\"","true","9223372036854775808","0","-1","100000000001")) {
            mvc.perform(auth(put(BASE+"/listings/"+listing).contentType("application/json")
                    .content("{\"expectedVersion\":0,\"title\":\"Must not persist\",\"priceMinor\":"+price+",\"photoIds\":[],\"negotiable\":false}"),owner))
                    .andExpect(status().isBadRequest());
        }
        for(String body:List.of(
                "{\"expectedVersion\":0.5,\"photoIds\":[],\"negotiable\":false}",
                "{\"expectedVersion\":0,\"photoIds\":[null],\"negotiable\":false}",
                "{\"expectedVersion\":0,\"negotiable\":false}")) {
            mvc.perform(auth(put(BASE+"/listings/"+listing).contentType("application/json").content(body),owner))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(auth(get(BASE+"/listings/"+listing),owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0)).andExpect(jsonPath("$.data.title").isEmpty())
                .andExpect(jsonPath("$.data.priceMinor").isEmpty()).andExpect(jsonPath("$.data.photos").isEmpty());
        verifyNoInteractions(storage);
    }

    @Test void unicodeHttpTitleUsesDatabaseCharacterLimitsAndReturnsDomainValidationOnPublish() throws Exception {
        Actor owner=actor("MUSICIAN");
        UUID listing=UUID.fromString(draft(owner,UUID.randomUUID()).path("id").asText());
        UUID category=jdbc.queryForObject("select id from tbl_marketplace_category where code='ELECTRIC_GUITAR'",UUID.class);
        String emoji="\uD83C\uDFB8";
        var body=json.createObjectNode().put("expectedVersion",0).put("title",emoji.repeat(120))
                .put("description",emoji.repeat(10)).put("categoryId",category.toString()).put("condition","USED")
                .put("priceMinor",1000).put("districtId",district.getId().toString()).put("negotiable",false)
                .put("deliveryMethod","PICKUP");
        body.putArray("photoIds").add(photo(listing).getId().toString());
        mvc.perform(auth(put(BASE+"/listings/"+listing).contentType("application/json").content(json.writeValueAsString(body)),owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value(emoji.repeat(120)))
                .andExpect(jsonPath("$.data.version").value(1));
        body.put("expectedVersion",1).put("title",emoji.repeat(121));
        mvc.perform(auth(put(BASE+"/listings/"+listing).contentType("application/json").content(json.writeValueAsString(body)),owner))
                .andExpect(status().isBadRequest());
        body.put("title",emoji.repeat(3));
        mvc.perform(auth(put(BASE+"/listings/"+listing).contentType("application/json").content(json.writeValueAsString(body)),owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(auth(post(BASE+"/listings/"+listing+"/publish").contentType("application/json")
                .content("{\"expectedVersion\":2}"),owner)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(9945));
        mvc.perform(auth(get(BASE+"/listings/"+listing),owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT")).andExpect(jsonPath("$.data.version").value(2));
    }

    @Test void privateOriginalAndThumbnailRequireCurrentListingVisibilityAndAttachment() throws Exception {
        Actor owner=actor("MUSICIAN"),buyer=actor("STUDIO"),listener=actor("LISTENER");
        UUID listing=UUID.fromString(draft(owner,UUID.randomUUID()).path("id").asText());
        MediaAsset photo=photo(listing),unattached=photo(listing);
        String path="/api/v1/user/media/"+photo.getId()+"/access-url";
        when(storage.createPresignedGetUrl(anyString())).thenAnswer(call->new StorageAccessUrl(
                "https://private.invalid/"+Integer.toUnsignedString(call.<String>getArgument(0).hashCode()),Instant.now().plusSeconds(300)));
        mvc.perform(auth(get(path),buyer)).andExpect(status().isNotFound());
        mvc.perform(auth(get(path),listener)).andExpect(status().isForbidden());
        verifyNoInteractions(storage);
        mvc.perform(auth(get(path),owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.thumbnailAccessUrl").isString()).andExpect(header().string("Cache-Control","no-store"));
        publishFixture(owner,listing,photo.getId());
        mvc.perform(auth(get(path),buyer)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/user/media/"+unattached.getId()+"/access-url"),buyer)).andExpect(status().isNotFound());
        clearInvocations(storage);
        mvc.perform(auth(post(BASE+"/listings/"+listing+"/withdraw").contentType("application/json")
                .content("{\"expectedVersion\":1}"),owner)).andExpect(status().isOk());
        mvc.perform(auth(get(path),buyer)).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test void moderatorPermissionIsReadFreshAndCannotBeInheritedByListener() throws Exception {
        Actor moderator=actor("ADMIN");
        String path="/api/v1/admin/marketplace/reports";
        jdbc.update("delete from role_permissions where role_id in(select id from tbl_role where name='ROLE_ADMIN')");
        mvc.perform(auth(get(path),moderator)).andExpect(status().isForbidden());
        jdbc.update("insert into user_permissions(user_id,permission_id) select ?,id from tbl_permissions where name='MANAGE_MARKETPLACE_REPORTS'",moderator.id());
        mvc.perform(auth(get(path),moderator)).andExpect(status().isOk());
        addRole(moderator.id(),"LISTENER");
        mvc.perform(auth(get(path),moderator)).andExpect(status().isForbidden());
        jdbc.update("delete from user_roles where user_id=? and role_id in(select id from tbl_role where name='ROLE_LISTENER')",moderator.id());
        mvc.perform(auth(get(path),moderator)).andExpect(status().isForbidden());
        jdbc.update("delete from \"tbl_listener-profile\" where user_id=?",moderator.id());
        mvc.perform(auth(get(path),moderator)).andExpect(status().isOk());
        jdbc.update("delete from user_permissions where user_id=?",moderator.id());
        mvc.perform(auth(get(path),moderator)).andExpect(status().isForbidden());
        jdbc.update("insert into role_permissions(role_id,permission_id) select r.id,p.id from tbl_role r cross join tbl_permissions p where r.name='ROLE_ADMIN' and p.name='MANAGE_MARKETPLACE_REPORTS'");
        mvc.perform(auth(get(path),moderator)).andExpect(status().isOk());
        jdbc.update("delete from role_permissions where role_id in(select id from tbl_role where name='ROLE_ADMIN')");
        mvc.perform(auth(get(path),moderator)).andExpect(status().isForbidden());
    }

    @Test void realHttpReportAndModerationRetriesKeepOneAuditAndProtectEvidence() throws Exception {
        Actor owner=actor("MUSICIAN"),buyer=actor("STUDIO"),moderator=actor("ADMIN");
        jdbc.update("insert into user_permissions(user_id,permission_id) select ?,id from tbl_permissions where name='MANAGE_MARKETPLACE_REPORTS'",moderator.id());
        UUID listing=UUID.fromString(draft(owner,UUID.randomUUID()).path("id").asText());
        MediaAsset photo=photo(listing);
        publishFixture(owner,listing,photo.getId());
        String reportBody=json.writeValueAsString(Map.of("clientRequestId",UUID.randomUUID(),"reason","SCAM","description","Test evidence"));
        UUID report=null;
        for(int attempt=0;attempt<2;attempt++) {
            var result=mvc.perform(auth(post(BASE+"/listings/"+listing+"/reports").contentType("application/json").content(reportBody),buyer))
                    .andExpect(status().isCreated()).andReturn();
            UUID reported=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asText());
            if(report!=null) assertThat(reported).isEqualTo(report);
            report=reported;
        }
        String path="/api/v1/admin/marketplace/reports/"+report+"/review";
        String reviewBody="{\"expectedVersion\":0,\"decision\":\"REMOVE_LISTING\",\"resolutionNote\":\"Confirmed test violation\"}";
        mvc.perform(auth(post(path).contentType("application/json").content(reviewBody),buyer)).andExpect(status().isForbidden());
        for(int attempt=0;attempt<2;attempt++) {
            mvc.perform(auth(post(path).contentType("application/json").content(reviewBody),moderator))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit where report_id=?",Long.class,report)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_photo where report_id=? and media_asset_id=?",Long.class,report,photo.getId())).isEqualTo(1);
        mvc.perform(auth(get(BASE+"/listings/"+listing),buyer)).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE+"/listings/"+listing),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("MODERATED"));
        String mediaPath="/api/v1/user/media/"+photo.getId()+"/access-url";
        mvc.perform(auth(get(mediaPath),buyer)).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        when(storage.createPresignedGetUrl(anyString())).thenReturn(new StorageAccessUrl("https://private.invalid/evidence",Instant.now().plusSeconds(300)));
        mvc.perform(auth(get(mediaPath),moderator)).andExpect(status().isOk()).andExpect(jsonPath("$.data.thumbnailAccessUrl").isString());
    }

    private record Actor(UUID id,String token) {}
    private Actor actor(String role) {
        return new TransactionTemplate(transactions).execute(ignored->{
            Role assigned=role(role);
            String name="qa"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            User user=User.builder().username(name).email(name+"@example.invalid").password("unused-test-password")
                    .status(UserStatus.ACTIVE).emailVerified(true).roles(new HashSet<>(Set.of(assigned))).build();em.persist(user);
            switch(role) {
                case "MUSICIAN"->em.persist(MusicianProfile.builder().user(user).name(name).build());
                case "STUDIO"->em.persist(StudioProfile.builder().user(user).name(name).build());
                case "VENUE"->{
                    Venue venue=Venue.builder().owner(user).name(name).address("Test address")
                            .city(city).district(district).neighborhood(neighborhood).status(VenueStatus.APPROVED).build();
                    em.persist(venue);
                    em.persist(VenueProfile.builder().venue(venue).build());
                }
                case "LISTENER"->em.persist(ListenerProfile.builder().user(user).visibilityChoiceCompleted(true).build());
            }
            em.flush();return new Actor(user.getId(),tokens.generateToken(new UserDetailsImpl(user)));
        });
    }
    private Role role(String name) {
        return em.createQuery("from Role where name=:name",Role.class).setParameter("name","ROLE_"+name)
                .getResultStream().findFirst().orElseGet(()->{Role role=Role.builder().name("ROLE_"+name).build();em.persist(role);return role;});
    }
    private void addRole(UUID user,String name) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored->{
            User account=em.find(User.class,user);account.getRoles().add(role(name));
            if(name.equals("LISTENER")) em.persist(ListenerProfile.builder().user(account).visibilityChoiceCompleted(true).build());
        });
    }
    private JsonNode draft(Actor owner,UUID request) throws Exception {
        var result=mvc.perform(auth(post(BASE+"/drafts").contentType("application/json")
                .content(json.writeValueAsString(Map.of("clientRequestId",request))),owner))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    private MediaAsset photo(UUID listing) {
        return new TransactionTemplate(transactions).execute(ignored->{
            MediaAsset photo=MediaAsset.builder().ownerType(MediaOwnerType.MARKETPLACE).ownerId(listing)
                    .kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PRIVATE)
                    .contentAudience(MediaContentAudience.BACKSTAGE).mimeType("image/jpeg").size(100L).build();
            em.persist(photo);em.flush();
            String prefix="protected/private-verified/media/"+photo.getId()+"/";
            photo.setStorageKey(prefix+"source.jpg");photo.setThumbnailStorageKey(prefix+"thumbnail.jpg");return photo;
        });
    }
    private void publishFixture(Actor owner,UUID listing,UUID photo) throws Exception {
        jdbc.update("""
                update tbl_marketplace_listing set title='Test guitar',description='Complete test description',
                category_id=(select id from tbl_marketplace_category where code='ELECTRIC_GUITAR'),
                condition='USED',price_minor=10000,district_id=?,delivery_method='PICKUP' where id=?
                """,district.getId(),listing);
        jdbc.update("insert into tbl_marketplace_listing_photo values(?,?,0)",listing,photo);
        mvc.perform(auth(post(BASE+"/listings/"+listing+"/publish").contentType("application/json")
                .content("{\"expectedVersion\":0}"),owner)).andExpect(status().isOk());
    }
    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,Actor actor) {
        return request.header("Authorization","Bearer "+actor.token());
    }
    private static String bearer(UUID user,Instant expiration,String issuer,String secret) {
        return Jwts.builder().setSubject(user.toString()).setIssuer(issuer).setExpiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),SignatureAlgorithm.HS256).compact();
    }
}
