package com.berkayb.soundconnect.shared.recovery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/** Explicit, hash-bound LOCAL_DISPOSABLE recovery launch. No authority or OPEN transition.
 * The host/operator must supply the pinned contract and an isolated network;
 * this launcher does not claim to fence other writers or create isolation.
 */
public final class RecoveryTargetLauncher {
    private static final Set<String> KEYS=Set.of("version","runId","role","targetKind",
        "targetResource","database","databaseUser","rabbitPort","rabbitVhost","redisPort","redisDatabase","artifactSha256");
    private RecoveryTargetLauncher() { }
    private static void require(boolean ok,String reason) {
        if(!ok) throw new IllegalStateException("RECOVERY_TARGET_"+reason);
    }
    static Map<String,String> contract(byte[] raw,String sha,String expectedRun) throws Exception {
        require(raw.length>0 && raw.length<=4096 && sha!=null && sha.matches("[0-9a-f]{64}"),"CONTRACT_LIMIT");
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(sha),"CONTRACT_PIN");
        var values=new LinkedHashMap<String,String>();
        String text=new String(raw,StandardCharsets.US_ASCII);
        require(java.util.Arrays.equals(raw,text.getBytes(StandardCharsets.US_ASCII)),"CONTRACT_ENCODING");
        for(String line:text.split("\n",-1)) {
            if(line.isEmpty()) continue;
            require(line.matches("[A-Za-z][A-Za-z0-9]*=[\\x20-\\x7e]+"),"CONTRACT_FORMAT");
            int equals=line.indexOf('=');
            require(values.putIfAbsent(line.substring(0,equals),line.substring(equals+1))==null,"CONTRACT_DUPLICATE");
        }
        require(values.keySet().equals(KEYS) && "1".equals(values.get("version")),"CONTRACT_FIELDS");
        String run=values.get("runId");
        require(UUID.fromString(run).toString().equals(run) && run.equals(expectedRun),"RUN_BINDING");
        require(Set.of("api","media-worker").contains(values.get("role")),"ROLE");
        require(values.get("artifactSha256").matches("[0-9a-f]{64}"),"ARTIFACT_PIN");
        require(values.get("databaseUser").matches("[a-z][a-z0-9_]{0,62}")
            && values.get("rabbitVhost").matches("/[a-zA-Z0-9_-]{0,63}"),"SERVICE_IDENTITY");
        for(String port:new String[]{"rabbitPort","redisPort"})
            require(values.get(port).matches("[1-9][0-9]{0,4}") && Integer.parseInt(values.get(port))<=65535,"PORT");
        require(values.get("redisDatabase").matches("[0-9]|1[0-5]"),"REDIS_DATABASE");
        require("LOCAL_DISPOSABLE".equals(values.get("targetKind")),"TARGET_KIND");
        require(values.get("targetResource").equals("fixture-"+run),"RESOURCE");
        require(values.get("database").equals("jdbc:postgresql://pg:5432/hold_"+run.replace("-","")),"DATABASE");
        return Map.copyOf(values);
    }
    static void bind(ConfigurableApplicationContext context,Map<String,String> c) {
        RecoveryRuntime.quarantine();
        var env=context.getEnvironment();
        require(c.get("database").equals(env.getProperty("spring.datasource.url")),"DATABASE_BINDING");
        require(c.get("databaseUser").equals(env.getProperty("spring.datasource.username")),"DATABASE_USER");
        require(env.getProperty("spring.datasource.jndi-name","").isBlank(),"DATABASE_ALTERNATE");
        require("rabbit".equals(env.getProperty("spring.rabbitmq.host")),"RABBIT_BINDING");
        require(env.getProperty("spring.rabbitmq.addresses","").isBlank(),"RABBIT_ALTERNATE");
        require(c.get("rabbitPort").equals(env.getProperty("spring.rabbitmq.port","5672"))
            && c.get("rabbitVhost").equals(env.getProperty("spring.rabbitmq.virtual-host","/")),"RABBIT_NAMESPACE");
        if("api".equals(c.get("role"))) {
            var redis=org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("spring.data.redis",org.springframework.boot.autoconfigure.data.redis.RedisProperties.class)
                .orElseGet(org.springframework.boot.autoconfigure.data.redis.RedisProperties::new);
            require(redis.getCluster()==null && redis.getSentinel()==null && (redis.getUrl()==null || redis.getUrl().isBlank()),"REDIS_ALTERNATE");
            require("redis".equals(env.getProperty("spring.data.redis.host")),"REDIS_BINDING");
            require(c.get("redisPort").equals(env.getProperty("spring.data.redis.port","6379"))
                && c.get("redisDatabase").equals(env.getProperty("spring.data.redis.database","0")),"REDIS_NAMESPACE");
            require(env.getProperty("spring.data.redis.url","").isBlank()
                && env.getProperty("spring.data.redis.cluster.nodes","").isBlank()
                && env.getProperty("spring.data.redis.sentinel.master","").isBlank(),"REDIS_ALTERNATE");
        }
        require("HOLD".equals(env.getProperty("app.recovery.mode")) && "validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")),"MODE");
        // A separate instance makes the mandatory guard independent of factories
        // registration. Both invocations are intentionally idempotent and closed.
        new RecoveryRuntimeInitializer().initialize(context);
        context.addBeanFactoryPostProcessor(factory->factory.addBeanPostProcessor(
            new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean,String name) {
                    if(bean instanceof com.zaxxer.hikari.HikariDataSource source) {
                        require(c.get("database").equals(source.getJdbcUrl()) && source.getDataSource()==null
                            && c.get("databaseUser").equals(source.getUsername()) && source.getDataSourceClassName()==null
                            && source.getDataSourceJNDI()==null,"POOL_BINDING");
                        var properties=source.getDataSourceProperties();
                        require(properties.keySet().equals(Set.of("readOnlyMode"))
                            && "always".equals(properties.getProperty("readOnlyMode")),"POOL_PROPERTIES");
                    }
                    if(bean instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redis) {
                        require(redis.getClusterConfiguration()==null && redis.getSentinelConfiguration()==null
                            && "redis".equals(redis.getHostName()) && redis.getPort()==Integer.parseInt(c.get("redisPort"))
                            && redis.getDatabase()==Integer.parseInt(c.get("redisDatabase")),"REDIS_FACTORY");
                    }
                    return bean;
                }
            }));
    }
    private static byte[] read(Path path,int maximum) throws Exception {
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),"FILE");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw=input.readNBytes(maximum+1);require(raw.length<=maximum,"FILE_LIMIT");return raw;
        }
    }
    public static void main(String[] args) throws Exception {
        RecoveryRuntime.quarantine();
        require(args.length==0,"ARGUMENTS");
        require("/recovery/application.jar".equals(System.getProperty("java.class.path")),"CLASSPATH");
        var c=contract(read(Path.of("/recovery/runtime.properties"),4096),
            System.getenv("SC_RECOVERY_CONTRACT_SHA256"),System.getenv("SC_RECOVERY_RUN_ID"));
        Path jar=Path.of("/recovery/application.jar");
        require(Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(jar),"ARTIFACT_FILE");
        var digest=MessageDigest.getInstance("SHA-256");long length=0;
        try(var input=Files.newInputStream(jar,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[65536];int count;
            while((count=input.read(buffer))!=-1) {
                length+=count;require(length<=256L*1024*1024,"ARTIFACT_LIMIT");digest.update(buffer,0,count);
            }
        }
        require(length>0 && HexFormat.of().formatHex(digest.digest()).equals(c.get("artifactSha256")),"ARTIFACT_PIN");
        boolean worker=c.get("role").equals("media-worker");
        Class<?> source=Class.forName(worker
            ? "com.berkayb.soundconnectworker.MediaWorkerApplication" : "com.berkayb.soundconnect.SoundConnectApplication");
        String origin="jar:nested:/recovery/application.jar/!BOOT-INF/classes/!/";
        for(Class<?> type:new Class<?>[]{source,RecoveryTargetLauncher.class,RecoveryRuntime.class,RecoveryRuntimeInitializer.class})
            require(type.getProtectionDomain().getCodeSource().getLocation().toExternalForm().equals(origin),"CLASS_ORIGIN");
        var application=new SpringApplication(source);
        application.setAdditionalProfiles(worker?new String[]{"media-worker","recovery"}:new String[]{"recovery"});
        if(worker)application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context->bind(context,c));
        // No user arguments can override mandatory mode, DDL or import isolation.
        application.run("--app.recovery.mode=HOLD","--spring.jpa.hibernate.ddl-auto=validate",
            "--SOUNDCONNECT_CONFIG_IMPORT=","--spring.config.import=","--spring.config.location=classpath:/application.yml",
            "--spring.config.additional-location=","--spring.config.name=application","--spring.profiles.active=local",
            "--spring.main.lazy-initialization=false","--spring.main.web-application-type="+(worker?"none":"servlet"));
    }
}
