package com.berkayb.soundconnect.modules.notification.repository;

import com.berkayb.soundconnect.modules.notification.service.NotificationServiceImpl;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production Hibernate scalar query, real PostgreSQL read-only transaction, unique disposable schema. */
@EnabledIfSystemProperty(named="push.test.jdbc-url",matches="jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/.*")
class NotificationDeliveryStatePostgresTest {
    JdbcTemplate admin,jdbc;
    String schema;
    LocalContainerEntityManagerFactoryBean factory;
    EntityManager em;
    NotificationServiceImpl service;
    SessionFactory hibernate;
    UUID owner=UUID.randomUUID(),foreign=UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("push.test.jdbc-url"),user=System.getProperty("push.test.username","postgres");
        String password=System.getProperty("push.test.password","");
        var base=new DriverManagerDataSource(url,user,password);
        try(var connection=base.getConnection()) { assertThat(connection.getCatalog()).containsIgnoringCase("test"); }
        admin=new JdbcTemplate(base); schema="notification_state_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("create schema "+schema);
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(source);
        factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
        factory.setPackagesToScan("com.berkayb.soundconnect.modules.notification.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.generate_statistics","true",
                "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        hibernate=factory.getObject().unwrap(SessionFactory.class);em=factory.getObject().createEntityManager();
        jdbc.execute("create table tbl_role(id uuid primary key,name text)");
        jdbc.execute("create table user_roles(user_id uuid not null,role_id uuid not null,primary key(user_id,role_id))");
        var audience=new NotificationAudienceRepositoryImpl(em);
        var repository=mock(NotificationRepository.class);
        when(repository.findVisibleUnreadIds(any(UUID.class),anyCollection())).thenAnswer(invocation->
                audience.findVisibleUnreadIds(invocation.getArgument(0),invocation.getArgument(1)));
        service=new NotificationServiceImpl(repository,null,null,null,null,null);
    }
    @AfterEach void cleanup() {
        if(em!=null) { if(em.getTransaction().isActive())em.getTransaction().rollback();em.close(); }
        if(factory!=null)factory.destroy();
        if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");
    }

    @Test void mixedReadUnreadDeletedForeignAndMissingAreIsolatedWithoutChangingPersistentState() {
        UUID unread=seed(owner,false,"DM_NEW_MESSAGE"),read=seed(owner,true,"DM_NEW_MESSAGE");
        UUID other=seed(foreign,false,"DM_NEW_MESSAGE"),deleted=seed(owner,false,"DM_NEW_MESSAGE"),missing=UUID.randomUUID();
        jdbc.update("delete from tbl_notification where id=?",deleted);
        var before=jdbc.queryForList("select id,recipient_id,is_read from tbl_notification order by id");
        assertThat(lookup(owner,List.of(unread,read,deleted,other,missing))).containsExactly(read,deleted,other,missing);
        assertThat(lookup(foreign,List.of(unread,read,deleted,other,missing))).containsExactly(unread,read,deleted,missing);
        assertThat(jdbc.queryForList("select id,recipient_id,is_read from tbl_notification order by id")).isEqualTo(before);
    }

    @Test void currentListenerRoleHidesBusinessRowsIncludingMixedRolesWithoutDeletingAnything() {
        UUID dm=seed(owner,false,"DM_NEW_MESSAGE"),business=seed(owner,false,"STUDIO_RESERVATION_APPROVED");
        assertThat(lookup(owner,List.of(dm,business))).isEmpty();
        addRole("ROLE_LISTENER");
        assertThat(lookup(owner,List.of(dm,business))).containsExactly(business);
        addRole("ROLE_MUSICIAN");
        assertThat(lookup(owner,List.of(dm,business))).containsExactly(business);
        jdbc.update("delete from user_roles where role_id in(select id from tbl_role where name='ROLE_LISTENER')");
        assertThat(lookup(owner,List.of(dm,business))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification where is_read=false",Integer.class)).isEqualTo(2);
    }

    @Test void databaseReadTransitionAndPhysicalDeletionAreObservedOnNextLookup() {
        UUID id=seed(owner,false,"DM_NEW_MESSAGE");
        assertThat(lookup(owner,List.of(id))).isEmpty();
        jdbc.update("update tbl_notification set is_read=true where id=?",id);
        assertThat(lookup(owner,List.of(id))).containsExactly(id);
        jdbc.update("delete from tbl_notification where id=?",id);
        assertThat(lookup(owner,List.of(id))).containsExactly(id);
    }

    @Test void oneHundredRequestedIdsNeedExactlyOneScalarSqlQueryAndReturnNoUnrequestedRows() {
        List<UUID> requested=new ArrayList<>();
        for(int i=0;i<100;i++)requested.add(seed(owner,i%2==0,"DM_NEW_MESSAGE"));
        seed(owner,true,"DM_NEW_MESSAGE");
        assertThat(lookup(owner,requested)).containsExactlyElementsOf(java.util.stream.IntStream.range(0,100)
                .filter(i->i%2==0).mapToObj(requested::get).toList());
        assertThat(hibernate.getStatistics().getEntityLoadCount()).isZero();
        assertThat(hibernate.getStatistics().getEntityUpdateCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_notification",Integer.class)).isEqualTo(101);
    }

    private List<UUID> lookup(UUID user,List<UUID> ids) {
        em.getTransaction().begin();
        em.createNativeQuery("set transaction read only").executeUpdate();
        hibernate.getStatistics().clear();
        var result=service.getDismissedDeliveryIds(user,ids);
        assertThat(hibernate.getStatistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(hibernate.getStatistics().getQueryExecutionCount()).isEqualTo(1);
        em.getTransaction().commit();return result;
    }
    private UUID seed(UUID recipient,boolean read,String type) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into tbl_notification(id,created_at,updated_at,recipient_id,type,title,message,occurred_at,is_read)"
                +" values(?,now(),now(),?,?,'fixture','fixture',now(),?)",id,recipient,type,read);
        return id;
    }
    private void addRole(String name) {
        UUID id=UUID.randomUUID();jdbc.update("insert into tbl_role values (?,?)",id,name);
        jdbc.update("insert into user_roles values (?,?)",owner,id);
    }
}
