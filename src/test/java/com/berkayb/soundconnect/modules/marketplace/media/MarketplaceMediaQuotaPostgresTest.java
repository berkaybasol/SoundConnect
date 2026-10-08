package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MarketplaceMediaQuotaPostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    MarketplaceMediaAccess access;
    UUID owner, listing;

    @BeforeEach void prepare() throws Exception {
        var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var named = new NamedParameterJdbcTemplate(source);
        access = new MarketplaceMediaAccess(new MarketplaceAccess(named), named);
        jdbc.execute("drop schema public cascade; create schema public");
        jdbc.execute("""
                create table tbl_user(id uuid primary key,status text,email_verified boolean,erased_at timestamp);
                create table tbl_role(id uuid primary key,name text);
                create table user_roles(user_id uuid,role_id uuid);
                create table tbl_permissions(id uuid primary key,name text);
                create table user_permissions(user_id uuid,permission_id uuid);
                create table role_permissions(role_id uuid,permission_id uuid);
                create table "tbl_listener-profile"(user_id uuid);
                create table tbl_musician_profile(id uuid,user_id uuid);
                create table tbl_studio_profile(id uuid,user_id uuid);
                create table tbl_organizer_profile(user_id uuid);
                create table tbl_producer_profile(user_id uuid);
                create table tbl_venues(id uuid,owner_id uuid);
                create table tbl_marketplace_listing(id uuid primary key,owner_user_id uuid,status text,seller_profile_type text,seller_profile_id uuid);
                create table tbl_marketplace_listing_photo(listing_id uuid,media_asset_id uuid);
                create table tbl_marketplace_report_photo(report_id uuid,media_asset_id uuid);
                create table tbl_media_asset(id uuid primary key,owner_type varchar(32),owner_id uuid,status text,
                    kind text,visibility text,content_audience text,created_at timestamp default current_timestamp,
                    constraint installation_specific_owner_check check(owner_type in ('USER','PROMOTION')));
                """);
        // Exercise actual production enum/strict-private migration, including retry.
        String migration = Files.readString(Path.of("scripts/db/2026-09-20-marketplace-media.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration);
        owner = UUID.randomUUID(); listing = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        jdbc.update("insert into tbl_user values(?,'ACTIVE',true,null)", owner);
        jdbc.update("insert into tbl_musician_profile values(?,?)", owner, owner);
        jdbc.update("insert into tbl_role values(?,'ROLE_MUSICIAN')", role);
        jdbc.update("insert into user_roles values(?,?)", owner, role);
        jdbc.update("insert into tbl_marketplace_listing values(?,?,'DRAFT','MUSICIAN',?)", listing, owner, owner);
    }

    @Test void competingInitializersCanReserveOnlyOneRemainingSlot() throws Exception {
        for (int i = 0; i < 15; i++) insertPhoto();
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> reserve = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    transaction.executeWithoutResult(ignored -> {
                        access.requireUploadOwner(owner, listing);
                        insertPhoto();
                    });
                    return true;
                } catch (SoundConnectException quotaReached) { return false; }
            };
            Future<Boolean> first = executor.submit(reserve), second = executor.submit(reserve);
            assertThat((first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_media_asset", Integer.class)).isEqualTo(16);
    }

    @Test void databaseRejectsPublicMarketplaceImagesAndListenerCannotMintUpload() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into tbl_media_asset(id,owner_type,owner_id,status,kind,visibility,content_audience)
                values(?,'MARKETPLACE',?,'READY','IMAGE','PUBLIC','BACKSTAGE')
                """, UUID.randomUUID(), listing)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("insert into \"tbl_listener-profile\" values(?)", owner);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> access.requireUploadOwner(owner, listing)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void recreatedSellerProfileCannotReuseOldListingUploadOwnership() {
        jdbc.update("update tbl_musician_profile set id=? where user_id=?", UUID.randomUUID(), owner);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> access.requireUploadOwner(owner, listing)))
                .isInstanceOf(SoundConnectException.class);
    }

    private void insertPhoto() {
        jdbc.update("""
                insert into tbl_media_asset(id,owner_type,owner_id,status,kind,visibility,content_audience)
                values(?,'MARKETPLACE',?,'UPLOADING','IMAGE','PRIVATE','BACKSTAGE')
                """, UUID.randomUUID(), listing);
    }
}
