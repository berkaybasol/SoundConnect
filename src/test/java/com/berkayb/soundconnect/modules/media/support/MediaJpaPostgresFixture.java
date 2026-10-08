package com.berkayb.soundconnect.modules.media.support;

import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.Listing;
import com.berkayb.soundconnect.modules.marketplace.media.*;
import com.berkayb.soundconnect.modules.marketplace.repository.MarketplaceRepository;
import com.berkayb.soundconnect.modules.marketplace.service.MarketplaceService;
import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.abuse.MediaUploadGuardProperties;
import com.berkayb.soundconnect.modules.media.deletion.*;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.image.ImageVariantBackfillFinalizer;
import com.berkayb.soundconnect.modules.media.repository.*;
import com.berkayb.soundconnect.modules.media.service.*;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.ownership.*;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated real ORM/JDBC transaction fixture. Never imports application.yml or credentials. */
@Testcontainers(disabledWithoutDocker = true)
public abstract class MediaJpaPostgresFixture {
    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");

    protected AnnotationConfigApplicationContext context;
    protected NamedParameterJdbcTemplate jdbc;
    protected TransactionTemplate transaction;
    protected EntityManagerFactory emf;
    protected MediaAssetRepository assets;
    protected MarketplaceService service;
    protected MarketplaceMediaLifecycle lifecycle;
    protected MarketplaceRepository listings;
    protected RecordingEvents events;
    protected UUID seller, buyer, sellerProfile;
    private TimeZone originalZone;

    @BeforeEach
    void createFixture() throws Exception {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        }
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", DataSource.class, () -> source);
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(NamedParameterJdbcTemplate.class);
        emf = context.getBean(EntityManagerFactory.class);
        transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        assets = context.getBean(MediaAssetRepository.class);
        lifecycle = context.getBean(MarketplaceMediaLifecycle.class);
        service = context.getBean(MarketplaceService.class);
        listings = context.getBean(MarketplaceRepository.class);
        events = context.getBean(RecordingEvents.class);
        jdbc.getJdbcTemplate().execute("""
                create table tbl_user(id uuid primary key,user_name text not null,status text not null default 'ACTIVE',email_verified boolean not null default true,erased_at timestamptz);
                create table tbl_role(id uuid primary key,name text unique not null);
                create table user_roles(user_id uuid not null,role_id uuid not null,primary key(user_id,role_id));
                create table tbl_permissions(id uuid primary key,name text unique,created_at timestamp,updated_at timestamp);
                create table role_permissions(role_id uuid,permission_id uuid,primary key(role_id,permission_id));
                create table user_permissions(user_id uuid,permission_id uuid,primary key(user_id,permission_id));
                create table tbl_musician_profile(id uuid primary key,user_id uuid,name text,profile_picture_media_id uuid);
                create table tbl_studio_profile(id uuid primary key,user_id uuid,name text,profile_picture_media_id uuid);
                create table tbl_venues(id uuid primary key,owner_id uuid,name text);
                create table tbl_venue_profile(id uuid primary key,venue_id uuid unique references tbl_venues(id),profile_picture_media_id uuid);
                create table "tbl_listener-profile"(id uuid primary key,user_id uuid);
                create table tbl_organizer_profile(id uuid primary key,user_id uuid);
                create table tbl_producer_profile(id uuid primary key,user_id uuid);
                create table tbl_city(id uuid primary key,name text);
                create table tbl_district(id uuid primary key,city_id uuid references tbl_city(id),name text);
                create table test_allowed_commit(id integer primary key);
                create table test_commit_guard(id integer references test_allowed_commit(id) deferrable initially deferred);
                """);
        for (String migration : List.of("2026-09-20-marketplace-domain.sql", "2026-09-20-marketplace-media.sql",
                "2026-09-20-protected-image-thumbnails.sql")) {
            jdbc.getJdbcTemplate().execute(Files.readString(Path.of("scripts/db", migration)));
        }
        seller = account("media-seller"); buyer = account("media-buyer");
        sellerProfile = jdbc.queryForObject("select id from tbl_musician_profile where user_id=:id", Map.of("id", seller), UUID.class);
        when(context.getBean(ProfileOwnershipResolver.class).resolveOwnedProfiles(eq(seller), anySet()))
                .thenReturn(List.of(new OwnedProfileTarget(ProfileType.MUSICIAN, sellerProfile, "Seller", null)));
    }

    @AfterEach
    void closeFixture() {
        if (context != null) context.close();
        if (originalZone != null) TimeZone.setDefault(originalZone);
    }

    protected UUID account(String name) {
        UUID id = UUID.randomUUID(), role = UUID.randomUUID();
        jdbc.update("insert into tbl_user(id,user_name) values(:id,:name)", Map.of("id", id, "name", name));
        jdbc.update("insert into tbl_role values(:id,'ROLE_MUSICIAN') on conflict(name) do nothing", Map.of("id", role));
        jdbc.update("insert into user_roles select :id,id from tbl_role where name='ROLE_MUSICIAN'", Map.of("id", id));
        jdbc.update("insert into tbl_musician_profile(id,user_id,name) values(:profile,:id,:name)",
                Map.of("profile", UUID.randomUUID(), "id", id, "name", name));
        return id;
    }

    protected Listing draft() {
        return service.createDraft(seller, new MarketplaceRequests.Draft(UUID.randomUUID()));
    }

    protected MediaAsset image(UUID listing) {
        return tx(() -> {
            MediaAsset asset = assets.saveAndFlush(MediaAsset.builder()
                    .ownerType(MediaOwnerType.MARKETPLACE).ownerId(listing).kind(MediaKind.IMAGE)
                    .visibility(MediaVisibility.PRIVATE).contentAudience(MediaContentAudience.BACKSTAGE)
                    .status(MediaStatus.READY).mimeType("image/jpeg").size(1000L).build());
            asset.setStorageKey("protected/private-verified/media/" + asset.getId() + "/attempts/" + UUID.randomUUID() + "/source.jpg");
            // Old, closed PUT authority avoids involving clocks/network in final deletion tests.
            asset.setUploadWriteAuthorityExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(8));
            return assets.saveAndFlush(asset);
        });
    }

    protected MediaAsset asset(UUID id) { return assets.findById(id).orElseThrow(); }

    protected Listing update(Listing listing, UUID... photos) {
        return service.update(seller, listing.id(), new MarketplaceRequests.Update(listing.version(),
                "Transaction guitar", "Isolated test fixture description", null, null, null,
                null, null, null, List.of(photos), false, null));
    }

    protected void age(UUID id, LocalDateTime createdAt) {
        tx(() -> {
            var em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
            em.createQuery("update MediaAsset m set m.createdAt=:createdAt where m.id=:id")
                    .setParameter("createdAt", createdAt).setParameter("id", id).executeUpdate();
            return null;
        });
    }

    protected List<UUID> photos(UUID listing) {
        return jdbc.queryForList("select media_asset_id from tbl_marketplace_listing_photo where listing_id=:id order by position",
                Map.of("id", listing), UUID.class);
    }

    protected UUID retainInReport(UUID listing, UUID asset) {
        UUID report = UUID.randomUUID();
        jdbc.update("""
                insert into tbl_marketplace_report(id,listing_id,reporter_user_id,client_request_id,reason,evidence)
                values(:id,:listing,:buyer,:request,'SCAM','{}'::jsonb)
                """, Map.of("id", report, "listing", listing, "buyer", buyer, "request", UUID.randomUUID()));
        jdbc.update("insert into tbl_marketplace_report_photo values(:report,:asset)", Map.of("report", report, "asset", asset));
        return report;
    }

    protected <T> T tx(Supplier<T> work) { return transaction.execute(status -> work.get()); }

    /** AFTER_COMMIT observer plus controlled immediate/commit-time database fault injection. */
    public static class RecordingEvents {
        public final List<UUID> committed = new CopyOnWriteArrayList<>();
        public boolean failImmediately, failAtCommit;
        private final NamedParameterJdbcTemplate jdbc;
        RecordingEvents(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
        @EventListener
        public void beforeCommit(MediaDeletionRequestedEvent event) {
            if (failImmediately) throw new IllegalStateException("Injected transaction failure");
            if (failAtCommit) jdbc.getJdbcTemplate().update("insert into test_commit_guard values(999)");
        }
        @TransactionalEventListener
        public void afterCommit(MediaDeletionRequestedEvent event) { committed.add(event.assetId()); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = MediaAssetRepository.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MediaAssetReferenceRepository.class))
    @Import(JpaAuditingConfig.class)
    static class Config {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(MediaAsset.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create",
                    "hibernate.jdbc.time_zone", "UTC",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory, DataSource source) {
            var manager = new JpaTransactionManager(factory);
            manager.setDataSource(source);
            return manager;
        }
        @Bean NamedParameterJdbcTemplate jdbc(DataSource source) { return new NamedParameterJdbcTemplate(source); }
        @Bean MarketplaceAccess access(NamedParameterJdbcTemplate jdbc) { return new MarketplaceAccess(jdbc); }
        @Bean MarketplaceMediaAccess mediaAccess(MarketplaceAccess access, NamedParameterJdbcTemplate jdbc) {
            return new MarketplaceMediaAccess(access, jdbc);
        }
        @Bean MediaAssetReferenceGuard referenceGuard(NamedParameterJdbcTemplate jdbc) throws Exception {
            // Other modules are outside this fixture. Execute the exact production marketplace
            // native reference query against real tables, rather than mocking its answer.
            var references = mock(MediaAssetReferenceRepository.class);
            String sql = MediaAssetReferenceRepository.class.getMethod("countMarketplaceReferences", UUID.class)
                    .getAnnotation(Query.class).value();
            when(references.countMarketplaceReferences(any())).thenAnswer(call ->
                    jdbc.queryForObject(sql, Map.of("assetId", call.getArgument(0)), Long.class));
            return new MediaAssetReferenceGuard(references);
        }
        @Bean MediaUploadAbuseGuard abuse() {
            var properties = new MediaUploadGuardProperties();
            properties.setEnabled(false);
            return spy(new MediaUploadAbuseGuard(mock(org.springframework.data.redis.core.StringRedisTemplate.class), properties));
        }
        @Bean MarketplaceMediaLifecycle lifecycle(MarketplaceMediaAccess access, NamedParameterJdbcTemplate jdbc,
                MediaAssetRepository assets, MediaAssetReferenceGuard references, ApplicationEventPublisher events,
                MediaUploadAbuseGuard abuse) {
            return new MarketplaceMediaLifecycle(access, jdbc, assets, references, events, abuse, new MediaDeletionProperties());
        }
        @Bean MarketplaceRepository listings(NamedParameterJdbcTemplate jdbc) {
            return new MarketplaceRepository(jdbc, new ObjectMapper().findAndRegisterModules(), mock(MediaAssetService.class));
        }
        @Bean ProfileOwnershipResolver profiles() { return mock(ProfileOwnershipResolver.class); }
        @Bean LocationEntityFinder locations() { return mock(LocationEntityFinder.class); }
        @Bean MarketplaceService service(MarketplaceRepository listings, MarketplaceAccess access,
                ProfileOwnershipResolver profiles, LocationEntityFinder locations, MarketplaceMediaLifecycle lifecycle) {
            return new MarketplaceService(listings, access, profiles, locations, lifecycle);
        }
        @Bean RecordingEvents events(NamedParameterJdbcTemplate jdbc) { return new RecordingEvents(jdbc); }
        @Bean ImageVariantBackfillFinalizer finalizer(MediaAssetRepository assets) { return new ImageVariantBackfillFinalizer(assets); }
        @Bean MediaDeletionStateService deletionState(MediaAssetRepository assets) { return new MediaDeletionStateService(assets); }
    }
}
