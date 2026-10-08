package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqOperations;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthProbe.Reading;
import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;

/** Whitelist adapter. Never serializes contributor details or dynamically supplied metric names. */
@Component
public class SystemHealthSources {
    private static final Set<String> COUNTS = Set.of("pending", "inFlight", "deadLetter", "suppressed", "retry", "publishing", "review", "stale");
    private static final Map<String, String> PUSH_COUNTS = Map.of("PENDING", "pending", "IN_FLIGHT", "inFlight",
            "DEAD_LETTER", "deadLetter", "SUPPRESSED", "suppressed", "ACCEPTED", "providerAccepted");
    private final HealthContributorRegistry registry;
    private final ObjectProvider<NotificationDlqOperations> dlq;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SystemHealthProperties properties;
    private final Clock clock = Clock.systemUTC();
    private final ApiHealthObservation api;
    private final RabbitQueueHealthSources queues;
    @Autowired private StorageHealthObservation storage;
    private final ObjectProvider<MobileDiagnosticsStore> mobileDiagnostics;

    public SystemHealthSources(HealthContributorRegistry registry, ObjectProvider<NotificationDlqOperations> dlq,
                               DataSource dataSource, PlatformTransactionManager manager,
                               MeterRegistry metrics, SystemHealthProperties properties, RabbitQueueHealthSources queues,
                               ObjectProvider<MobileDiagnosticsStore> mobileDiagnostics) {
        this.registry = registry; this.dlq = dlq; this.properties = properties;
        this.queues = queues;
        this.mobileDiagnostics = mobileDiagnostics;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(Math.max(1, (properties.getProbeTimeoutMillis() + 999) / 1000));
        jdbc.setMaxRows(16);
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(Math.max(1, (properties.getProbeTimeoutMillis() + 999) / 1000));
        api = new ApiHealthObservation(metrics, properties, clock);
    }

    List<SystemHealthProbe> probes() {
        List<SystemHealthProbe> sources = new ArrayList<>();
        sources.add(new SystemHealthProbe("api", "API yanıtları", "İstek gecikmeleri ve sunucu hataları; yalnız bu API örneğinin ölçüm aralığı.", api::read));
        indicator(sources, "database", "Veritabanı", "Hesap ve ürün verilerine erişim etkilenebilir.", "db", false);
        indicator(sources, "redis", "Redis", "Giriş koruması ve geçici veriye erişim etkilenebilir.", "redis", false);
        indicator(sources, "rabbit", "Mesaj aracısı", "Arka plan işleri ve mesaj iletimi gecikebilir.", "rabbit", false);
        indicator(sources, "realtime", "Canlı bağlantı", "Açık ekranlara anlık güncellemeler ulaşmayabilir; istemci teslimi ayrıca doğrulanır.", "webSocketBrokerRelayHealth", false);
        indicator(sources, "push", "Push dağıtımı", "Bildirim gönderimleri gecikebilir. Sağlayıcı kabulü gerçek cihaz teslimini kanıtlamaz.", "pushDeliveryHealth", true);
        indicator(sources, "mail", "Başvuru e-postaları", "Başvuru e-postalarının kuyruğa aktarımı gecikebilir; alıcıya teslim ayrıca doğrulanır.", "applicationMailIntentHealth", true);
        sources.add(new SystemHealthProbe("notificationDlq", "Bildirim hata kuyruğu", "Yalnız hazır mesaj sayısı ölçülür; işlenen mesajlar, toplam ve en eski mesaj yaşı bilinmez.", this::dlq));
        sources.addAll(queues.probes());
        indicator(sources, "followOutbox", "Takip bildirimleri", "Takip bildirimleri gecikebilir.", "followNotificationOutboxHealth", true);
        indicator(sources, "tableOutbox", "Masa bildirimleri", "Masa bildirimleri gecikebilir.", "tableGroupNotificationOutboxHealth", true);
        indicator(sources, "collabOutbox", "Collab bildirimleri", "İş birliği bildirimleri gecikebilir.", "collabNotificationOutboxHealth", true);
        indicator(sources, "overthinkingOutbox", "Overthinking bildirimleri", "Overthinking bildirimleri gecikebilir.", "overthinkingNotificationOutboxHealth", true);
        indicator(sources, "eventOutbox", "Etkinlik bildirimleri", "Etkinlik bildirimleri gecikebilir.", "eventPerformerNotificationOutboxHealth", true);
        indicator(sources, "studioOutbox", "Stüdyo bildirimleri", "Rezervasyon bildirimleri gecikebilir.", "studioReservationNotificationOutboxHealth", true);
        indicator(sources, "venueSuggestionOutbox", "Mekân önerileri", "Mekân önerilerinin işlemleri gecikebilir.", "venueSuggestionOutboxHealth", true);
        sources.add(new SystemHealthProbe("mediaProcessing", "Medya işleme", "Yüklenen medya işlenmeyi bekleyebilir; tamamlanma worker canlılığını kanıtlamaz.", () -> media(false)));
        sources.add(new SystemHealthProbe("mediaCleanup", "Medya temizliği", "Silme ve geçici medya temizliği gecikebilir.", () -> media(true)));
        sources.add(new SystemHealthProbe("mediaWorker", "Medya worker", "Worker DB/mesaj aracısı kontrolü; işleme başarısı ve depolama erişimi ayrı ölçülür.", this::worker));
        sources.add(new SystemHealthProbe("storage", "Medya depolama", "Genel ve özel depoya salt okunur erişim. Yükleme, CDN ve medya işleme başarısı ayrı doğrulanır.",
                () -> storage == null ? Reading.unknown(ReasonCode.NOT_CONFIGURED) : storage.read()));
        sources.add(new SystemHealthProbe("mobileErrors", "Mobil hata alımı", "Son 15 dakikada sunucuya kabul edilmiş güvenli hata kodları; cihaz yakalama ve teslimi ayrıca doğrulanır.", () -> {
            var store = mobileDiagnostics.getIfAvailable();
            return store == null ? Reading.unknown(ReasonCode.NOT_CONFIGURED) : store.health();
        }));
        return List.copyOf(sources);
    }

    private void indicator(List<SystemHealthProbe> list, String id, String label, String impact, String name, boolean databaseRead) {
        list.add(new SystemHealthProbe(id, label, impact, () -> databaseRead
                ? transaction.execute(status -> readContributor(registry.getContributor(name), 0))
                : readContributor(registry.getContributor(name), 0)));
    }

    private Reading readContributor(HealthContributor contributor, int depth) {
        if (contributor == null) return Reading.unknown(ReasonCode.NOT_CONFIGURED);
        if (depth > 3) return Reading.unknown(ReasonCode.METRIC_LIMIT_EXCEEDED);
        if (contributor instanceof HealthIndicator indicator) return sanitize(indicator.health(), clock.instant());
        if (contributor instanceof CompositeHealthContributor composite) {
            List<Status> statuses = new ArrayList<>();
            int count = 0;
            for (var child : composite) {
                if (++count > 8) return Reading.unknown(ReasonCode.METRIC_LIMIT_EXCEEDED);
                statuses.add(readContributor(child.getContributor(), depth + 1).status());
            }
            Status status = SystemHealthService.aggregate(statuses);
            return new Reading(status, reason(status), statuses.isEmpty() || status == Status.UNKNOWN ? null : clock.instant(), Map.of());
        }
        return Reading.unknown(ReasonCode.NOT_CONFIGURED);
    }

    static Reading sanitize(Health health, Instant now) {
        if (health == null) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
        Map<String, Object> details = health.getDetails();
        Status status = "DISABLED".equals(details.get("state")) ? Status.DISABLED : switch (health.getStatus().getCode()) {
            case "UP" -> Status.UP;
            case "DEGRADED" -> Status.DEGRADED;
            case "DOWN", "OUT_OF_SERVICE" -> Status.DOWN;
            default -> Status.UNKNOWN;
        };
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String key : COUNTS) addNumber(metrics, key, details.get(key));
        if (details.get("counts") instanceof Map<?, ?> nested)
            PUSH_COUNTS.forEach((source, target) -> addNumber(metrics, target, nested.get(source)));
        Object oldest = details.containsKey("oldestPendingAt") ? details.get("oldestPendingAt") : details.get("oldestUndeliveredAt");
        if (oldest instanceof Instant timestamp && !timestamp.isAfter(now))
            metrics.put("oldestPendingAgeSeconds", (double) Duration.between(timestamp, now).getSeconds());
        if (status == Status.UNKNOWN) return Reading.unknown(ReasonCode.PROBE_FAILED);
        return new Reading(status, reason(status), now, metrics);
    }

    private static void addNumber(Map<String, Double> out, String key, Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0)
            out.put(key, number.doubleValue());
    }

    static ReasonCode reason(Status status) {
        return switch (status) {
            case UP -> ReasonCode.HEALTHY;
            case DEGRADED -> ReasonCode.DEGRADED_SIGNAL;
            case DOWN -> ReasonCode.DEPENDENCY_UNAVAILABLE;
            case DISABLED -> ReasonCode.FEATURE_DISABLED;
            case STALE -> ReasonCode.MEASUREMENT_STALE;
            case UNKNOWN -> ReasonCode.NOT_MEASURED;
        };
    }

    private Reading dlq() {
        var operations = dlq.getIfAvailable();
        if (operations == null) return Reading.unknown(ReasonCode.NOT_CONFIGURED);
        var summary = operations.summary();
        if (summary.stale()) return new Reading(summary.measuredAt() == null ? Status.UNKNOWN : Status.STALE,
                summary.measuredAt() == null ? ReasonCode.NOT_MEASURED : ReasonCode.MEASUREMENT_STALE, summary.measuredAt(), Map.of());
        if (summary.readyMessages() == null) return Reading.unknown(ReasonCode.PROBE_FAILED);
        Status status = summary.readyMessages() > 0 ? Status.DEGRADED : Status.UP;
        return new Reading(status, reason(status), summary.measuredAt(), Map.of("readyMessages", summary.readyMessages().doubleValue()));
    }

    private Reading media(boolean cleanup) {
        String statuses = cleanup ? "'CLEANUP_PENDING','DELETION_PENDING','HLS_CLEANUP'"
                : "'VERIFYING','TRANSCODE_QUEUED','TRANSCODE_SENT','PROCESSING','FAILED'";
        // A newly requested deletion of an old asset must not look like an old cleanup backlog.
        // Durable fences also avoid moving the age forward on each retry or before writes may safely stop.
        String ageColumn = cleanup ? "CASE WHEN status='DELETION_PENDING' THEN coalesce(physical_deletion_not_before,deletion_requested_at,created_at) "
                + "WHEN status='HLS_CLEANUP' THEN coalesce(transcode_cleanup_not_before,created_at) ELSE created_at END" : "created_at";
        return jdbc.query("SELECT status,count(*) AS total,min(" + ageColumn + ") AS oldest FROM tbl_media_asset WHERE status IN ("
                + statuses + ") GROUP BY status", rs -> {
            long pending = 0, failed = 0;
            Instant oldest = null, now = clock.instant();
            while (rs.next()) {
                if ("FAILED".equals(rs.getString("status"))) { failed += rs.getLong("total"); continue; }
                pending += rs.getLong("total");
                LocalDateTime time = rs.getObject("oldest", LocalDateTime.class);
                if (time != null && (oldest == null || time.toInstant(ZoneOffset.UTC).isBefore(oldest))) oldest = time.toInstant(ZoneOffset.UTC);
            }
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("pending", (double) pending);
            if (!cleanup) metrics.put("failed", (double) failed);
            long age = oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds());
            if (oldest != null) metrics.put("oldestPendingAgeSeconds", (double) age);
            // Historic terminal failures remain visible but do not permanently degrade a recovered service.
            Status status = age >= properties.getBacklogAgeThresholdSeconds() ? Status.DEGRADED : Status.UP;
            return new Reading(status, reason(status), now, metrics);
        });
    }

    private Reading worker() {
        if (properties.getMediaWorkerHealthFile().isBlank()) return Reading.unknown(ReasonCode.NOT_CONFIGURED);
        try (InputStream input = Files.newInputStream(Path.of(properties.getMediaWorkerHealthFile()))) {
            byte[] bytes = input.readNBytes(33);
            if (bytes.length > 32) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
            String epoch = new String(bytes, StandardCharsets.US_ASCII).trim();
            if (!epoch.matches("[0-9]{1,12}")) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
            Instant measured = Instant.ofEpochSecond(Long.parseLong(epoch)), now = clock.instant();
            if (measured.isAfter(now)) return Reading.unknown(ReasonCode.INVALID_MEASUREMENT);
            Status status = Duration.between(measured, now).getSeconds() > properties.getStaleAfterSeconds() ? Status.STALE : Status.UP;
            return new Reading(status, reason(status), measured, Map.of());
        } catch (Exception unavailable) { return Reading.unknown(ReasonCode.PROBE_FAILED); }
    }
}
