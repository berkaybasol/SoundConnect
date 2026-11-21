package com.berkayb.soundconnect.shared.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Narrow OTLP adapter for our allowlist. Micrometer still owns collection and cumulative histograms. */
final class SafeOtlpMetricEncoder {
    private final Resource resource;
    private final long startedAt;
    private final AtomicLong unsupported = new AtomicLong();
    private final TelemetryExportMeterFilter safety = new TelemetryExportMeterFilter(2048);

    SafeOtlpMetricEncoder(Map<String, String> attributes, long startedAt) {
        var resource = Resource.newBuilder();
        attributes.forEach((key, value) -> resource.addAttributes(attribute(key, value)));
        this.resource = resource.build();
        this.startedAt = startedAt;
    }

    byte[] encode(List<Meter> meters, long now) {
        var grouped = new LinkedHashMap<String, Metric.Builder>();
        for (Meter meter : meters) {
            try {
                Metric metric = encode(meter, now);
                if (metric != null) {
                    Metric.Builder previous = grouped.get(metric.getName());
                    if (previous == null) grouped.put(metric.getName(), metric.toBuilder());
                    else if (previous.getDataCase() != metric.getDataCase() || !previous.getUnit().equals(metric.getUnit())) unsupported.incrementAndGet();
                    else if (metric.hasGauge()) previous.getGaugeBuilder().addAllDataPoints(metric.getGauge().getDataPointsList());
                    else if (metric.hasSum()) previous.getSumBuilder().addAllDataPoints(metric.getSum().getDataPointsList());
                    else if (metric.hasHistogram()) previous.getHistogramBuilder().addAllDataPoints(metric.getHistogram().getDataPointsList());
                }
            } catch (RuntimeException invalidMeasurement) {
                unsupported.incrementAndGet();
            }
        }
        var scope = ScopeMetrics.newBuilder();
        grouped.values().forEach(scope::addMetrics);
        return ExportMetricsServiceRequest.newBuilder().addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource).addScopeMetrics(scope)).build().toByteArray();
    }

    private Metric encode(Meter meter, long now) {
        Meter.Id id = meter.getId();
        if (!safety.canExport(id)) {
            unsupported.incrementAndGet();
            return null;
        }
        var metric = Metric.newBuilder().setName(id.getName());
        if (id.getBaseUnit() != null) metric.setUnit(id.getBaseUnit());
        if (meter instanceof Gauge gauge) {
            double value = gauge.value();
            // Explicit NaN invalidates a prior sample immediately; omission would leave
            // the last healthy value visible during the remote Prometheus lookback.
            // Micrometer 1.14's OTLP gauge convention also writes NaN as AsDouble.
            if (Double.isInfinite(value)) return null;
            return metric.setGauge(io.opentelemetry.proto.metrics.v1.Gauge.newBuilder()
                    .addDataPoints(number(id, now, value))).build();
        }
        if (meter instanceof Counter counter) return counter(metric, id, now, counter.count());
        if (meter instanceof FunctionCounter counter) return counter(metric, id, now, counter.count());
        if (meter instanceof Timer timer) {
            metric.setUnit("s");
            HistogramSnapshot snapshot = timer.takeSnapshot();
            var point = histogram(id, now, snapshot.count(), snapshot.total(TimeUnit.SECONDS));
            // OtlpMeterRegistry cumulative snapshots contain exclusive bucket counts,
            // unlike Prometheus cumulative bucket samples. Do not subtract adjacent bins.
            for (var bucket : snapshot.histogramCounts()) {
                long count = (long) bucket.count();
                if (count < 0) throw new IllegalArgumentException("Invalid histogram count");
                if (Double.isFinite(bucket.bucket())) point.addExplicitBounds(bucket.bucket(TimeUnit.SECONDS));
                point.addBucketCounts(count);
            }
            if (snapshot.histogramCounts().length == 0) point.addBucketCounts(snapshot.count());
            else if (snapshot.histogramCounts().length > 11
                    || Double.isFinite(snapshot.histogramCounts()[snapshot.histogramCounts().length - 1].bucket()))
                throw new IllegalArgumentException("Unsupported histogram schema");
            return metric.setHistogram(Histogram.newBuilder().setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                    .addDataPoints(point)).build();
        }
        if (meter instanceof FunctionTimer timer) {
            metric.setUnit("s");
            long count = (long) timer.count();
            return metric.setHistogram(Histogram.newBuilder().setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                    .addDataPoints(histogram(id, now, count, timer.totalTime(TimeUnit.SECONDS)).addBucketCounts(count))).build();
        }
        unsupported.incrementAndGet();
        return null;
    }

    private Metric counter(Metric.Builder metric, Meter.Id id, long now, double value) {
        if (!Double.isFinite(value) || value < 0) return null;
        return metric.setSum(Sum.newBuilder().setIsMonotonic(true)
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE)
                .addDataPoints(number(id, now, value))).build();
    }

    private NumberDataPoint.Builder number(Meter.Id id, long now, double value) {
        var point = NumberDataPoint.newBuilder().setStartTimeUnixNano(startedAt).setTimeUnixNano(now).setAsDouble(value);
        id.getTags().forEach(tag -> point.addAttributes(attribute(tag.getKey(), tag.getValue())));
        return point;
    }

    private HistogramDataPoint.Builder histogram(Meter.Id id, long now, long count, double total) {
        if (count < 0 || !Double.isFinite(total) || total < 0) throw new IllegalArgumentException("Invalid histogram measurement");
        var point = HistogramDataPoint.newBuilder().setStartTimeUnixNano(startedAt).setTimeUnixNano(now).setCount(count).setSum(total);
        id.getTags().forEach(tag -> point.addAttributes(attribute(tag.getKey(), tag.getValue())));
        return point;
    }

    private static KeyValue attribute(String key, String value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    long unsupportedMeters() { return unsupported.get(); }
}
