package com.berkayb.soundconnect.modules.marketplace;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.*;
import com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaLifecycle;
import com.berkayb.soundconnect.modules.marketplace.repository.MarketplaceRepository;
import com.berkayb.soundconnect.modules.marketplace.service.MarketplaceService;
import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.ownership.OwnedProfileTarget;
import com.berkayb.soundconnect.modules.profile.shared.ownership.ProfileOwnershipResolver;
import com.berkayb.soundconnect.shared.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only against its disposable container; never loads application config or local credentials. */
@Testcontainers(disabledWithoutDocker=true)
class MarketplacePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine");
    NamedParameterJdbcTemplate jdbc;
    TransactionTemplate transaction;
    MarketplaceRepository repository;
    MarketplaceAccess access;
    MarketplaceService service;
    MarketplaceMediaLifecycle media;
    MediaAssetService mediaAssets;
    ProfileOwnershipResolver profiles;
    LocationEntityFinder locations;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    UUID seller,buyer,admin,sellerProfile,city,district,category,root;

    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        jdbc=new NamedParameterJdbcTemplate(source);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        try(Connection c=source.getConnection(); Statement s=c.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
            s.execute("""
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
                    create table tbl_media_asset(id uuid primary key,owner_type text,owner_id uuid,visibility text not null,kind text not null,content_audience text not null,status text not null,created_at timestamp,storage_key text,thumbnail_url text);
                    """);
        }
        migrate();
        mediaAssets=mock(MediaAssetService.class);
        repository=new MarketplaceRepository(jdbc,json,mediaAssets);
        access=new MarketplaceAccess(jdbc);
        media=mock(MarketplaceMediaLifecycle.class);
        profiles=mock(ProfileOwnershipResolver.class);
        locations=mock(LocationEntityFinder.class);
        service=new MarketplaceService(repository,access,profiles,locations,media);
        seller=account("seller","MUSICIAN");buyer=account("buyer","STUDIO");admin=account("moderator","ADMIN");
        sellerProfile=jdbc.queryForObject("select id from tbl_musician_profile where user_id=:id",Map.of("id",seller),UUID.class);
        when(profiles.resolveOwnedProfiles(eq(seller),anySet())).thenReturn(List.of(new OwnedProfileTarget(ProfileType.MUSICIAN,sellerProfile,"Seller",null)));
        jdbc.update("insert into role_permissions select r.id,p.id from tbl_role r cross join tbl_permissions p where r.name='ROLE_ADMIN' and p.name='MANAGE_MARKETPLACE_REPORTS' on conflict do nothing",Map.of());
        city=UUID.randomUUID();district=UUID.randomUUID();
        jdbc.update("insert into tbl_city values(:id,'İstanbul')",Map.of("id",city));
        jdbc.update("insert into tbl_district values(:id,:city,'Kadıköy')",Map.of("id",district,"city",city));
        City c=City.builder().id(city).name("İstanbul").build();
        when(locations.getDistrict(district)).thenReturn(District.builder().id(district).name("Kadıköy").city(c).build());
        when(locations.getCity(city)).thenReturn(c);
        category=category("ELECTRIC_GUITAR");root=category("GUITARS");
    }

    @Test void migrationsAreRepeatableAndCatalogMatchesResourceExactly() throws Exception {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        migrate();migrate();
        assertThat(tx(()->service.detail(seller,draft.id())).id()).isEqualTo(draft.id());
        var roots=json.readTree(Files.readString(Path.of("src/main/resources/marketplace-category-seed.json")));
        Map<String,String> expected=new LinkedHashMap<>();
        for(var r:roots) {
            expected.put(r.get("code").asText(),r.get("name").asText());
            for(var child:r.get("children")) {
                expected.put(child.get("code").asText(),child.get("name").asText());
                assertThat(repository.category(category(child.get("code").asText())).orElseThrow().parentId())
                        .isEqualTo(category(r.get("code").asText()));
            }
        }
        Map<String,String> actual=new LinkedHashMap<>();
        repository.categories().forEach(row->actual.put(row.code(),row.name()));
        assertThat(actual).isEqualTo(expected);
        assertThat(tx(()->service.categories(seller))).hasSize(13);
        assertThatThrownBy(()->jdbc.update("insert into tbl_marketplace_listing_photo values(:listing,:asset,8)",
                Map.of("listing",draft.id(),"asset",asset(draft.id())))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update tbl_media_asset set visibility='PUBLIC' where owner_type='MARKETPLACE'",Map.of()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void eligibilityChecksFreshRolesProfilesAndAccountInsteadOfTokenClaims() {
        access.requireBackstage(seller);access.requireBackstage(buyer);
        UUID listener=account("listener","LISTENER");
        denied(()->access.requireBackstage(listener),ErrorType.MARKETPLACE_FORBIDDEN);
        role(seller,"LISTENER");denied(()->access.requireBackstage(seller),ErrorType.MARKETPLACE_FORBIDDEN);
        jdbc.update("delete from user_roles where user_id=:id and role_id in (select id from tbl_role where name='ROLE_LISTENER')",Map.of("id",seller));
        jdbc.update("insert into \"tbl_listener-profile\" values(:id,:user)",Map.of("id",UUID.randomUUID(),"user",seller));
        denied(()->access.requireBackstage(seller),ErrorType.MARKETPLACE_FORBIDDEN);
        jdbc.update("delete from \"tbl_listener-profile\" where user_id=:id",Map.of("id",seller));
        jdbc.update("update tbl_user set status='INACTIVE' where id=:id",Map.of("id",seller));
        denied(()->access.requireBackstage(seller),ErrorType.MARKETPLACE_FORBIDDEN);
        jdbc.update("update tbl_user set status='ACTIVE',email_verified=false where id=:id",Map.of("id",seller));
        denied(()->access.requireBackstage(seller),ErrorType.MARKETPLACE_FORBIDDEN);
        jdbc.update("update tbl_user set email_verified=true,erased_at=now() where id=:id",Map.of("id",seller));
        denied(()->access.requireBackstage(seller),ErrorType.MARKETPLACE_FORBIDDEN);
    }

    @Test void protectedThumbnailMigrationKeepsLegacyRowsAndRejectsCrossAssetOrPublicReferences() throws Exception {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        UUID photo=asset(draft.id());
        migrate();
        assertThat(jdbc.queryForObject("select thumbnail_storage_key from tbl_media_asset where id=:id",
                Map.of("id",photo),String.class)).isNull();
        String source="protected/private-verified/media/"+photo+"/source.jpg";
        String thumb=source.replace("source.jpg","thumbnail.jpg");
        jdbc.update("update tbl_media_asset set storage_key=:source,thumbnail_storage_key=:thumbnail where id=:id",
                Map.of("id",photo,"source",source,"thumbnail",thumb));
        assertThatThrownBy(()->jdbc.update("update tbl_media_asset set thumbnail_storage_key='protected/private-verified/media/other/thumbnail.jpg' where id=:id",
                Map.of("id",photo))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update tbl_media_asset set thumbnail_url='https://public.invalid/thumb.jpg' where id=:id",
                Map.of("id",photo))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update tbl_media_asset set storage_key=null where id=:id",
                Map.of("id",photo))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void draftRetryIsSingleRecordAndConcurrentRetryUsesAccountFence() throws Exception {
        UUID request=UUID.randomUUID();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Callable<UUID> task=()->{start.await();return tx(()->service.createDraft(seller,new Draft(request))).id();};
            Future<UUID> first=executor.submit(task),second=executor.submit(task);start.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS)).isEqualTo(second.get(20,TimeUnit.SECONDS));
            assertThat(repository.countByOwner(seller,Status.DRAFT)).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @Test void everyServiceEntryPointRejectsListenerBeforeLookingUpRequestsOrListings() {
        UUID listener=account("private-listener","LISTENER"),id=UUID.randomUUID();
        List<Runnable> calls=List.of(
                ()->service.categories(listener),()->service.discovery(listener,null,0,20),
                ()->service.mine(listener,null,0,20),()->service.saved(listener,0,20),()->service.detail(listener,id),
                ()->service.createDraft(listener,null),()->service.update(listener,id,null),
                ()->service.publish(listener,id,null),()->service.sold(listener,id,null),()->service.withdraw(listener,id,null),
                ()->service.deleteDraft(listener,id,0),()->service.save(listener,id),()->service.unsave(listener,id),
                ()->service.report(listener,id,null),()->service.reports(listener,null,0,20),()->service.review(listener,id,null));
        calls.forEach(call->denied(()->tx(()->{call.run();return null;}),ErrorType.MARKETPLACE_FORBIDDEN));
        verifyNoInteractions(media,profiles,locations);
    }
    @Test void draftQuotaCannotBeExceededByTwoConcurrentDifferentRequests() throws Exception {
        jdbc.update("""
                insert into tbl_marketplace_listing(id,owner_user_id,seller_profile_type,seller_profile_id,client_request_id)
                select md5('draft-'||n)::uuid,:owner,'MUSICIAN',:profile,md5('request-'||n)::uuid from generate_series(1,29) n
                """,Map.of("owner",seller,"profile",sellerProfile));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Boolean> task=()->{start.await();try {tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));return true;}
                catch(SoundConnectException failure){assertThat(failure.getErrorType()).isEqualTo(ErrorType.MARKETPLACE_LIMIT_REACHED);return false;}};
            Future<Boolean> first=executor.submit(task),second=executor.submit(task);start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(repository.countByOwner(seller,Status.DRAFT)).isEqualTo(30);
        } finally {executor.shutdownNow();}
    }
    @Test void completeLifecycleKeepsPublishTimeAndRejectsStaleCrossOwnerOrIncompleteWrites() {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        assertThat(draft.isOwner()).isTrue();assertThat(draft.title()).isNull();
        denied(()->tx(()->service.detail(buyer,draft.id())),ErrorType.MARKETPLACE_NOT_FOUND);
        denied(()->tx(()->service.publish(seller,draft.id(),new Version(0L))),ErrorType.MARKETPLACE_INCOMPLETE);
        UUID photo=asset(draft.id());
        Listing edited=tx(()->service.update(seller,draft.id(),update(draft,photo,"Satılık gitar",90000L,category)));
        Listing published=tx(()->service.publish(seller,draft.id(),new Version(edited.version())));
        assertThat(published.status()).isEqualTo(Status.PUBLISHED);
        assertThat(tx(()->service.publish(seller,draft.id(),new Version(edited.version()))).version()).isEqualTo(published.version());
        denied(()->tx(()->service.update(buyer,draft.id(),update(published,photo,"Başkasının ilanı",100L,category))),ErrorType.MARKETPLACE_NOT_FOUND);
        denied(()->tx(()->service.update(seller,draft.id(),update(edited,photo,"Eski düzenleme",100L,category))),ErrorType.MARKETPLACE_VERSION_CONFLICT);
        Listing changed=tx(()->service.update(seller,draft.id(),update(published,photo,"Fiyat güncellendi",80000L,category)));
        assertThat(changed.publishedAt()).isEqualTo(published.publishedAt());
        Listing withdrawn=tx(()->service.withdraw(seller,draft.id(),new Version(changed.version())));
        denied(()->tx(()->service.detail(buyer,draft.id())),ErrorType.MARKETPLACE_NOT_FOUND);
        Listing republished=tx(()->service.publish(seller,draft.id(),new Version(withdrawn.version())));
        assertThat(republished.publishedAt()).isEqualTo(published.publishedAt());
        Listing sold=tx(()->service.sold(seller,draft.id(),new Version(republished.version())));
        denied(()->tx(()->service.publish(seller,draft.id(),new Version(sold.version()))),ErrorType.MARKETPLACE_STATE_CONFLICT);
        assertThat(tx(()->service.discovery(buyer,null,0,20)).content()).isEmpty();
        verify(media,atLeastOnce()).validateAttachments(seller,draft.id(),List.of(photo));
    }

    @Test void onlyOneConcurrentPublishCanClaimTheTwentiethPublishedSlot() throws Exception {
        for(int i=0;i<19;i++) published("Existing guitar "+i,1000L);
        Listing first=readyDraft("First competing guitar"),second=readyDraft("Second competing guitar");
        List<Attempt<Listing>> attempts=race(
                ()->tx(()->service.publish(seller,first.id(),new Version(first.version()))),
                ()->tx(()->service.publish(seller,second.id(),new Version(second.version()))));
        assertThat(attempts.stream().filter(a->a.failure()==null).count()).isEqualTo(1);
        assertThat(attempts.stream().map(Attempt::failure).filter(Objects::nonNull).toList())
                .containsExactly(ErrorType.MARKETPLACE_LIMIT_REACHED);
        assertThat(repository.countByOwner(seller,Status.PUBLISHED)).isEqualTo(20);
        assertThat(repository.countByOwner(seller,Status.DRAFT)).isEqualTo(1);
    }

    @Test void simultaneousSameVersionEditSoldAndWithdrawProduceOneCommittedMutation() throws Exception {
        Listing listing=published("Concurrent guitar",1000L);
        UUID photo=listing.photos().getFirst().assetId();
        List<Attempt<Listing>> attempts=race(
                ()->tx(()->service.update(seller,listing.id(),update(listing,photo,"Edited guitar",2000L,category))),
                ()->tx(()->service.sold(seller,listing.id(),new Version(listing.version()))),
                ()->tx(()->service.withdraw(seller,listing.id(),new Version(listing.version()))));
        assertThat(attempts.stream().filter(a->a.failure()==null).count()).isEqualTo(1);
        assertThat(attempts.stream().map(Attempt::failure).filter(Objects::nonNull).toList())
                .hasSize(2).allMatch(error->error==ErrorType.MARKETPLACE_VERSION_CONFLICT
                        || error==ErrorType.MARKETPLACE_STATE_CONFLICT);
        Listing stored=tx(()->service.detail(seller,listing.id()));
        assertThat(stored.version()).isEqualTo(listing.version()+1);
        assertThat(stored.publishedAt()).isEqualTo(listing.publishedAt());
        assertThat(stored.photos()).containsExactlyElementsOf(listing.photos());
    }

    @Test void parallelPublishRetriesReturnTheSameVersionWithoutConsumingTwoSlots() throws Exception {
        Listing listing=readyDraft("Retry publication guitar");
        List<Attempt<Listing>> attempts=race(
                ()->tx(()->service.publish(seller,listing.id(),new Version(listing.version()))),
                ()->tx(()->service.publish(seller,listing.id(),new Version(listing.version()))));
        assertThat(attempts).allMatch(a->a.failure()==null);
        assertThat(attempts.getFirst().value().version()).isEqualTo(attempts.getLast().value().version());
        assertThat(repository.countByOwner(seller,Status.PUBLISHED)).isEqualTo(1);
    }

    @Test void moderationAndSellerEditSerializeWithoutRevivingRemovedListing() throws Exception {
        Listing listing=published("Moderation race guitar",1000L);
        ReportReceipt report=tx(()->service.report(buyer,listing.id(),new Report(ReportReason.SCAM,"Test evidence",UUID.randomUUID())));
        List<Attempt<Object>> attempts=race(
                ()->tx(()->service.update(seller,listing.id(),update(listing,listing.photos().getFirst().assetId(),"Seller edit racing",2000L,category))),
                ()->tx(()->service.review(admin,report.id(),new Review(0L,ReportDecision.REMOVE_LISTING,"Verified test violation"))));
        assertThat(attempts.get(1).failure()).isNull();
        assertThat(attempts.getFirst().failure()).isIn(null,ErrorType.MARKETPLACE_STATE_CONFLICT,ErrorType.MARKETPLACE_VERSION_CONFLICT);
        assertThat(tx(()->service.detail(seller,listing.id())).status()).isEqualTo(Status.MODERATED);
        assertThat(repository.report(report.id(),false).orElseThrow().evidence().path("title").asText()).isEqualTo("Moderation race guitar");
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit where report_id=:id",Map.of("id",report.id()),Long.class)).isEqualTo(1);
    }

    @Test void concurrentConflictingModeratorDecisionsCreateOnlyOneAudit() throws Exception {
        Listing listing=published("Review concurrency guitar",1000L);
        ReportReceipt report=tx(()->service.report(buyer,listing.id(),new Report(ReportReason.SPAM,null,UUID.randomUUID())));
        UUID otherAdmin=account("second-moderator","ADMIN");
        List<Attempt<AdminReport>> attempts=race(
                ()->tx(()->service.review(admin,report.id(),new Review(0L,ReportDecision.REMOVE_LISTING,"First reviewed decision"))),
                ()->tx(()->service.review(otherAdmin,report.id(),new Review(0L,ReportDecision.DISMISS,"Second reviewed decision"))));
        assertThat(attempts.stream().filter(a->a.failure()==null).count()).isEqualTo(1);
        assertThat(attempts.stream().map(Attempt::failure).filter(Objects::nonNull).toList())
                .containsExactly(ErrorType.MARKETPLACE_STATE_CONFLICT);
        assertThat(repository.report(report.id(),false).orElseThrow().version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit where report_id=:id",Map.of("id",report.id()),Long.class)).isEqualTo(1);
    }
    @Test void invalidRootInactiveCategoryLocationAndPhotoFieldsAreRejected() {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));UUID photo=asset(draft.id());
        denied(()->tx(()->service.update(seller,draft.id(),update(draft,photo,"Root category",100L,root))),ErrorType.MARKETPLACE_INVALID);
        jdbc.update("update tbl_marketplace_category set active=false where id=:id",Map.of("id",category));
        denied(()->tx(()->service.update(seller,draft.id(),update(draft,photo,"Inactive category",100L,category))),ErrorType.MARKETPLACE_INVALID);
        denied(()->tx(()->service.discovery(seller,new Filter(null,null,UUID.randomUUID(),district,null,null,null,null),0,20)),ErrorType.MARKETPLACE_INVALID);
        denied(()->tx(()->service.discovery(seller,new Filter(null,null,null,null,null,500L,100L,null),0,20)),ErrorType.MARKETPLACE_INVALID);
        denied(()->tx(()->service.discovery(seller,null,-1,100)),ErrorType.MARKETPLACE_INVALID);
    }
    @Test void filtersSortPaginationOwnershipAndSavesUseCurrentVisibility() {
        Listing cheap=published("Cheap 50%_ Guitar",100L),costly=published("Expensive Guitar",200L);
        Filter sorted=new Filter(null,root,city,district,Condition.USED,50L,250L,SortOrder.PRICE_ASC);
        var first=tx(()->service.discovery(seller,sorted,0,1));
        assertThat(first.totalElements()).isEqualTo(2);assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content().getFirst().id()).isEqualTo(cheap.id());assertThat(first.content().getFirst().isOwner()).isTrue();
        assertThat(tx(()->service.discovery(buyer,sorted,1,1)).content().getFirst().id()).isEqualTo(costly.id());
        assertThat(tx(()->service.mine(seller,null,0,20)).content()).allMatch(Listing::isOwner);
        Filter literal=new Filter("50%_",null,null,null,null,null,null,null);
        assertThat(tx(()->service.discovery(buyer,literal,0,20)).content()).extracting(Listing::id).containsExactly(cheap.id());
        tx(()->{service.save(buyer,cheap.id());service.save(buyer,cheap.id());return null;});
        var saved=tx(()->service.saved(buyer,0,20)).content();
        assertThat(saved).hasSize(1);assertThat(saved.getFirst().saved()).isTrue();assertThat(saved.getFirst().isOwner()).isFalse();
        jdbc.update("delete from tbl_musician_profile where id=:id",Map.of("id",sellerProfile));
        jdbc.update("insert into tbl_musician_profile(id,user_id,name) values(:id,:user,'Replacement')",Map.of("id",UUID.randomUUID(),"user",seller));
        assertThat(tx(()->service.discovery(buyer,null,0,20)).content()).isEmpty();
        assertThat(tx(()->service.saved(buyer,0,20)).content()).isEmpty();
        denied(()->tx(()->service.detail(buyer,cheap.id())),ErrorType.MARKETPLACE_NOT_FOUND);
    }
    @ParameterizedTest @ValueSource(strings={"title","brand","model"})
    void turkishSearchUsesDatabaseCaseFoldingForBothOperands(String field) {
        Map<UUID,String> values=new LinkedHashMap<>();
        for(String text:List.of("İkinci","ikinci","Ikinci","ıkıncı")) {
            Listing listing=published("Search control guitar",1000L);
            jdbc.update("update tbl_marketplace_listing set "+field+"=:text where id=:id",Map.of("text",text,"id",listing.id()));
            values.put(listing.id(),text);
        }
        for(String query:values.values()) {
            // The database locale defines equivalence; Java's Locale.ROOT must not alter it.
            List<UUID> expected=values.entrySet().stream().filter(entry->Boolean.TRUE.equals(jdbc.queryForObject(
                    "select lower(:stored)=lower(:query)",Map.of("stored",entry.getValue(),"query",query),Boolean.class)))
                    .map(Map.Entry::getKey).toList();
            Filter filter=new Filter(query,null,null,null,null,null,null,null);
            var page=tx(()->service.discovery(buyer,filter,0,20));
            assertThat(page.content()).as("%s search for %s",field,query).extracting(Listing::id)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(page.totalElements()).isEqualTo(expected.size());
        }
    }
    @Test void turkishSearchStillTreatsPercentUnderscoreAndBackslashAsLiteralText() {
        Listing literal=published("İkinci 50%_\\ Guitar",1000L);
        published("İkinci 50abcX\\ Guitar",1000L);
        published("İkinci 50%_ Guitar",1000L);
        Filter filter=new Filter("  İkinci 50%_\\  ",null,null,null,null,null,null,null);
        assertThat(tx(()->service.discovery(buyer,filter,0,20)).content()).extracting(Listing::id)
                .containsExactly(literal.id());
    }
    @ParameterizedTest @CsvSource({"3,10","4,10","5,9"})
    void publicationRejectsTooFewUnicodeCodePointsBeforeDatabaseWrite(int titleLength,int descriptionLength) {
        String emoji="\uD83C\uDFB8";
        Listing draft=readyDraft(emoji.repeat(titleLength));
        Update content=new Update(draft.version(),draft.title(),emoji.repeat(descriptionLength),category,
                null,null,Condition.USED,1000L,district,draft.photos().stream().map(Photo::assetId).toList(),false,Delivery.PICKUP);
        Listing edited=tx(()->service.update(seller,draft.id(),content));
        denied(()->tx(()->service.publish(seller,edited.id(),new Version(edited.version()))),ErrorType.MARKETPLACE_INCOMPLETE);
        Listing unchanged=tx(()->service.detail(seller,edited.id()));
        assertThat(unchanged.status()).isEqualTo(Status.DRAFT);
        assertThat(unchanged.version()).isEqualTo(edited.version());
    }
    @Test void unicodeListingLimitsMatchDatabaseAtMinimumAndMaximumBoundaries() {
        String emoji="\uD83C\uDFB8";
        Listing draft=readyDraft(emoji.repeat(5));
        List<UUID> photos=draft.photos().stream().map(Photo::assetId).toList();
        Listing minimum=tx(()->service.update(seller,draft.id(),new Update(draft.version(),emoji.repeat(5),emoji.repeat(10),
                category,null,null,Condition.USED,1000L,district,photos,false,Delivery.PICKUP)));
        Listing published=tx(()->service.publish(seller,minimum.id(),new Version(minimum.version())));
        Listing maximum=tx(()->service.update(seller,published.id(),new Update(published.version(),emoji.repeat(120),emoji.repeat(4000),
                category,emoji.repeat(80),emoji.repeat(100),Condition.USED,1000L,district,photos,false,Delivery.PICKUP)));
        assertThat(maximum.title()).isEqualTo(emoji.repeat(120));
        assertThat(maximum.description()).isEqualTo(emoji.repeat(4000));
        assertThat(maximum.brand()).isEqualTo(emoji.repeat(80));
        assertThat(maximum.model()).isEqualTo(emoji.repeat(100));
        for(Update oversized:List.of(
                new Update(maximum.version(),emoji.repeat(121),maximum.description(),category,maximum.brand(),maximum.model(),Condition.USED,1000L,district,photos,false,Delivery.PICKUP),
                new Update(maximum.version(),maximum.title(),emoji.repeat(4001),category,maximum.brand(),maximum.model(),Condition.USED,1000L,district,photos,false,Delivery.PICKUP),
                new Update(maximum.version(),maximum.title(),maximum.description(),category,emoji.repeat(81),maximum.model(),Condition.USED,1000L,district,photos,false,Delivery.PICKUP),
                new Update(maximum.version(),maximum.title(),maximum.description(),category,maximum.brand(),emoji.repeat(101),Condition.USED,1000L,district,photos,false,Delivery.PICKUP))) {
            denied(()->tx(()->service.update(seller,maximum.id(),oversized)),ErrorType.MARKETPLACE_INVALID);
        }
        denied(()->tx(()->service.update(seller,maximum.id(),new Update(maximum.version(),emoji.repeat(4),maximum.description(),
                category,null,null,Condition.USED,1000L,district,photos,false,Delivery.PICKUP))),ErrorType.MARKETPLACE_INCOMPLETE);
        assertThat(tx(()->service.detail(seller,maximum.id())).version()).isEqualTo(maximum.version());
        assertThat(tx(()->service.discovery(buyer,new Filter(emoji.repeat(100),null,null,null,null,null,null,null),0,20)).content())
                .extracting(Listing::id).containsExactly(maximum.id());
        denied(()->tx(()->service.discovery(buyer,new Filter(emoji.repeat(101),null,null,null,null,null,null,null),0,20)),ErrorType.MARKETPLACE_INVALID);
    }
    @Test void reportAndReviewUnicodeLimitsRejectShortNotesBeforeDatabaseWrite() {
        String emoji="\uD83C\uDFB8";
        Listing listing=published("Reportable Unicode guitar",1000L);
        denied(()->tx(()->service.report(buyer,listing.id(),new Report(ReportReason.OTHER,emoji.repeat(3),UUID.randomUUID()))),ErrorType.MARKETPLACE_INVALID);
        denied(()->tx(()->service.report(buyer,listing.id(),new Report(ReportReason.OTHER,emoji.repeat(1001),UUID.randomUUID()))),ErrorType.MARKETPLACE_INVALID);
        ReportReceipt receipt=tx(()->service.report(buyer,listing.id(),new Report(ReportReason.OTHER,emoji.repeat(1000),UUID.randomUUID())));
        for(String invalid:List.of("   "," "+emoji.repeat(4)+" ",emoji.repeat(1001))) {
            denied(()->tx(()->service.review(admin,receipt.id(),new Review(0L,ReportDecision.DISMISS,invalid))),ErrorType.MARKETPLACE_INVALID);
        }
        assertThat(repository.report(receipt.id(),false).orElseThrow().version()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit",Map.of(),Long.class)).isZero();
        AdminReport resolved=tx(()->service.review(admin,receipt.id(),new Review(0L,ReportDecision.DISMISS,emoji.repeat(1000))));
        assertThat(resolved.description()).isEqualTo(emoji.repeat(1000));
        assertThat(resolved.resolutionNote()).isEqualTo(emoji.repeat(1000));
        assertThat(resolved.status()).isEqualTo(ReportStatus.DISMISSED);
    }
    @Test void reportRetrySnapshotsPhotosAndModerationIsAuditedWithFreshPermission() {
        Listing listing=published("Reportable guitar",12345L);
        UUID request=UUID.randomUUID();Report report=new Report(ReportReason.SCAM,"Yanıltıcı ilan",request);
        var receipt=tx(()->service.report(buyer,listing.id(),report));
        assertThat(tx(()->service.report(buyer,listing.id(),report)).id()).isEqualTo(receipt.id());
        denied(()->tx(()->service.report(buyer,listing.id(),new Report(ReportReason.SPAM,null,request))),ErrorType.MARKETPLACE_IDEMPOTENCY_CONFLICT);
        denied(()->tx(()->service.report(buyer,listing.id(),new Report(ReportReason.SPAM,null,UUID.randomUUID()))),ErrorType.MARKETPLACE_REPORT_DUPLICATE);
        UUID replacement=asset(listing.id());
        tx(()->service.update(seller,listing.id(),update(listing,replacement,"Changed after report",4321L,category)));
        var evidence=tx(()->service.reports(admin,ReportStatus.OPEN,0,20)).content().getFirst();
        assertThat(evidence.evidence().get("title").asText()).isEqualTo("Reportable guitar");
        assertThat(jdbc.queryForList("select media_asset_id from tbl_marketplace_report_photo where report_id=:id",Map.of("id",receipt.id()),UUID.class))
                .containsExactly(listing.photos().getFirst().assetId());
        assertThatThrownBy(()->jdbc.update("delete from tbl_media_asset where id=:id",Map.of("id",listing.photos().getFirst().assetId())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        denied(()->tx(()->service.review(buyer,receipt.id(),new Review(0L,ReportDecision.REMOVE_LISTING,"Confirmed misleading"))),ErrorType.MARKETPLACE_FORBIDDEN);
        var resolved=tx(()->service.review(admin,receipt.id(),new Review(0L,ReportDecision.REMOVE_LISTING,"Confirmed misleading")));
        assertThat(resolved.status()).isEqualTo(ReportStatus.ACTIONED);
        assertThat(tx(()->service.detail(seller,listing.id())).status()).isEqualTo(Status.MODERATED);
        denied(()->tx(()->service.detail(buyer,listing.id())),ErrorType.MARKETPLACE_NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit",Map.of(),Long.class)).isEqualTo(1);
        tx(()->service.review(admin,receipt.id(),new Review(0L,ReportDecision.REMOVE_LISTING,"Confirmed misleading")));
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_audit",Map.of(),Long.class)).isEqualTo(1);
        jdbc.update("delete from tbl_marketplace_listing_photo where listing_id=:id",Map.of("id",listing.id()));
        jdbc.update("delete from tbl_marketplace_listing where id=:id",Map.of("id",listing.id()));
        assertThat(repository.report(receipt.id(),false).orElseThrow().listingId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_report_photo",Map.of(),Long.class)).isEqualTo(1);
        role(admin,"LISTENER");denied(()->tx(()->service.reports(admin,null,0,20)),ErrorType.MARKETPLACE_FORBIDDEN);
    }
    @Test void draftDeletionDetachesBeforeDurableCleanupAndPublishedDeletionIsRejected() {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));UUID photo=asset(draft.id());
        Listing updated=tx(()->service.update(seller,draft.id(),update(draft,photo,"Draft product",100L,category)));
        doAnswer(call->{
            assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_listing_photo where listing_id=:id",Map.of("id",draft.id()),Long.class)).isZero();
            assertThat(repository.find(draft.id(),seller,false)).isPresent();return null;
        }).when(media).deleteListingMedia(draft.id());
        tx(()->{service.deleteDraft(seller,draft.id(),updated.version());return null;});
        verify(media).deleteListingMedia(draft.id());assertThat(repository.find(draft.id(),seller,false)).isEmpty();
        Listing published=published("Published item",100L);
        denied(()->tx(()->{service.deleteDraft(seller,published.id(),published.version());return null;}),ErrorType.MARKETPLACE_STATE_CONFLICT);
    }
    @Test void recreatedProfileCannotPublishOldDraftButOwnerCanDeleteItToReleaseQuota() {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        jdbc.update("delete from tbl_musician_profile where id=:id",Map.of("id",sellerProfile));
        jdbc.update("insert into tbl_musician_profile(id,user_id,name) values(:id,:user,'Replacement')",Map.of("id",UUID.randomUUID(),"user",seller));
        denied(()->tx(()->service.publish(seller,draft.id(),new Version(0L))),ErrorType.MARKETPLACE_FORBIDDEN);
        tx(()->{service.deleteDraft(seller,draft.id(),draft.version());return null;});
        assertThat(repository.find(draft.id(),seller,false)).isEmpty();
        verify(media).deleteListingMedia(draft.id());
    }

    @Test void sellerAvatarsFollowTheOriginalMusicianStudioAndVenueProfile() {
        for(String type:List.of("MUSICIAN","STUDIO","VENUE")) {
            UUID user=account("avatar-"+type.toLowerCase(Locale.ROOT),type);
            String table=switch(type){case "MUSICIAN"->"tbl_musician_profile";case "STUDIO"->"tbl_studio_profile";default->"tbl_venues";};
            UUID profile=jdbc.queryForObject("select id from "+table+" where "+(type.equals("VENUE")?"owner_id":"user_id")+"=:user",
                    Map.of("user",user),UUID.class);
            UUID avatar=UUID.randomUUID();
            if(type.equals("VENUE")) {
                jdbc.update("insert into tbl_venue_profile(id,venue_id,profile_picture_media_id) values(:id,:profile,:avatar)",
                        Map.of("id",UUID.randomUUID(),"profile",profile,"avatar",avatar));
            } else {
                jdbc.update("update "+table+" set profile_picture_media_id=:avatar where id=:profile",Map.of("avatar",avatar,"profile",profile));
            }
            when(profiles.resolveOwnedProfiles(eq(user),anySet())).thenReturn(List.of(
                    new OwnedProfileTarget(ProfileType.valueOf(type),profile,"Seller",null)));
            String url="https://cdn.example/avatar-"+type.toLowerCase(Locale.ROOT)+".webp";
            when(mediaAssets.getDisplayUrlMap(List.of(avatar))).thenReturn(Map.of(avatar,url));
            Listing draft=tx(()->service.createDraft(user,new Draft(UUID.randomUUID())));
            Seller result=tx(()->service.detail(user,draft.id())).seller();
            assertThat(result.avatarUrl()).isEqualTo(url);
            assertThat(result.profileType()).isEqualTo(type);
            assertThat(result.profileId()).isEqualTo(profile);
            assertThat(result.userId()).isEqualTo(user);
            assertThat(result.username()).isEqualTo("avatar-"+type.toLowerCase(Locale.ROOT));
        }
    }

    @Test void listAvatarsAreBatchedAndRefreshWhenTheSellerChangesTheirPhoto() {
        Listing first=published("First avatar listing",100L);
        published("Second avatar listing",200L);
        UUID original=UUID.randomUUID(),replacement=UUID.randomUUID();
        jdbc.update("update tbl_musician_profile set profile_picture_media_id=:avatar where id=:id",Map.of("avatar",original,"id",sellerProfile));
        when(mediaAssets.getDisplayUrlMap(List.of(original))).thenReturn(Map.of(original,"https://cdn.example/original.webp"));
        clearInvocations(mediaAssets);
        assertThat(tx(()->service.discovery(buyer,null,0,20)).content())
                .hasSize(2).allSatisfy(listing->assertThat(listing.seller().avatarUrl()).isEqualTo("https://cdn.example/original.webp"));
        verify(mediaAssets,times(1)).getDisplayUrlMap(List.of(original));
        verifyNoMoreInteractions(mediaAssets);

        jdbc.update("update tbl_musician_profile set profile_picture_media_id=:avatar where id=:id",Map.of("avatar",replacement,"id",sellerProfile));
        when(mediaAssets.getDisplayUrlMap(List.of(replacement))).thenReturn(Map.of(replacement,"https://cdn.example/replacement.webp"));
        assertThat(tx(()->service.detail(buyer,first.id())).seller().avatarUrl()).isEqualTo("https://cdn.example/replacement.webp");
    }

    @Test void missingOrNotDisplayableAvatarsStayAbsentWithoutFallingBackToListingPhotos() {
        Listing listing=published("No public avatar",100L);
        clearInvocations(mediaAssets);
        assertThat(tx(()->service.detail(buyer,listing.id())).seller().avatarUrl()).isNull();
        verifyNoInteractions(mediaAssets);

        UUID hiddenAvatar=UUID.randomUUID();
        jdbc.update("update tbl_musician_profile set profile_picture_media_id=:avatar where id=:id",Map.of("avatar",hiddenAvatar,"id",sellerProfile));
        when(mediaAssets.getDisplayUrlMap(List.of(hiddenAvatar))).thenReturn(Map.of());
        Listing result=tx(()->service.detail(buyer,listing.id()));
        assertThat(result.seller().avatarUrl()).isNull();
        assertThat(result.photos()).containsExactlyElementsOf(listing.photos());
        verify(mediaAssets).getDisplayUrlMap(List.of(hiddenAvatar));
    }

    private Listing published(String title,long price) {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        Listing update=tx(()->service.update(seller,draft.id(),update(draft,asset(draft.id()),title,price,category)));
        return tx(()->service.publish(seller,draft.id(),new Version(update.version())));
    }
    private Listing readyDraft(String title) {
        Listing draft=tx(()->service.createDraft(seller,new Draft(UUID.randomUUID())));
        return tx(()->service.update(seller,draft.id(),update(draft,asset(draft.id()),title,1000L,category)));
    }
    private record Attempt<T>(T value,ErrorType failure) {}
    @SafeVarargs private final <T> List<Attempt<T>> race(Supplier<T>... work) throws Exception {
        CyclicBarrier start=new CyclicBarrier(work.length);
        try(ExecutorService executor=Executors.newFixedThreadPool(work.length)) {
            List<Future<Attempt<T>>> futures=new ArrayList<>();
            for(Supplier<T> operation:work) futures.add(executor.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                try {return new Attempt<>(operation.get(),null);}
                catch(SoundConnectException failure){return new Attempt<T>(null,failure.getErrorType());}
            }));
            List<Attempt<T>> results=new ArrayList<>();
            for(Future<Attempt<T>> future:futures) results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        }
    }
    private Update update(Listing listing,UUID asset,String title,long price,UUID type) {
        return new Update(listing.version(),title,"Ürün ayrıntıları ve kutu içeriği.",type,"Fender","Stratocaster",Condition.USED,price,district,List.of(asset),true,Delivery.BOTH);
    }
    private UUID asset(UUID listing) {
        UUID id=UUID.randomUUID();jdbc.update("insert into tbl_media_asset(id,owner_type,owner_id,visibility,kind,content_audience,status,created_at) values(:id,'MARKETPLACE',:listing,'PRIVATE','IMAGE','BACKSTAGE','READY',now())",Map.of("id",id,"listing",listing));return id;
    }
    private UUID account(String name,String type) {
        UUID id=UUID.randomUUID();jdbc.update("insert into tbl_user(id,user_name) values(:id,:name)",Map.of("id",id,"name",name));role(id,type);
        String table=switch(type){case "MUSICIAN"->"tbl_musician_profile";case "STUDIO"->"tbl_studio_profile";case "VENUE"->"tbl_venues";case "LISTENER"->"\"tbl_listener-profile\"";default->null;};
        if(table!=null) jdbc.update("insert into "+table+"(id,"+(type.equals("VENUE")?"owner_id":"user_id")+") values(:id,:user)",Map.of("id",UUID.randomUUID(),"user",id));
        return id;
    }
    private void role(UUID user,String type) {
        jdbc.update("insert into tbl_role values(:id,:role) on conflict(name) do nothing",Map.of("id",UUID.randomUUID(),"role","ROLE_"+type));
        jdbc.update("insert into user_roles select :user,id from tbl_role where name=:role on conflict do nothing",Map.of("user",user,"role","ROLE_"+type));
    }
    private UUID category(String code){return jdbc.queryForObject("select id from tbl_marketplace_category where code=:code",Map.of("code",code),UUID.class);}
    private <T>T tx(Supplier<T> work){return transaction.execute(status->work.get());}
    private void migrate()throws Exception {
        try(Connection c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Statement s=c.createStatement()) {
            s.execute(Files.readString(Path.of("scripts/db/2026-09-20-marketplace-domain.sql")));
            s.execute(Files.readString(Path.of("scripts/db/2026-09-20-marketplace-media.sql")));
            s.execute(Files.readString(Path.of("scripts/db/2026-09-20-protected-image-thumbnails.sql")));
        }
    }
    private static void denied(Runnable action,ErrorType error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,failure->assertThat(failure.getErrorType()).isEqualTo(error));
    }
}
