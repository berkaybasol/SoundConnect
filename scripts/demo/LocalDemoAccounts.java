import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

/** Local one-shot provisioning tool. Does not start Spring, change auth, or send mail. */
public final class LocalDemoAccounts {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SEED = "soundconnect-local-marketplace-demo-v1";
    static final Set<String> ROLES = Set.of("MUSICIAN", "STUDIO", "VENUE");
    static final Map<String, List<String>> COLUMNS = Map.of(
        "tbl_user", List.of("id", "public_code", "user_name", "password", "email", "provider", "status", "email_verified", "city_id", "description", "created_at", "updated_at"),
        "user_roles", List.of("user_id", "role_id"),
        "tbl_musician_profile", List.of("id", "user_id", "name", "stage_name", "description", "address", "spotify_tracks", "created_at", "updated_at"),
        "tbl_studio_profile", List.of("id", "user_id", "name", "description", "address", "city_id", "district_id", "neighborhood_id", "time_zone", "version", "spotify_tracks", "created_at", "updated_at"),
        "tbl_venues", List.of("id", "owner_id", "name", "description", "address", "city_id", "district_id", "neighborhood_id", "status", "created_at", "updated_at"),
        "tbl_venue_profile", List.of("id", "venue_id", "bio", "created_at", "updated_at"),
        "musician_profile_instruments", List.of("musician_profile_id", "instrument_id")
    );

    record NamedId(UUID id, String name) {}
    record Account(String username, String role, String displayName, String bio, String address,
                   UUID userId, UUID profileId, UUID venueProfileId, String publicCode,
                   UUID roleId, NamedId city, NamedId district, NamedId neighborhood,
                   List<NamedId> instruments, boolean exists) {}

    public static void main(String[] args) {
        try {
            run(options(args));
        } catch (SQLException failure) {
            // PostgreSQL exception details can echo row values, including password hashes.
            System.err.println("Demo seed aborted; database transaction rolled back. SQLState=" + failure.getSQLState());
            System.exit(1);
        } catch (Exception failure) {
            // Only our explicit validation messages are safe to print, never connection/JSON contents.
            System.err.println(failure instanceof GuardFailure ? failure.getMessage()
                    : "Demo seed aborted safely (" + failure.getClass().getSimpleName() + "). No credential details logged.");
            System.exit(1);
        }
    }

    static Map<String,String> options(String[] args) {
        Map<String,String> result = new HashMap<>();
        for (int index=0; index<args.length; index+=2) {
            check(index+1<args.length && Set.of("--mode","--root","--accounts","--guard","--output").contains(args[index]), "Invalid seed command options.");
            check(result.put(args[index],args[index+1])==null,"Duplicate seed option.");
        }
        check(result.size()==5 && Set.of("dry-run","apply").contains(result.get("--mode")),"Explicit dry-run or apply mode and all paths are required.");
        return result;
    }

    static void run(Map<String,String> args) throws Exception {
        boolean apply = args.get("--mode").equals("apply");
        Path root = Path.of(args.get("--root")).toRealPath();
        Path output = Path.of(args.get("--output")).toAbsolutePath().normalize();
        check(output.startsWith(root.resolve("tmp/marketplace-demo")),"Manifest must remain under backend tmp/marketplace-demo.");
        JsonNode guard = JSON.readTree(Path.of(args.get("--guard")).toFile());
        check(guard.path("project").asText().equals("soundconnect-local") && guard.path("service").asText().equals("postgres"),"Unexpected Docker project/service.");
        check(Path.of(guard.path("root").asText()).toRealPath().equals(root),"Docker project directory differs from this repository.");
        Instant checkedAt = Instant.parse(guard.path("checkedAt").asText());
        check(!checkedAt.isAfter(Instant.now().plusSeconds(10)) && checkedAt.isAfter(Instant.now().minusSeconds(120)),"Docker identity proof is stale.");
        check(guard.path("containerId").asText().matches("[a-f0-9]{12,64}"),"Invalid local container proof.");
        Map<String,String> config = dotEnv(root.resolve(".env.local"));
        String url = setting(config,"SOUNDCONNECT_POSTGRES_URL","SOUNDCONNECT_POSTGRE_URL","SPRING_DATASOURCE_URL");
        check(url.startsWith("jdbc:postgresql://"),"Only explicit local PostgreSQL JDBC URLs are permitted.");
        URI uri = URI.create(url.substring(5));
        check(Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost()) && uri.getUserInfo()==null
                && uri.getRawQuery()==null && uri.getFragment()==null,"JDBC URL must be loopback-only without userinfo or query options.");
        check(uri.getPort()==guard.path("hostPort").asInt() && uri.getPath().equals("/"+guard.path("database").asText()),"JDBC target differs from the selected local Docker database.");
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user",setting(config,"SOUNDCONNECT_POSTGRES_USERNAME","SOUNDCONNECT_POSTGRE_USERNAME","SPRING_DATASOURCE_USERNAME"));
        connectionProperties.setProperty("password",setting(config,"SOUNDCONNECT_POSTGRES_PASSWORD","SOUNDCONNECT_POSTGRE_PASSWORD","SPRING_DATASOURCE_PASSWORD"));
        connectionProperties.setProperty("connectTimeout","5");
        connectionProperties.setProperty("socketTimeout","30");
        connectionProperties.setProperty("ApplicationName","soundconnect-local-demo-seed");
        String password = System.getenv("SOUNDCONNECT_DEMO_PASSWORD");
        if (apply) check(password!=null && password.length()>=16 && password.getBytes(StandardCharsets.UTF_8).length<=72,
                "Apply requires SOUNDCONNECT_DEMO_PASSWORD with at least 16 characters and at most 72 UTF-8 bytes.");
        JsonNode input = JSON.readTree(Path.of(args.get("--accounts")).toFile());
        check(input.path("seedId").asText().equals(SEED) && input.path("schemaVersion").asInt()==1,"Unexpected seed manifest identity/version.");
        check(input.path("accounts").isArray() && input.path("accounts").size()==25,"This seed must contain exactly 25 accounts.");
        try (Connection db = DriverManager.getConnection(url,connectionProperties)) {
            db.setReadOnly(!apply);
            db.setAutoCommit(false);
            try {
                try (Statement statement = db.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout='5s'");
                    statement.execute("SET LOCAL statement_timeout='30s'");
                    statement.execute("SET LOCAL search_path=public,pg_catalog");
                }
                try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("select current_database(), system_identifier::text, pg_is_in_recovery() from pg_control_system()")) {
                    check(rows.next() && rows.getString(1).equals(guard.path("database").asText())
                            && rows.getString(2).equals(guard.path("systemIdentifier").asText()) && !rows.getBoolean(3),
                            "Docker and JDBC PostgreSQL identities do not match, or target is a replica.");
                }
                if (apply) execute(db,"select pg_advisory_xact_lock(hashtextextended(?,0))",SEED);
                validateSchema(db);
                List<Account> accounts = prepare(db,input.path("accounts"));
                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                for (Account account : accounts) {
                    if (account.exists()) {
                        if (apply) check(encoder.matches(password,scalar(db,"select password from tbl_user where id=?",account.userId())),
                                "Existing demo password differs for "+account.username()+"; no password was reset.");
                    } else if (apply) insert(db,account,encoder.encode(password));
                }
                if (apply) db.commit(); else db.rollback();
                ObjectNode manifest = manifest(accounts,apply);
                Files.createDirectories(output.getParent());
                Path staging = Files.createTempFile(output.getParent(),"accounts-", ".json.tmp");
                try {
                    JSON.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(),manifest);
                    try { Files.move(staging,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ignored) { Files.move(staging,output,StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(staging); }
                long existing=accounts.stream().filter(Account::exists).count();
                System.out.println((apply?"APPLIED":"DRY RUN")+": 25 accounts; "+existing+" existing, "+(25-existing)+(apply?" created.":" would be created. No database changes."));
                System.out.println("Manifest: "+output);
            } catch (Exception failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    static List<Account> prepare(Connection db,JsonNode values) throws SQLException {
        List<Account> result=new ArrayList<>(); Set<String> usernames=new HashSet<>(); Map<String,Integer> counts=new HashMap<>();
        for (JsonNode value:values) {
            String username=text(value,"username",30), role=text(value,"role",16), name=text(value,"displayName",50), bio=text(value,"bio",500);
            check(username.matches("demo_[a-z0-9_]{1,25}") && usernames.add(username),"Demo usernames must be unique, lowercase ASCII and start with demo_.");
            check(ROLES.contains(role),"Unsupported demo role."); counts.merge(role,1,Integer::sum);
            NamedId city=resolve(db,"select id,name from tbl_city",text(value,"city",100),"city");
            NamedId district=resolve(db,"select id,name from tbl_district where city_id=?",text(value,"district",100),"district",city.id());
            String neighborhood=value.path("neighborhood").asText("");
            NamedId area=neighborhood.isBlank()?first(db,"select id,name from tbl_neighborhood where district_id=? order by name,id limit 1",district.id())
                    :resolve(db,"select id,name from tbl_neighborhood where district_id=?",neighborhood,"neighborhood",district.id());
            check(area!=null,"District has no seeded neighborhood: "+district.name());
            UUID user=id(username,"user"), profile=id(username,"profile"), venueProfile=role.equals("VENUE")?id(username,"venue-profile"):null;
            String publicCode="SC-"+user.toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
            String address=value.path("address").asText(district.name()+", "+city.name()+" — yerel demo çalışma alanı.");
            check(address.length()<=255 && !address.isBlank(),"Invalid demo profile address.");
            UUID roleId=UUID.fromString(requiredScalar(db,"select id::text from tbl_role where name=?","Required role is missing.","ROLE_"+role));
            List<NamedId> instruments=new ArrayList<>();
            if (value.has("instruments")) {
                check(role.equals("MUSICIAN") && value.path("instruments").isArray(),"Only musician fixtures can include instruments.");
                for (JsonNode instrument:value.path("instruments")) instruments.add(resolve(db,"select id,name from tbl_instrument",instrument.asText(),"instrument"));
                check(instruments.stream().map(NamedId::id).distinct().count()==instruments.size(),"Duplicate demo instrument.");
            }
            boolean existing=exists(db,"select 1 from tbl_user where id=? or user_name=? or email=? or public_code=?",user,username,username+"@demo.soundconnect.invalid",publicCode);
            Account account=new Account(username,role,name,bio,address,user,profile,venueProfile,publicCode,roleId,city,district,area,List.copyOf(instruments),existing);
            if (existing) assertExisting(db,account);
            else assertNewIds(db,account);
            result.add(account);
        }
        check(counts.equals(Map.of("MUSICIAN",15,"STUDIO",6,"VENUE",4)),"Expected exactly 15 musicians, 6 studios and 4 venues.");
        return result;
    }

    static void assertExisting(Connection db,Account a) throws SQLException {
        check(exists(db,"select 1 from tbl_user where id=? and user_name=? and email=? and public_code=? and provider='LOCAL' and provider_subject is null and status='ACTIVE' and email_verified and erased_at is null",a.userId(),a.username(),a.username()+"@demo.soundconnect.invalid",a.publicCode()),"Conflicting existing identity: "+a.username());
        check("1".equals(scalar(db,"select count(*)::text from user_roles where user_id=?",a.userId()))
                && exists(db,"select 1 from user_roles where user_id=? and role_id=?",a.userId(),a.roleId())
                && !exists(db,"select 1 from user_permissions where user_id=?",a.userId()),"Existing demo role/permission differs: "+a.username());
        String table=profileTable(a.role()), owner=a.role().equals("VENUE")?"owner_id":"user_id";
        check(exists(db,"select 1 from "+table+" where id=? and "+owner+"=?",a.profileId(),a.userId()),"Existing demo profile identity differs: "+a.username());
        check("1".equals(scalar(db,"select count(*)::text from "+table+" where "+owner+"=?",a.userId())),"Existing account has multiple profiles: "+a.username());
        if (a.venueProfileId()!=null) check(exists(db,"select 1 from tbl_venue_profile where id=? and venue_id=?",a.venueProfileId(),a.profileId()),"Existing venue profile differs: "+a.username());
        for (String other:ROLES) if (!other.equals(a.role())) check(!exists(db,"select 1 from "+profileTable(other)+" where "+(other.equals("VENUE")?"owner_id":"user_id")+"=?",a.userId()),"Demo account has another professional profile: "+a.username());
        for (String blocked:List.of("\"tbl_listener-profile\"","tbl_organizer_profile","tbl_producer_profile"))
            check(!exists(db,"select 1 from "+blocked+" where user_id=?",a.userId()),"Demo account has an incompatible personal profile: "+a.username());
    }

    static void assertNewIds(Connection db,Account a) throws SQLException {
        for (String table:List.of("tbl_musician_profile","tbl_studio_profile","tbl_venues","tbl_venue_profile"))
            check(!exists(db,"select 1 from "+table+" where id=?",a.profileId()),"Seed profile UUID collides with an existing row.");
        if (a.venueProfileId()!=null) check(!exists(db,"select 1 from tbl_venue_profile where id=?",a.venueProfileId()),"Seed venue profile UUID collides with an existing row.");
    }

    static void insert(Connection db,Account a,String hash) throws SQLException {
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        execute(db,"insert into tbl_user(id,public_code,user_name,password,email,provider,status,email_verified,city_id,description,created_at,updated_at) values(?,?,?,?,?,'LOCAL','ACTIVE',true,?,?,?,?)",
                a.userId(),a.publicCode(),a.username(),hash,a.username()+"@demo.soundconnect.invalid",a.city().id(),a.bio(),now,now);
        execute(db,"insert into user_roles(user_id,role_id) values(?,?)",a.userId(),a.roleId());
        switch (a.role()) {
            case "MUSICIAN" -> {
                execute(db,"insert into tbl_musician_profile(id,user_id,name,stage_name,description,address,spotify_tracks,created_at,updated_at) values(?,?,?,?,?,?,'[]'::jsonb,?,?)",
                        a.profileId(),a.userId(),a.displayName(),a.displayName(),a.bio(),a.address(),now,now);
                for (NamedId instrument:a.instruments()) execute(db,"insert into musician_profile_instruments(musician_profile_id,instrument_id) values(?,?)",a.profileId(),instrument.id());
            }
            case "STUDIO" -> execute(db,"insert into tbl_studio_profile(id,user_id,name,description,address,city_id,district_id,neighborhood_id,time_zone,version,spotify_tracks,created_at,updated_at) values(?,?,?,?,?,?,?,?,'Europe/Istanbul',0,'[]'::jsonb,?,?)",
                    a.profileId(),a.userId(),a.displayName(),a.bio(),a.address(),a.city().id(),a.district().id(),a.neighborhood().id(),now,now);
            case "VENUE" -> {
                execute(db,"insert into tbl_venues(id,owner_id,name,description,address,city_id,district_id,neighborhood_id,status,created_at,updated_at) values(?,?,?,?,?,?,?,?,'APPROVED',?,?)",
                        a.profileId(),a.userId(),a.displayName(),a.bio(),a.address(),a.city().id(),a.district().id(),a.neighborhood().id(),now,now);
                execute(db,"insert into tbl_venue_profile(id,venue_id,bio,created_at,updated_at) values(?,?,?,?,?)",a.venueProfileId(),a.profileId(),a.bio(),now,now);
            }
            default -> throw new GuardFailure("Unsupported role.");
        }
    }

    static ObjectNode manifest(List<Account> accounts,boolean applied) {
        ObjectNode result=JSON.createObjectNode().put("schemaVersion",1).put("seedId",SEED).put("mode",applied?"APPLIED":"DRY_RUN").put("generatedAt",Instant.now().toString());
        ArrayNode rows=result.putArray("accounts");
        for (Account a:accounts) {
            ObjectNode row=rows.addObject().put("username",a.username()).put("email",a.username()+"@demo.soundconnect.invalid")
                    .put("role",a.role()).put("displayName",a.displayName()).put("bio",a.bio()).put("address",a.address())
                    .put("userId",a.userId().toString()).put("profileId",a.profileId().toString()).put("publicCode",a.publicCode())
                    .put("cityId",a.city().id().toString()).put("city",a.city().name()).put("districtId",a.district().id().toString()).put("district",a.district().name())
                    .put("neighborhoodId",a.neighborhood().id().toString()).put("neighborhood",a.neighborhood().name())
                    .put("result",a.exists()?"EXISTING":applied?"CREATED":"WOULD_CREATE");
            if (a.venueProfileId()!=null) row.put("venueProfileId",a.venueProfileId().toString());
            ArrayNode instruments=row.putArray("instruments");
            for (NamedId instrument:a.instruments()) instruments.addObject().put("id",instrument.id().toString()).put("name",instrument.name());
        }
        return result;
    }

    static void validateSchema(Connection db) throws SQLException {
        for (var entry:COLUMNS.entrySet()) {
            Set<String> found=new HashSet<>();
            try (PreparedStatement statement=prepared(db,"select column_name,is_nullable,column_default,is_identity from information_schema.columns where table_schema='public' and table_name=?",entry.getKey()); ResultSet rows=statement.executeQuery()) {
                while (rows.next()) {
                    String column=rows.getString(1); found.add(column);
                    check(entry.getValue().contains(column) || !rows.getString(2).equals("NO") || rows.getString(3)!=null || rows.getString(4).equals("YES"),
                            "Unexpected required column in "+entry.getKey()+": "+column);
                }
            }
            check(found.containsAll(entry.getValue()),"Seed schema differs in table "+entry.getKey()+"; no changes allowed.");
        }
    }
    static String profileTable(String role) { return switch(role) { case "MUSICIAN" -> "tbl_musician_profile"; case "STUDIO" -> "tbl_studio_profile"; case "VENUE" -> "tbl_venues"; default -> throw new GuardFailure("Unsupported role."); }; }
    static UUID id(String username,String kind) { return UUID.nameUUIDFromBytes((SEED+":"+username+":"+kind).getBytes(StandardCharsets.UTF_8)); }
    static String fold(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('ı','i'); }
    static NamedId resolve(Connection db,String sql,String name,String label,Object... args) throws SQLException {
        List<NamedId> matches=new ArrayList<>();
        try (PreparedStatement statement=prepared(db,sql,args); ResultSet rows=statement.executeQuery()) { while(rows.next()) if(fold(rows.getString(2)).equals(fold(name))) matches.add(new NamedId(rows.getObject(1,UUID.class),rows.getString(2))); }
        check(matches.size()==1,"Missing or ambiguous "+label+" in existing catalog: "+name); return matches.getFirst();
    }
    static NamedId first(Connection db,String sql,Object... args) throws SQLException { try(PreparedStatement statement=prepared(db,sql,args);ResultSet rows=statement.executeQuery()) { return rows.next()?new NamedId(rows.getObject(1,UUID.class),rows.getString(2)):null; } }
    static boolean exists(Connection db,String sql,Object... args) throws SQLException { return scalar(db,sql,args)!=null; }
    static String scalar(Connection db,String sql,Object... args) throws SQLException { try(PreparedStatement statement=prepared(db,sql,args);ResultSet rows=statement.executeQuery()) { return rows.next()?rows.getString(1):null; } }
    static String requiredScalar(Connection db,String sql,String error,Object... args) throws SQLException { String value=scalar(db,sql,args); check(value!=null,error); return value; }
    static void execute(Connection db,String sql,Object... args) throws SQLException { try(PreparedStatement statement=prepared(db,sql,args)) { statement.execute(); } }
    static PreparedStatement prepared(Connection db,String sql,Object... args) throws SQLException {
        PreparedStatement statement=db.prepareStatement(sql);
        for(int index=0;index<args.length;index++) {
            // Match the existing Hibernate LocalDateTime + hibernate.jdbc.time_zone=UTC binding.
            if(args[index] instanceof LocalDateTime date) statement.setTimestamp(index+1,Timestamp.valueOf(date),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            else statement.setObject(index+1,args[index]);
        }
        return statement;
    }
    static String text(JsonNode value,String field,int max) { String text=value.path(field).asText(""); check(!text.isBlank() && text.length()<=max,"Missing or invalid seed field: "+field); return text; }
    static Map<String,String> dotEnv(Path file) throws Exception {
        Map<String,String> result=new HashMap<>();
        for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            String entry=line.strip(); if(entry.isEmpty() || entry.startsWith("#")) continue;
            int separator=entry.indexOf('='); check(separator>0,"Invalid local environment file format.");
            String key=entry.substring(0,separator).strip(), value=entry.substring(separator+1).strip();
            if(value.length()>=2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value=value.substring(1,value.length()-1);
            check(result.put(key,value)==null,"Duplicate key in local environment file.");
        }
        return result;
    }
    static String setting(Map<String,String> file,String... keys) { for(String key:keys) { String value=System.getenv(key); if(value==null || value.isBlank()) value=file.get(key); if(value!=null && !value.isBlank()) return value; } throw new GuardFailure("Required local database configuration is missing."); }
    static void check(boolean condition,String message) { if(!condition) throw new GuardFailure(message); }
    static final class GuardFailure extends RuntimeException { GuardFailure(String message) { super(message); } }
}
