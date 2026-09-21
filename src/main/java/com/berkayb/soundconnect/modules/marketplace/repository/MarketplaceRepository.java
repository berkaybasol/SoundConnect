package com.berkayb.soundconnect.modules.marketplace.repository;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.*;
import com.berkayb.soundconnect.modules.marketplace.support.MarketplaceAccess;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.shared.ownership.OwnedProfileTarget;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class MarketplaceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MediaAssetService mediaAssets;
    public record Stored(Listing listing, boolean sellerEligible, UUID sellerAvatarMediaId) {}
    public record CategoryRow(UUID id, String code, String name, UUID parentId, boolean active) {}

    private static final String FROM = """
            from tbl_marketplace_listing l join tbl_user u on u.id=l.owner_user_id
            left join tbl_marketplace_category c on c.id=l.category_id
            left join tbl_marketplace_category root_category on root_category.id=c.parent_id
            left join tbl_district d on d.id=l.district_id
            left join tbl_city city on city.id=d.city_id
            left join tbl_musician_profile musician on l.seller_profile_type='MUSICIAN'
                and musician.id=l.seller_profile_id and musician.user_id=l.owner_user_id
            left join tbl_studio_profile studio on l.seller_profile_type='STUDIO'
                and studio.id=l.seller_profile_id and studio.user_id=l.owner_user_id
            left join tbl_venues venue on l.seller_profile_type='VENUE'
                and venue.id=l.seller_profile_id and venue.owner_id=l.owner_user_id
            left join tbl_venue_profile venue_profile on venue_profile.venue_id=venue.id
            """;
    private static final String ELIGIBLE = "(" + MarketplaceAccess.eligibleOwnerSql("u")
            + " and " + MarketplaceAccess.eligibleListingProfileSql("l") + ")";
    private static final String SELECT = """
            select l.*, u.user_name as username,
            coalesce(nullif(musician.name,''),nullif(studio.name,''),nullif(venue.name,''),u.user_name) as seller_name,
            coalesce(musician.profile_picture_media_id,studio.profile_picture_media_id,
                venue_profile.profile_picture_media_id) as seller_avatar_media_id,
            c.code as category_code,c.name as category_name,
            root_category.id as root_id,root_category.code as root_code,root_category.name as root_name,
            d.name as district_name, city.id as city_id,city.name as city_name,
            exists(select 1 from tbl_marketplace_saved s where s.listing_id=l.id and s.user_id=:viewer) as saved,
            """ + ELIGIBLE + " as seller_eligible " + FROM;

    public void lockUser(UUID userId) {
        List<UUID> found = jdbc.query("select id from tbl_user where id=:id order by id for update",
                Map.of("id",userId),(rs,n)->uuid(rs,"id"));
        if (found.isEmpty()) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
    }
    public Optional<Stored> find(UUID id, UUID viewer, boolean lock) {
        List<Stored> rows = jdbc.query(SELECT + " where l.id=:id" + (lock ? " for update of l" : ""),
                Map.of("id",id,"viewer",viewer),this::map);
        return attachPhotos(rows).stream().findFirst();
    }
    public Optional<UUID> draftReplay(UUID owner, UUID request) {
        return jdbc.query("select id from tbl_marketplace_listing where owner_user_id=:owner and client_request_id=:request",
                Map.of("owner",owner,"request",request),(rs,n)->uuid(rs,"id")).stream().findFirst();
    }
    public long countByOwner(UUID user, Status status) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from tbl_marketplace_listing where owner_user_id=:id and status=:status",
                Map.of("id",user,"status",status.name()),Long.class));
    }
    public UUID insertDraft(UUID userId, UUID request, OwnedProfileTarget seller) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into tbl_marketplace_listing(id,owner_user_id,seller_profile_type,seller_profile_id,client_request_id)
                values(:id,:owner,:type,:profile,:request)
                """,Map.of("id",id,"owner",userId,"type",seller.type().name(),"profile",seller.sourceId(),"request",request));
        return id;
    }
    public void update(UUID id, Update value) {
        jdbc.update("""
                update tbl_marketplace_listing set title=:title,description=:description,category_id=:category,
                    brand=:brand,model=:model,condition=:condition,price_minor=:price,district_id=:district,
                    negotiable=:negotiable,delivery_method=:delivery,version=version+1,updated_at=CURRENT_TIMESTAMP
                where id=:id
                """,new MapSqlParameterSource().addValue("id",id).addValue("title",value.title())
                .addValue("description",value.description()).addValue("category",value.categoryId())
                .addValue("brand",value.brand()).addValue("model",value.model())
                .addValue("condition",name(value.condition())).addValue("price",value.priceMinor())
                .addValue("district",value.districtId()).addValue("negotiable",value.negotiable())
                .addValue("delivery",name(value.deliveryMethod())));
    }
    public void replacePhotos(UUID id, List<UUID> photos) {
        jdbc.update("delete from tbl_marketplace_listing_photo where listing_id=:id",Map.of("id",id));
        for(int i=0;i<photos.size();i++) jdbc.update("""
                insert into tbl_marketplace_listing_photo(listing_id,media_asset_id,position) values(:id,:asset,:position)
                """,Map.of("id",id,"asset",photos.get(i),"position",i));
    }
    public void transition(UUID id, Status status) {
        jdbc.update("""
                update tbl_marketplace_listing set status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP,
                    published_at=case when :status='PUBLISHED' then coalesce(published_at,CURRENT_TIMESTAMP) else published_at end
                where id=:id
                """,Map.of("id",id,"status",status.name()));
    }
    public void delete(UUID id) {
        jdbc.update("delete from tbl_marketplace_listing where id=:id",Map.of("id",id));
    }

    public PageResponse<Listing> page(UUID viewer, Filter filter, boolean mine, boolean saved,
                                       Status status, int page, int size) {
        MapSqlParameterSource args=new MapSqlParameterSource("viewer",viewer);
        StringBuilder where=new StringBuilder(" where ");
        if(mine) {
            where.append("l.owner_user_id=:viewer");
            if(status!=null) { where.append(" and l.status=:status"); args.addValue("status",status.name()); }
        } else where.append("l.status='PUBLISHED' and ").append(ELIGIBLE);
        if(saved) where.append(" and exists(select 1 from tbl_marketplace_saved s where s.listing_id=l.id and s.user_id=:viewer)");
        if(filter!=null) {
            if(filter.categoryId()!=null) {
                where.append(" and (c.id=:category or c.parent_id=:category)");args.addValue("category",filter.categoryId());
            }
            if(filter.cityId()!=null) {where.append(" and d.city_id=:city");args.addValue("city",filter.cityId());}
            if(filter.districtId()!=null) {where.append(" and l.district_id=:district");args.addValue("district",filter.districtId());}
            if(filter.condition()!=null) {where.append(" and l.condition=:condition");args.addValue("condition",filter.condition().name());}
            if(filter.minPriceMinor()!=null) {where.append(" and l.price_minor>=:min");args.addValue("min",filter.minPriceMinor());}
            if(filter.maxPriceMinor()!=null) {where.append(" and l.price_minor<=:max");args.addValue("max",filter.maxPriceMinor());}
            if(filter.q()!=null && !filter.q().isBlank()) {
                // Fold both operands in PostgreSQL so locale-sensitive letters use the same rules.
                where.append(" and (lower(l.title) like lower(:q) escape '\\' or lower(l.brand) like lower(:q) escape '\\' or lower(l.model) like lower(:q) escape '\\')");
                args.addValue("q","%"+escapeLike(filter.q().strip())+"%");
            }
        }
        long total=Objects.requireNonNull(jdbc.queryForObject("select count(*) "+FROM+where,args,Long.class));
        String order=mine?"l.created_at desc,l.id desc":saved?
                "(select s.saved_at from tbl_marketplace_saved s where s.listing_id=l.id and s.user_id=:viewer) desc,l.id desc":
                switch(filter==null || filter.sort()==null?SortOrder.NEWEST:filter.sort()) {
                    case NEWEST -> "l.published_at desc,l.id desc";
                    case PRICE_ASC -> "l.price_minor asc,l.id asc";
                    case PRICE_DESC -> "l.price_minor desc,l.id desc";
                };
        args.addValue("limit",size).addValue("offset",(long)page*size);
        List<Listing> content=attachPhotos(jdbc.query(SELECT+where+" order by "+order+" limit :limit offset :offset",args,this::map))
                .stream().map(row->forViewer(row.listing(),viewer)).toList();
        int totalPages=(int)((total+size-1)/size);
        return new PageResponse<>(content,page,size,total,totalPages,page==0,page+1>=totalPages);
    }
    public List<CategoryRow> categories() {
        return jdbc.query("select * from tbl_marketplace_category order by sort_order,id",Map.of(),(rs,n)->
                new CategoryRow(uuid(rs,"id"),rs.getString("code"),rs.getString("name"),uuid(rs,"parent_id"),rs.getBoolean("active")));
    }
    public Optional<CategoryRow> category(UUID id) {
        return jdbc.query("select * from tbl_marketplace_category where id=:id",Map.of("id",id),(rs,n)->
                new CategoryRow(uuid(rs,"id"),rs.getString("code"),rs.getString("name"),uuid(rs,"parent_id"),rs.getBoolean("active")))
                .stream().findFirst();
    }
    public void save(UUID user, UUID listing) {
        jdbc.update("insert into tbl_marketplace_saved(user_id,listing_id) values(:user,:listing) on conflict do nothing",Map.of("user",user,"listing",listing));
    }
    public void unsave(UUID user, UUID listing) {
        jdbc.update("delete from tbl_marketplace_saved where user_id=:user and listing_id=:listing",Map.of("user",user,"listing",listing));
    }
    public Optional<AdminReport> reportReplay(UUID user, UUID request) {
        return jdbc.query("select * from tbl_marketplace_report where reporter_user_id=:user and client_request_id=:request",
                Map.of("user",user,"request",request),this::reportMap).stream().findFirst();
    }
    public boolean alreadyReported(UUID user, UUID listing) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tbl_marketplace_report where reporter_user_id=:user and listing_id=:listing)",
                Map.of("user",user,"listing",listing),Boolean.class));
    }
    public AdminReport createReport(UUID reporter, UUID listing, Report request, Listing evidence) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into tbl_marketplace_report(id,listing_id,reporter_user_id,client_request_id,reason,description,evidence)
                values(:id,:listing,:reporter,:request,:reason,:description,cast(:evidence as jsonb))
                """,new MapSqlParameterSource().addValue("id",id).addValue("listing",listing).addValue("reporter",reporter)
                .addValue("request",request.clientRequestId()).addValue("reason",request.reason().name())
                .addValue("description",request.description()).addValue("evidence",serialize(evidence)));
        for(Photo photo:evidence.photos()) {
            jdbc.update("insert into tbl_marketplace_report_photo(report_id,media_asset_id) values(:report,:asset)",
                    Map.of("report",id,"asset",photo.assetId()));
        }
        return report(id,false).orElseThrow();
    }
    public Optional<AdminReport> report(UUID id, boolean lock) {
        return jdbc.query("select * from tbl_marketplace_report where id=:id"+(lock?" for update":""),Map.of("id",id),this::reportMap).stream().findFirst();
    }
    public PageResponse<AdminReport> reports(ReportStatus status,int page,int size) {
        MapSqlParameterSource args=new MapSqlParameterSource().addValue("limit",size).addValue("offset",(long)page*size);
        String where=status==null?"":" where status=:status";
        if(status!=null) args.addValue("status",status.name());
        long total=Objects.requireNonNull(jdbc.queryForObject("select count(*) from tbl_marketplace_report"+where,args,Long.class));
        List<AdminReport> rows=jdbc.query("select * from tbl_marketplace_report"+where+" order by reported_at desc,id desc limit :limit offset :offset",args,this::reportMap);
        int pages=(int)((total+size-1)/size);
        return new PageResponse<>(rows,page,size,total,pages,page==0,page+1>=pages);
    }
    public void resolveReport(UUID id,UUID actor,Review review) {
        jdbc.update("""
                update tbl_marketplace_report set status=:status,decision=:decision,resolution_note=:note,
                    reviewed_by_user_id=:actor,reviewed_at=CURRENT_TIMESTAMP,version=version+1 where id=:id
                """,Map.of("id",id,"status",review.decision()==ReportDecision.DISMISS?"DISMISSED":"ACTIONED",
                "decision",review.decision().name(),"note",review.resolutionNote(),"actor",actor));
        jdbc.update("""
                insert into tbl_marketplace_report_audit(id,report_id,actor_user_id,decision,resolution_note,resulting_version)
                select :audit,id,:actor,decision,resolution_note,version from tbl_marketplace_report where id=:id
                """,Map.of("audit",UUID.randomUUID(),"id",id,"actor",actor));
    }
    private Stored map(ResultSet rs,int index) throws SQLException {
        UUID owner=uuid(rs,"owner_user_id");
        ListingCategory category=uuid(rs,"category_id")==null?null:new ListingCategory(uuid(rs,"category_id"),rs.getString("category_code"),
                rs.getString("category_name"),uuid(rs,"root_id"),rs.getString("root_code"),rs.getString("root_name"));
        Location city=uuid(rs,"city_id")==null?null:new Location(uuid(rs,"city_id"),rs.getString("city_name"));
        Location district=uuid(rs,"district_id")==null?null:new Location(uuid(rs,"district_id"),rs.getString("district_name"));
        return new Stored(new Listing(uuid(rs,"id"),rs.getLong("version"),Status.valueOf(rs.getString("status")),
                rs.getString("title"),rs.getString("description"),category,rs.getString("brand"),rs.getString("model"),
                enumValue(Condition.class,rs.getString("condition")),rs.getObject("price_minor",Long.class),"TRY",rs.getBoolean("negotiable"),
                enumValue(Delivery.class,rs.getString("delivery_method")),city,district,List.of(),
                new Seller(owner,rs.getString("username"),rs.getString("seller_profile_type"),uuid(rs,"seller_profile_id"),rs.getString("seller_name"),null),
                false,rs.getBoolean("saved"),instant(rs,"created_at"),instant(rs,"published_at")),
                rs.getBoolean("seller_eligible"),uuid(rs,"seller_avatar_media_id"));
    }
    private List<Stored> attachPhotos(List<Stored> rows) {
        if(rows.isEmpty()) return rows;
        Map<UUID,List<Photo>> photos=new HashMap<>();
        jdbc.query("select listing_id,media_asset_id from tbl_marketplace_listing_photo where listing_id in (:ids) order by listing_id,position",
                Map.of("ids",rows.stream().map(row->row.listing().id()).toList()),(org.springframework.jdbc.core.RowCallbackHandler)rs->
                        photos.computeIfAbsent(uuid(rs,"listing_id"),ignored->new ArrayList<>()).add(new Photo(uuid(rs,"media_asset_id"))));
        List<UUID> avatarIds=rows.stream().map(Stored::sellerAvatarMediaId).filter(Objects::nonNull).distinct().toList();
        // Reuse the profile media policy (public, READY, thumbnail preferred) in one
        // bounded batch. Missing/private avatars remain absent, never signed here.
        Map<UUID,String> avatars=avatarIds.isEmpty()?Map.of():mediaAssets.getDisplayUrlMap(avatarIds);
        return rows.stream().map(row->{
            Listing value=row.listing();
            String avatarUrl=row.sellerAvatarMediaId()==null?null:avatars.get(row.sellerAvatarMediaId());
            return new Stored(withMedia(value,List.copyOf(photos.getOrDefault(value.id(),List.of())),avatarUrl),
                    row.sellerEligible(),row.sellerAvatarMediaId());
        }).toList();
    }
    public static Listing forViewer(Listing v,UUID viewer) {
        return new Listing(v.id(),v.version(),v.status(),v.title(),v.description(),v.category(),v.brand(),v.model(),v.condition(),v.priceMinor(),
                v.currency(),v.negotiable(),v.deliveryMethod(),v.city(),v.district(),v.photos(),v.seller(),v.seller().userId().equals(viewer),v.saved(),v.createdAt(),v.publishedAt());
    }
    private static Listing withMedia(Listing v,List<Photo> photos,String avatarUrl) {
        Seller seller=v.seller();
        return new Listing(v.id(),v.version(),v.status(),v.title(),v.description(),v.category(),v.brand(),v.model(),v.condition(),v.priceMinor(),
                v.currency(),v.negotiable(),v.deliveryMethod(),v.city(),v.district(),photos,
                new Seller(seller.userId(),seller.username(),seller.profileType(),seller.profileId(),seller.displayName(),avatarUrl),
                v.isOwner(),v.saved(),v.createdAt(),v.publishedAt());
    }
    private AdminReport reportMap(ResultSet rs,int n) throws SQLException {
        try {
            return new AdminReport(uuid(rs,"id"),rs.getLong("version"),uuid(rs,"listing_id"),uuid(rs,"reporter_user_id"),
                    ReportReason.valueOf(rs.getString("reason")),rs.getString("description"),ReportStatus.valueOf(rs.getString("status")),
                    json.readTree(rs.getString("evidence")),instant(rs,"reported_at"),uuid(rs,"reviewed_by_user_id"),instant(rs,"reviewed_at"),
                    enumValue(ReportDecision.class,rs.getString("decision")),rs.getString("resolution_note"));
        } catch(JsonProcessingException invalid) { throw new SQLException("Invalid stored report evidence",invalid); }
    }
    private String serialize(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("Cannot serialize marketplace evidence",failure);}
    }
    public static String escapeLike(String value) {return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static String name(Enum<?> value){return value==null?null:value.name();}
    private static <E extends Enum<E>> E enumValue(Class<E> type,String value){return value==null?null:Enum.valueOf(type,value);}
    private static UUID uuid(ResultSet rs,String name)throws SQLException{return rs.getObject(name,UUID.class);}
    private static Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name)==null?null:rs.getTimestamp(name).toInstant();}
}
