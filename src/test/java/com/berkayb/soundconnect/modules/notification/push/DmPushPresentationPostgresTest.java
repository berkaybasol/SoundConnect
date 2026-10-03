package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "push.test.jdbc-url", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
class DmPushPresentationPostgresTest {
    JdbcTemplate admin,jdbc;
    NamedParameterJdbcTemplate named;
    DataSourceTransactionManager manager;
    String schema,handleSql;
    UUID sender;

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("push.test.jdbc-url"),user=System.getProperty("push.test.username","postgres"),password=System.getProperty("push.test.password","");
        var base=new DriverManagerDataSource(url,user,password);
        try(var connection=base.getConnection()) { assertThat(connection.getCatalog()).containsIgnoringCase("test"); }
        admin=new JdbcTemplate(base); schema="dm_handle_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(source); named=new NamedParameterJdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        jdbc.execute("create table tbl_user(id uuid primary key,user_name text,status text,email_verified boolean,erased_at timestamp)");
        jdbc.execute("create table \"tbl_listener-profile\"(user_id uuid primary key,visibility_mode text,visibility_choice_completed boolean)");
        sender=UUID.randomUUID(); jdbc.update("insert into tbl_user values (?,'public_handle','ACTIVE',true,null)",sender);
        handleSql=UserRepository.class.getMethod("findPublicUsernameForDmPush",UUID.class).getAnnotation(Query.class).value();
        assertThat(com.berkayb.soundconnect.modules.user.entity.User.class.getDeclaredField("username")
                .getAnnotation(jakarta.persistence.Column.class).name()).isEqualTo("user_name");
    }
    @AfterEach void cleanup() { if(admin!=null && schema!=null) admin.execute("drop schema "+schema+" cascade"); }

    @Test void exactRepositorySqlOnlyExposesActiveVerifiedUnrestrictedHandles() {
        assertThat(handle()).contains("public_handle");
        jdbc.update("update tbl_user set status='PENDING' where id=?",sender); assertThat(handle()).isEmpty();
        jdbc.update("update tbl_user set status='ACTIVE',email_verified=false where id=?",sender); assertThat(handle()).isEmpty();
        jdbc.update("update tbl_user set email_verified=true,erased_at=now() where id=?",sender); assertThat(handle()).isEmpty();
        jdbc.update("update tbl_user set erased_at=null where id=?",sender);
        jdbc.update("insert into \"tbl_listener-profile\" values (?,'STANDARD',false)",sender); assertThat(handle()).isEmpty();
        jdbc.update("update \"tbl_listener-profile\" set visibility_choice_completed=true"); assertThat(handle()).contains("public_handle");
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST'"); assertThat(handle()).isEmpty();
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode=null"); assertThat(handle()).isEmpty();
        jdbc.update("update \"tbl_listener-profile\" set visibility_mode='STANDARD',visibility_choice_completed=null"); assertThat(handle()).isEmpty();
        assertThat(named.queryForList(handleSql,Map.of("userId",UUID.randomUUID()),String.class)).isEmpty();
    }

    @Test void presentationTransactionKeepsResolverVisibilityLockUntilScalarReadCompletes() throws Exception {
        jdbc.update("insert into \"tbl_listener-profile\" values (?,'STANDARD',true)",sender);
        var scalarEntered=new CountDownLatch(1); var releaseScalar=new CountDownLatch(1); var mutationEntered=new CountDownLatch(1);
        var profiles=mock(PublicProfileResolverService.class);
        when(profiles.resolveByUserId(sender)).thenAnswer(invocation -> {
            // Same shared row lock contract as ListenerVisibilityPolicy inside
            // the real public resolver. The outer presentation must retain it.
            String mode=jdbc.queryForObject("select visibility_mode from \"tbl_listener-profile\" where user_id=? for share",String.class,sender);
            return new UserProfilesResolveResponseDto(sender,List.of(new UserProfileTargetDto("LISTENER",UUID.randomUUID(),
                    "GHOST".equals(mode)?"safe_ghost_alias":"Display Name",null,"GHOST".equals(mode)?ListenerVisibilityMode.GHOST:null)));
        });
        var users=mock(UserRepository.class);
        when(users.findPublicUsernameForDmPush(sender)).thenAnswer(invocation -> {
            scalarEntered.countDown();
            if(!releaseScalar.await(5,TimeUnit.SECONDS)) throw new AssertionError("scalar release timeout");
            return handle();
        });
        var proxy=new ProxyFactory(new DmPushPresentation(profiles,users,"https://fixture.invalid"));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var subject=(DmPushPresentation)proxy.getProxy();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var resolving=pool.submit(() -> subject.resolve(sender));
            assertThat(scalarEntered.await(5,TimeUnit.SECONDS)).isTrue();
            var transition=pool.submit(() -> { mutationEntered.countDown(); jdbc.update("update \"tbl_listener-profile\" set visibility_mode='GHOST' where user_id=?",sender); });
            assertThat(mutationEntered.await(5,TimeUnit.SECONDS)).isTrue();
            try { assertThatThrownBy(() -> transition.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { releaseScalar.countDown(); }
            assertThat(resolving.get(5,TimeUnit.SECONDS).name()).isEqualTo("public_handle");
            transition.get(5,TimeUnit.SECONDS);
        }
        assertThat(subject.resolve(sender).name()).isEqualTo("safe_ghost_alias");
        verify(users,times(1)).findPublicUsernameForDmPush(sender);
    }

    private Optional<String> handle() { return named.queryForList(handleSql,Map.of("userId",sender),String.class).stream().findFirst(); }
}
