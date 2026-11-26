package com.berkayb.soundconnect.modules.analytics.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalQuery;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class Ga4Reports {
    static final String PROFILE = "customUser:profile_type";
    static final String SECTION = "customEvent:section";
    static final String DURATION = "customEvent:duration_ms";
    static final Set<String> SECTIONS = Set.of("tables","overthinking","marketplace","collab","discover","profiles","other");
    static final List<String> ACTIVITY = List.of("foreground_visit","section_time","meaningful_action");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter
            .ofPattern("uuuuMMddHH", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";
    private final String property;
    private final String stream;
    private final boolean enabled;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private GoogleCredentials credential;
    private volatile Instant retryAfter = Instant.MIN;

    public Ga4Reports(ObjectMapper json, @Value("${app.analytics-report.enabled:false}") boolean enabled,
                      @Value("${app.analytics-report.property:}") String property,
                      @Value("${app.analytics-report.stream:}") String stream) {
        this.json=json; this.enabled=enabled; this.property=property; this.stream=stream;
        if (enabled && (!property.matches("[0-9]{1,20}") || !stream.matches("[0-9]{1,20}")))
            throw new IllegalArgumentException("GA4 property and stream are required");
    }
    public boolean enabled() { return enabled; }
    private synchronized String authorization() throws IOException {
        if (credential == null) credential=GoogleCredentials.getApplicationDefault().createScoped(SCOPE).createWithCustomRetryStrategy(false);
        credential.refreshIfExpired();
        return "Bearer " + credential.getAccessToken().getTokenValue();
    }
    JsonNode call(String method,Object request) {
        if (!enabled || Instant.now().isBefore(retryAfter)) throw new Unavailable();
        try {
            URI uri=URI.create("https://analyticsdata.googleapis.com/v1beta/properties/"+property+":"+method);
            var post=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(9))
                    .header("Content-Type","application/json").header("Authorization",authorization())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build();
            var response=http.send(post,HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode()!=200) {
                if (response.statusCode()==403 || response.statusCode()==429) {
                    retryAfter=Instant.now().plusSeconds(600);
                }
                throw new Unavailable();
            }
            return json.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Unavailable();
        } catch (IOException e) { throw new Unavailable(); }
    }
    static class Unavailable extends RuntimeException {}
    private static Map<String,Object> filter(String name,List<String> values) {
        return Map.of("filter", Map.of("fieldName",name,"inListFilter",Map.of("values",values,"caseSensitive",true)));
    }
    private Map<String,Object> filters(List<String> roles,List<String> events) {
        var expressions=new ArrayList<>(List.of(filter("streamId",List.of(stream)),filter(PROFILE,roles)));
        if (!events.isEmpty()) expressions.add(filter("eventName",events));
        return Map.of("andGroup",Map.of("expressions",expressions));
    }
    private static List<String> roles(String role) {
        return role.equals("ALL") ? List.of("listener","musician","venue","studio") : List.of(role.toLowerCase(Locale.ROOT));
    }
    private static List<Map<String,String>> names(List<String> values) {
        return values.stream().map(n->Map.of("name",n)).toList();
    }
    private Map<String,Object> report(LocalDate start, LocalDate end, String role, List<String> events,
                                      List<String> dims, List<String> metrics) {
        return Map.of("dateRanges",List.of(Map.of("startDate",start.toString(),"endDate",end.toString())),
                "dimensions",names(dims),"metrics",names(metrics),"dimensionFilter",filters(roles(role),events),"limit","10000");
    }
    private List<List<JsonNode>> batch(List<Map<String,Object>> requests) {
        var result=new ArrayList<List<JsonNode>>();
        for(int i=0;i<requests.size();i+=5) {
            var batch=call("batchRunReports",Map.of("requests",requests.subList(i,Math.min(i+5,requests.size())))).path("reports");
            if (!batch.isArray() || batch.size()!=Math.min(5,requests.size()-i)) throw new Unavailable();
            batch.forEach(raw->result.add(rows(raw,true)));
        }
        return result;
    }
    static List<JsonNode> rows(JsonNode response, boolean historical) {
        var meta=response.path("metadata");
        if (meta.path("dataLossFromOtherRow").asBoolean() || meta.path("subjectToThresholding").asBoolean()
                || meta.path("samplingMetadatas").size()>0) throw new Unavailable();
        if (historical && !turkeyReportingTime(meta.path("timeZone").asText())) throw new Unavailable();
        var result=new ArrayList<JsonNode>();
        response.path("rows").forEach(result::add);
        if (response.path("rowCount").asInt()!=result.size()) throw new Unavailable();
        return result;
    }
    static boolean turkeyReportingTime(String zone) {
        try {
            var rules=ZoneId.of(zone).getRules();
            return rules.equals(AdminAnalyticsReports.ZONE.getRules())
                    || (rules.isFixedOffset() && rules.getOffset(Instant.EPOCH).equals(ZoneOffset.ofHours(3)));
        } catch (DateTimeException invalid) { return false; }
    }
    static String dim(JsonNode row,int n) { return row.path("dimensionValues").path(n).path("value").asText(); }
    static long metric(JsonNode row,int n) {
        try {
            long value = new java.math.BigDecimal(row.path("metricValues").path(n).path("value").asText()).longValueExact();
            if (value < 0) throw new Unavailable();
            return value;
        }
        catch (ArithmeticException|NumberFormatException e) { throw new Unavailable(); }
    }
    static long total(List<JsonNode> rows) { return rows.isEmpty()?0:metric(rows.get(0),0); }
    private static <T> T reportDate(JsonNode row, DateTimeFormatter format, TemporalQuery<T> type) {
        try {
            return format.parse(dim(row, 0), type);
        } catch (DateTimeException invalid) {
            throw new Unavailable();
        }
    }
    public AnalyticsReport.Usage monthly(LocalDate start,LocalDate end,String role,boolean previousCovered) {
        var requests=new ArrayList<Map<String,Object>>();
        List<String> users=List.of("totalUsers"), sectionMetrics=List.of("totalUsers",DURATION);
        requests.add(report(start,end,role,ACTIVITY,List.of(),users));
        requests.add(report(start,end,"guest",ACTIVITY,List.of(),users));
        requests.add(report(start,end,role,ACTIVITY,List.of("date"),users));
        requests.add(report(start,end,role,ACTIVITY,List.of("dateHour"),users));
        requests.add(report(start,end,role,List.of("section_time"),List.of(SECTION),sectionMetrics));
        requests.add(report(start,end,role,List.of("section_time"),List.of("date",SECTION),sectionMetrics));
        if (previousCovered) {
            var prev=start.withDayOfMonth(1).minusMonths(1);
            requests.add(report(prev,start.minusDays(1),role,ACTIVITY,List.of(),users));
            requests.add(report(prev,end,role,ACTIVITY,List.of(),users));
        }
        return summarize(start,end,batch(requests));
    }
    static AnalyticsReport.Usage summarize(LocalDate start,LocalDate end,List<List<JsonNode>> data) {
        long mau=total(data.get(0)), guest=total(data.get(1));
        var days=new LinkedHashMap<LocalDate,Long>();
        start.datesUntil(end.plusDays(1)).forEach(d->days.put(d,0L));
        long[] hours=new long[24];
        for (var row : data.get(2)) {
            var date = reportDate(row, DateTimeFormatter.BASIC_ISO_DATE, LocalDate::from);
            if (days.replace(date, metric(row, 0)) == null) throw new Unavailable();
        }
        for (var row : data.get(3)) {
            var dateHour = reportDate(row, DATE_HOUR, LocalDateTime::from);
            if (!days.containsKey(dateHour.toLocalDate())) throw new Unavailable();
            hours[dateHour.getHour()] += metric(row, 0);
        }
        long userDays=days.values().stream().mapToLong(Long::longValue).sum();
        var daily=days.entrySet().stream().map(e->new AnalyticsReport.Day(e.getKey(),e.getValue())).toList();
        var hourly=new ArrayList<AnalyticsReport.Hour>();
        for(int i=0;i<24;i++) hourly.add(new AnalyticsReport.Hour(i,(double)hours[i]/days.size()));
        var weekly=days.entrySet().stream().collect(Collectors.groupingBy(e->e.getKey().getDayOfWeek().getValue(),
                TreeMap::new, Collectors.averagingLong(Map.Entry::getValue))).entrySet().stream()
                .map(e->new AnalyticsReport.Weekday(e.getKey(),e.getValue())).toList();
        var sections=new ArrayList<AnalyticsReport.Section>();
        for(var row:data.get(4)) {
            if(!SECTIONS.contains(dim(row,0))) throw new Unavailable();
            sections.add(new AnalyticsReport.Section(dim(row,0),metric(row,0),metric(row,1)));
        }
        var byDate=new ArrayList<AnalyticsReport.DaySection>();
        for(var row:data.get(5)) {
            if(!SECTIONS.contains(dim(row,1)))throw new Unavailable();
            var date = reportDate(row, DateTimeFormatter.BASIC_ISO_DATE, LocalDate::from);
            if (!days.containsKey(date)) throw new Unavailable();
            byDate.add(new AnalyticsReport.DaySection(date,dim(row,1),metric(row,0),metric(row,1)));
        }
        long milliseconds=sections.stream().mapToLong(AnalyticsReport.Section::milliseconds).sum();
        // Inclusion-exclusion counts the intersection without exporting a member identifier.
        Long previous=data.size()==8?total(data.get(6)):null;
        Long returned=previous==null?null:previous+mau-total(data.get(7));
        if(returned!=null && (returned<0 || returned>Math.min(previous,mau))) returned=null;
        Double rate=previous==null || previous==0 || returned==null?null:(double)returned/previous;
        return new AnalyticsReport.Usage(mau,guest,(double)userDays/days.size(),previous,returned,rate,milliseconds,
                userDays==0?null:milliseconds/60000d/userDays,daily,hourly,weekly,sections,byDate);
    }
}
