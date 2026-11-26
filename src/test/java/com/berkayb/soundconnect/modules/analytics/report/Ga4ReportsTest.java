package com.berkayb.soundconnect.modules.analytics.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ga4ReportsTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode row(List<String> dimensions,long... values) {
        var row=json.createObjectNode();
        var dims=row.putArray("dimensionValues");
        dimensions.forEach(value->dims.addObject().put("value",value));
        var metrics=row.putArray("metricValues");
        for(long value:values) metrics.addObject().put("value",String.valueOf(value));
        return row;
    }
    private Map<String,List<String>> filters(JsonNode report) {
        var filters=new java.util.LinkedHashMap<String,List<String>>();
        var root=report.get("dimensionFilter");
        Iterable<JsonNode> expressions=root.has("filter")?List.of(root):root.at("/andGroup/expressions");
        for (var expression : expressions) {
            var filter=expression.get("filter");
            assertThat(filter.at("/inListFilter/caseSensitive").asBoolean()).isTrue();
            var values=new ArrayList<String>();
            filter.at("/inListFilter/values").forEach(value->values.add(value.asText()));
            filters.put(filter.get("fieldName").asText(),values);
        }
        return filters;
    }
    @Test void allSixMonthlyReportsRetainTheirProductEventAndAudienceFilters() {
        var requests=new ArrayList<JsonNode>();
        var reports=new Ga4Reports(json,true,"123","456") {
            @Override JsonNode call(String method,Object request) {
                assertThat(method).isEqualTo("batchRunReports");
                var response=json.createObjectNode();
                var reports=response.putArray("reports");
                for (var report : json.valueToTree(request).get("requests")) {
                    requests.add(report);
                    reports.addObject().putObject("metadata").put("timeZone","Europe/Istanbul");
                }
                return response;
            }
        };
        var date=LocalDate.of(2026,11,26);
        reports.monthly(date,date,"STUDIO",false);
        assertThat(requests).hasSize(6);
        for (int i=0;i<requests.size();i++) {
            assertThat(filters(requests.get(i))).isEqualTo(Map.of(
                    "streamId",List.of("456"),Ga4Reports.PROFILE,List.of(i==1?"guest":"studio"),
                    "eventName",i<4?Ga4Reports.ACTIVITY:List.of("section_time")));
        }
    }
    @Test void cohortIntersectionUsesPeriodUniquesAndInactiveDaysCountInTheAverage() {
        var result=Ga4Reports.summarize(LocalDate.of(2026,11,1),LocalDate.of(2026,11,3),List.of(
                List.of(row(List.of(),3)),List.of(row(List.of(),7)),
                List.of(row(List.of("20261101"),2),row(List.of("20261103"),2)),
                List.of(row(List.of("2026110118"),2),row(List.of("2026110318"),1)),
                List.of(row(List.of("marketplace"),2,120000),row(List.of("other"),3,360000)),
                List.of(row(List.of("20261101","marketplace"),2,120000)),
                List.of(row(List.of(),5)),List.of(row(List.of(),6))));
        assertThat(result.mau()).isEqualTo(3);
        assertThat(result.guestMau()).isEqualTo(7);
        assertThat(result.daily()).extracting(AnalyticsReport.Day::users).containsExactly(2L,0L,2L);
        assertThat(result.avgDau()).isCloseTo(4d/3,within(0.00001));
        assertThat(result.minutesPerActiveDay()).isEqualTo(2d);
        assertThat(result.returned()).isEqualTo(2);
        assertThat(result.returnRate()).isEqualTo(0.4);
        assertThat(result.hours().get(18).users()).isEqualTo(1d);
    }
    @Test void aPartialFirstMonthHasNoInventedReturningCohortOrDurationAverage() {
        var d=LocalDate.of(2026,11,26);
        var result=Ga4Reports.summarize(d,d,List.of(List.of(),List.of(),List.of(),List.of(),List.of(),List.of()));
        assertThat(result.mau()).isZero();
        assertThat(result.returnRate()).isNull();
        assertThat(result.previousActive()).isNull();
        assertThat(result.minutesPerActiveDay()).isNull();
    }
    @Test void januaryReturnQueriesCompareTheSameAudienceThroughTheLastObservedDay() {
        var requests = new ArrayList<JsonNode>();
        var reports = new Ga4Reports(json, true, "123", "456") {
            @Override JsonNode call(String method, Object request) {
                assertThat(method).isEqualTo("batchRunReports");
                var response = json.createObjectNode();
                var batch = response.putArray("reports");
                for (var report : json.valueToTree(request).get("requests")) {
                    requests.add(report);
                    var result = batch.addObject();
                    result.putObject("metadata").put("timeZone", "Europe/Istanbul");
                    long total = switch (requests.size()) {
                        case 1 -> 75;  // January
                        case 7 -> 200; // December
                        case 8 -> 215; // Both months together
                        default -> 0;
                    };
                    if (total > 0) {
                        result.putArray("rows").add(row(List.of(), total));
                        result.put("rowCount", 1);
                    }
                }
                return response;
            }
        };
        var usage = reports.monthly(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 8), "MUSICIAN", true);
        assertThat(requests).hasSize(8);
        for (int i : List.of(0, 6, 7)) {
            var request = requests.get(i);
            assertThat(filters(request)).isEqualTo(Map.of("streamId", List.of("456"), Ga4Reports.PROFILE,
                    List.of("musician"), "eventName", Ga4Reports.ACTIVITY));
            assertThat(request.get("dimensions").size()).isZero();
            assertThat(request.at("/metrics/0/name").asText()).isEqualTo("totalUsers");
            assertThat(request.at("/dateRanges/0/startDate").asText()).isEqualTo(i == 0 ? "2027-01-01" : "2026-12-01");
            assertThat(request.at("/dateRanges/0/endDate").asText()).isEqualTo(i == 6 ? "2026-12-31" : "2027-01-08");
        }
        assertThat(usage.previousActive()).isEqualTo(200);
        assertThat(usage.returned()).isEqualTo(60);
        assertThat(usage.returnRate()).isEqualTo(0.30);
    }

    @Test void aComparisonRequiresCoverageAtIstanbulMidnightOnTheFirstOfThePreviousMonth() {
        var history = mock(ReportHistory.class);
        var google = mock(Ga4Reports.class);
        when(google.enabled()).thenReturn(true);
        var access = mock(AdminPanelReadAccess.class);
        var january = LocalDate.of(2025, 1, 1);
        Instant decemberMidnight = Instant.parse("2024-11-30T21:00:00Z");
        for (boolean coversPrevious : List.of(true, false)) {
            when(history.started()).thenReturn(coversPrevious ? decemberMidnight : decemberMidnight.plusNanos(1000));
            new AdminAnalyticsReports(access, history, google).get(null, "2025-01", ProfileType.MUSICIAN);
            verify(google).monthly(january, january.withDayOfMonth(31), "MUSICIAN", coversPrevious);
        }
    }

    @Test void zeroPriorAudienceAndInconsistentPeriodUniquesHaveNoReturnPercentage() {
        var day = LocalDate.of(2027, 1, 1);
        var current = List.of(row(List.of(), 5));
        for (int previous : List.of(0, 3)) {
            // For three prior members, a union of nine exceeds the sum from the separate periods.
            var union = List.of(row(List.of(), previous == 0 ? 5 : 9));
            var usage = Ga4Reports.summarize(day, day, List.of(current, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(row(List.of(), previous)), union));
            assertThat(usage.previousActive()).isEqualTo(previous);
            if (previous == 0) assertThat(usage.returned()).isZero();
            else assertThat(usage.returned()).isNull();
            assertThat(usage.returnRate()).isNull();
        }
    }
    @Test void withheldSampledTruncatedOrWrongTimezoneCannotTurnIntoZero() throws Exception {
        for(String meta:List.of("{\"subjectToThresholding\":true}","{\"dataLossFromOtherRow\":true}",
                "{\"samplingMetadatas\":[{}]}","{\"timeZone\":\"Europe/London\"}")) {
            var response=json.createObjectNode(); response.set("metadata",json.readTree(meta));
            assertThatThrownBy(()->Ga4Reports.rows(response,true)).isInstanceOf(Ga4Reports.Unavailable.class);
        }
        var missing=json.readTree("{\"rowCount\":1,\"metadata\":{\"timeZone\":\"Europe/Istanbul\"}}");
        assertThatThrownBy(()->Ga4Reports.rows(missing,true)).isInstanceOf(Ga4Reports.Unavailable.class);
        assertThat(Ga4Reports.turkeyReportingTime("Etc/GMT-3")).isTrue();
        assertThat(Ga4Reports.turkeyReportingTime("GMT+03:00")).isTrue();
        assertThat(Ga4Reports.turkeyReportingTime("Europe/Helsinki")).isFalse();
    }

    @Test void invalidProviderDimensionsMakeOnlyGa4UsageUnavailable() {
        var day = LocalDate.of(2026, 11, 26);
        var empty = List.<JsonNode>of();
        for (String hour : List.of("(not set)", "2026112624", "2026112512", "2026023009")) {
            var hourly = List.of(row(List.of(hour), 1));
            assertThatThrownBy(() -> Ga4Reports.summarize(day, day,
                    List.of(empty, empty, empty, hourly, empty, empty)))
                    .isInstanceOf(Ga4Reports.Unavailable.class);
        }
        for (String date : List.of("(not set)", "20261127")) {
            var daily = List.of(row(List.of(date), 1));
            var section = List.of(row(List.of(date, "discover"), 1, 1000));
            assertThatThrownBy(() -> Ga4Reports.summarize(day, day,
                    List.of(empty, empty, daily, empty, empty, empty)))
                    .isInstanceOf(Ga4Reports.Unavailable.class);
            assertThatThrownBy(() -> Ga4Reports.summarize(day, day,
                    List.of(empty, empty, empty, empty, empty, section)))
                    .isInstanceOf(Ga4Reports.Unavailable.class);
        }
        assertThatThrownBy(() -> Ga4Reports.metric(row(List.of(), -1), 0))
                .isInstanceOf(Ga4Reports.Unavailable.class);
    }
}
