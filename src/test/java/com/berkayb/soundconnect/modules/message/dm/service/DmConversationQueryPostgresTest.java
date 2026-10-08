package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exact production SQL, only in an explicit disposable PostgreSQL test schema. */
@EnabledIfSystemProperty(named = "push.test.jdbc-url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
class DmConversationQueryPostgresTest {
    JdbcTemplate admin, jdbc;
    TransactionTemplate tx;
    DmConversationQueryService service;
    GhostListenerIdentityBatchResolver ghosts;
    MediaAssetService media;
    CapturingJdbc named;
    String schema;
    UUID actor;
    static final LocalDateTime AT = LocalDateTime.of(2026, 9, 23, 12, 0);

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("push.test.jdbc-url"), username = System.getProperty("push.test.username", "postgres");
        String password = System.getProperty("push.test.password", "");
        var base = new DriverManagerDataSource(url, username, password);
        try (var connection = base.getConnection()) { assertThat(connection.getCatalog()).containsIgnoringCase("test"); }
        admin = new JdbcTemplate(base);
        schema = "dm_page_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
        jdbc = new JdbcTemplate(source);
        assertThat(com.berkayb.soundconnect.modules.user.entity.User.class.getDeclaredField("username")
                .getAnnotation(jakarta.persistence.Column.class).name()).isEqualTo("user_name");
        jdbc.execute("create table tbl_user(id uuid primary key,status text,email_verified boolean,erased_at timestamp,user_name text,profile_picture text)");
        jdbc.execute("create table tbl_dm_conversation(id uuid primary key,user_a_id uuid,user_b_id uuid,last_message_at timestamp,check(user_a_id<user_b_id),unique(user_a_id,user_b_id))");
        jdbc.execute("create table tbl_dm_message(id uuid primary key,conversation_id uuid,sender_id uuid,recipient_id uuid,content text,message_type text,created_at timestamp,read_at timestamp,deleted_at timestamp)");
        for (String name : List.of("musician", "organizer", "producer", "studio")) {
            jdbc.execute("create table tbl_" + name + "_profile(user_id uuid primary key,name text,profile_picture_media_id uuid)");
        }
        jdbc.execute("create table \"tbl_listener-profile\"(user_id uuid primary key,name text,profile_picture_media_id uuid,visibility_mode text,visibility_choice_completed boolean)");
        jdbc.execute("create table tbl_venues(id uuid primary key,owner_id uuid,name text,created_at timestamp)");
        jdbc.execute("create table tbl_venue_profile(venue_id uuid primary key,profile_picture_media_id uuid)");
        String migration = Files.readString(Path.of("scripts/db/2026-09-23-dm-conversation-pagination.sql"));
        jdbc.execute(migration); jdbc.execute(migration);
        named = new CapturingJdbc(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        ghosts = mock(GhostListenerIdentityBatchResolver.class); media = mock(MediaAssetService.class);
        when(ghosts.resolve(anyCollection())).thenReturn(Map.of());
        when(media.getDisplayUrlMap(anyList())).thenReturn(Map.of());
        service = new DmConversationQueryService(named, new AccountDeliveryFence(named), ghosts, media);
        actor = UUID.fromString("80000000-0000-0000-0000-000000000000"); user(actor, "actor");
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null) admin.execute("drop schema " + schema + " cascade");
    }

    @Test void allPagesHaveDeterministicTiesAndNullTailWithoutDuplicatesOrForeignConversations() {
        for (int i=1; i<=9; i++) conversation(id(i), peer(i), i<=5 ? AT : null);
        UUID foreign = UUID.randomUUID();
        jdbc.update("insert into tbl_dm_conversation values (?,?,?,?)", foreign, id(300), id(301), AT.plusDays(10));
        List<UUID> seen = new ArrayList<>(); String cursor = null; int pages = 0;
        do {
            var page = page(2, cursor); pages++;
            assertThat(page.content()).hasSizeLessThanOrEqualTo(2);
            seen.addAll(page.content().stream().map(DMConversationPreviewResponseDto::conversationId).toList());
            assertThat(page.hasNext()).isEqualTo(page.nextCursor() != null);
            cursor = page.nextCursor();
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(5);
        assertThat(seen).containsExactly(id(5),id(4),id(3),id(2),id(1),id(9),id(8),id(7),id(6));
        assertThat(seen).doesNotHaveDuplicates().doesNotContain(foreign);
        assertThat(jdbc.queryForObject("select count(*) from soundconnect_schema_migrations", Integer.class)).isEqualTo(1);
    }

    @Test void cursorUsesConversationOrderEvenWhenMessageTimestampDiffersAndNewHeadArrives() {
        conversation(id(1), peer(1), AT); conversation(id(2), peer(2), AT.plusSeconds(1));
        message(id(100), id(2), peer(2), actor, AT.minusDays(1), false);
        var first = page(1,null);
        assertThat(first.content().getFirst().lastMessageAt()).isEqualTo(AT.minusDays(1));
        assertThat(DmConversationCursor.decode(actor, first.nextCursor()).lastMessageAt()).isEqualTo(AT.plusSeconds(1));
        conversation(id(3),peer(3),AT.plusDays(1));
        assertThat(page(1,first.nextCursor()).content()).extracting(DMConversationPreviewResponseDto::conversationId).containsExactly(id(1));
        assertThat(page(1,null).content()).extracting(DMConversationPreviewResponseDto::conversationId).containsExactly(id(3));
    }

    @Test void missingAndForeignPreviewBothFailClosedAndInactiveCallerCannotRead() {
        conversation(id(1), peer(1), AT);
        assertThat(preview(id(1)).otherUserId()).isEqualTo(peer(1));
        assertError(() -> preview(id(2)), ErrorType.CONVERSATION_NOT_FOUND);
        jdbc.update("insert into tbl_dm_conversation values (?,?,?,?)", id(2), id(300), id(301), AT);
        assertError(() -> preview(id(2)), ErrorType.CONVERSATION_NOT_FOUND);
        jdbc.update("update tbl_user set status='SUSPENDED' where id=?", actor);
        assertError(() -> page(30,null), ErrorType.FORBIDDEN_ACCESS);
        assertError(() -> preview(id(1)), ErrorType.FORBIDDEN_ACCESS);
    }

    @Test void invalidBoundsAndCrossOwnerCursorAreRejectedBeforeQuery() {
        assertError(() -> page(0,null), ErrorType.BAD_REQUEST);
        assertError(() -> page(101,null), ErrorType.BAD_REQUEST);
        assertError(() -> page(30,""), ErrorType.BAD_REQUEST);
        assertError(() -> page(30,DmConversationCursor.encode(peer(1),new DmConversationCursor.Cursor(AT,id(1)))), ErrorType.BAD_REQUEST);
        assertThat(page(100,null).content()).isEmpty();
        verifyNoInteractions(ghosts,media);
    }

    @Test void latestVisibleMessageUsesUuidTieBreakAndComputesReadForTheReader() {
        conversation(id(1),peer(1),AT);
        message(id(100),id(1),peer(1),actor,AT,false);
        message(id(101),id(1),peer(1),actor,AT,false);
        message(id(102),id(1),peer(1),actor,AT.plusDays(1),true);
        assertThat(preview(id(1)).lastMessageContent()).isEqualTo("fixture-"+id(101));
        assertThat(preview(id(1)).lastMessageRead()).isFalse();
        jdbc.update("update tbl_dm_message set read_at=? where id=?",AT,id(101));
        assertThat(preview(id(1)).lastMessageRead()).isTrue();
        message(id(103),id(1),actor,peer(1),AT.plusSeconds(1),false);
        assertThat(preview(id(1)).lastMessageRead()).isTrue();
        jdbc.update("update tbl_dm_message set deleted_at=?",AT);
        var empty = preview(id(1));
        assertThat(empty.lastMessageContent()).isNull(); assertThat(empty.lastMessageRead()).isNull();
    }

    @Test void allPersonalProfileAvatarsAreBatchedAndVenueIdentityIsDeterministic() {
        List<UUID> assets = new ArrayList<>();
        String[] kinds = {"musician","listener","organizer","producer","studio","venue"};
        for(int i=1;i<=6;i++) {
            conversation(id(i),peer(i),AT); UUID asset=id(100+i); assets.add(asset);
            if (i==2) jdbc.update("insert into \"tbl_listener-profile\" values (?,?,?,'STANDARD',true)",peer(i),"public-listener",asset);
            else if(i==6) {
                jdbc.update("insert into tbl_venues values (?,?,?,?),(?,?,?,?)",id(200),peer(i),"First venue",AT,id(201),peer(i),"Later venue",AT.plusDays(1));
                jdbc.update("insert into tbl_venue_profile values (?,?)",id(200),asset);
            } else jdbc.update("insert into tbl_"+kinds[i-1]+"_profile values (?,?,?)",peer(i),"profile-name",asset);
        }
        Map<UUID,String> urls = new HashMap<>(); for(var asset:assets) urls.put(asset,"https://fixture.invalid/"+asset);
        when(media.getDisplayUrlMap(anyList())).thenReturn(urls);
        var rows = page(30,null).content();
        assertThat(rows).hasSize(6);
        assertThat(rows.getFirst().otherUsername()).isEqualTo("First venue");
        for(var row:rows) assertThat(row.otherUserProfilePicture()).startsWith("https://fixture.invalid/");
        verify(media,times(1)).getDisplayUrlMap(argThat(ids -> ids.size()==6 && ids.containsAll(assets)));
        verify(ghosts,times(1)).resolve(argThat(ids -> ids.size()==6));
        verifyNoMoreInteractions(media,ghosts);
    }

    @Test void ghostPendingAndErasedNeverExposeAlternateIdentityOrAvatar() {
        for(int i=1;i<=3;i++) {
            conversation(id(i),peer(i),AT);
            jdbc.update("insert into tbl_musician_profile values (?,?,?)",peer(i),"private-professional",id(100+i));
            jdbc.update("update tbl_user set profile_picture='private-legacy' where id=?",peer(i));
        }
        jdbc.update("insert into \"tbl_listener-profile\" values (?,?,?,'GHOST',true),(?,?,?,'STANDARD',false)",peer(1),"hidden",id(501),peer(2),"hidden",id(502));
        jdbc.update("update tbl_user set erased_at=? where id=?",AT,peer(3));
        when(ghosts.resolve(anyCollection())).thenReturn(Map.of(peer(1),new GhostListenerIdentity(peer(1),"safe-ghost","https://fixture.invalid/ghost",ListenerVisibilityMode.GHOST)));
        var rows = page(30,null).content();
        assertThat(rows.get(0).otherUsername()).isEqualTo("Silinmiş hesap");
        assertThat(rows.get(0).otherUserDeleted()).isTrue(); assertThat(rows.get(0).otherUserProfilePicture()).isNull();
        assertThat(rows.get(1).otherUsername()).isEqualTo("Kullanici");
        assertThat(rows.get(1).otherUserVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
        assertThat(rows.get(1).otherUserProfilePicture()).isNull();
        assertThat(rows.get(2).otherUsername()).isEqualTo("safe-ghost");
        assertThat(rows.get(2).otherUserProfilePicture()).isEqualTo("https://fixture.invalid/ghost");
        verifyNoInteractions(media);
    }

    @Test void erasureCompletingBetweenProjectionAndVisibilityResolutionMasksOldSnapshot() {
        conversation(id(1),peer(1),AT);
        when(ghosts.resolve(anyCollection())).thenAnswer(invocation -> {
            jdbc.update("update tbl_user set erased_at=? where id=?",AT,peer(1));
            return Map.of();
        });
        assertThat(preview(id(1)).otherUserDeleted()).isTrue();
        assertThat(preview(id(1)).otherUsername()).isEqualTo("Silinmiş hesap");
        verifyNoInteractions(media);
    }

    @Test void deepPageUsesTupleIndexRangeAndOnlyEnrichesTheRequestedWindow() throws Exception {
        // No application users: 10,000 synthetic conversations in this schema.
        jdbc.update("""
                insert into tbl_user(id,status,email_verified,user_name)
                select (case when n%2=0 then '90000000' else '10000000' end
                    ||'-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,'ACTIVE',true,'fixture-'||n
                from generate_series(1,10000) n
                """);
        jdbc.update("""
                insert into tbl_dm_conversation
                select ('00000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,
                    least(?,u.id),greatest(?,u.id),timestamp '2026-09-23 12:00:00' - n*interval '1 second'
                from generate_series(1,10000) n join tbl_user u on u.user_name='fixture-'||n
                """, actor,actor);
        jdbc.execute("analyze tbl_dm_conversation"); jdbc.execute("analyze tbl_user");
        String cursor=DmConversationCursor.encode(actor,new DmConversationCursor.Cursor(AT.minusSeconds(9000),id(9000)));
        var page=page(30,cursor);
        assertThat(page.content()).hasSize(30);
        assertThat(page.content().getFirst().conversationId()).isEqualTo(id(9001));
        assertThat(page.content().getLast().conversationId()).isEqualTo(id(9030));
        verify(ghosts).resolve(argThat(ids -> ids.size()==30));
        String plan=named.queryForObject("explain (analyze,format json) "+named.pageSql,named.pageParams,String.class);
        assertThat(plan).contains("idx_dm_conversation_user_a_page", "idx_dm_conversation_user_b_page", "ROW(last_message_at, id)");
        var indexConditions = new com.fasterxml.jackson.databind.ObjectMapper().readTree(plan)
                .findValues("Index Cond").stream().map(com.fasterxml.jackson.databind.JsonNode::asText).toList();
        assertThat(indexConditions).anySatisfy(condition -> assertThat(condition).contains("user_a_id", "ROW(last_message_at, id)"));
        assertThat(indexConditions).anySatisfy(condition -> assertThat(condition).contains("user_b_id", "ROW(last_message_at, id)"));
        // If the old OR/filter implementation returns, PostgreSQL must discard
        // thousands of preceding entries. The range-seek plan has no such filter.
        assertThat(plan).doesNotContain("\"Filter\": \"((last_message_at <");
    }

    private DMConversationPageResponseDto page(int size,String cursor) { return tx.execute(status -> service.page(actor,size,cursor)); }
    private DMConversationPreviewResponseDto preview(UUID id) { return tx.execute(status -> service.preview(actor,id)); }
    private void user(UUID id,String username) { jdbc.update("insert into tbl_user values (?,'ACTIVE',true,null,?,null) on conflict do nothing",id,username); }
    private void conversation(UUID id,UUID peer,LocalDateTime at) {
        user(peer,"peer-"+peer);
        // PostgreSQL UUID ordering is unsigned; Java UUID.compareTo is signed.
        var ordered = new ArrayList<>(List.of(actor,peer)); ordered.sort(Comparator.comparing(UUID::toString));
        jdbc.update("insert into tbl_dm_conversation values (?,?,?,?)",id,ordered.get(0),ordered.get(1),at);
    }
    private void message(UUID id,UUID conversation,UUID sender,UUID recipient,LocalDateTime at,boolean deleted) {
        jdbc.update("insert into tbl_dm_message values (?,?,?,?,?,'text',?,null,?)",id,conversation,sender,recipient,"fixture-"+id,at,deleted?at:null);
    }
    private static UUID id(int value) { return UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d",value)); }
    private static UUID peer(int value) { return UUID.fromString((value%2==0?"90000000":"10000000")+"-0000-0000-0000-"+String.format("%012d",value)); }
    private static void assertError(Runnable action,ErrorType type) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SoundConnectException.class,error -> assertThat(error.getErrorType()).isEqualTo(type));
    }

    private static class CapturingJdbc extends NamedParameterJdbcTemplate {
        String pageSql;
        SqlParameterSource pageParams;
        CapturingJdbc(javax.sql.DataSource source) { super(source); }
        @Override public <T> List<T> query(String sql,SqlParameterSource parameters,RowMapper<T> mapper) {
            if(sql.startsWith("with candidates")) { pageSql=sql; pageParams=parameters; }
            return super.query(sql,parameters,mapper);
        }
    }
}
