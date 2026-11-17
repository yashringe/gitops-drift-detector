package io.github.yashringe.drift.server;

import io.github.yashringe.drift.config.DriftProperties;
import io.github.yashringe.drift.core.DriftDetector;
import io.github.yashringe.drift.report.DriftReport;
import io.github.yashringe.drift.report.DriftReport.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server mode: checks on a fixed delay and publishes the result as Prometheus metrics, so an
 * alert can fire when someone changes the cluster by hand.
 * <pre>
 *   gitops_drift_objects{status="drifted"}  2
 *   gitops_drift_last_success_seconds        1.7616e9
 *   gitops_drift_check_failures_total        0
 * </pre>
 * Example alert: {@code gitops_drift_objects{status=~"drifted|missing"} > 0 for 15m}.
 */
@Component
@ConditionalOnProperty(name = "drift.mode", havingValue = "server")
public class DriftMonitor implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DriftMonitor.class);

    private final DriftDetector detector;
    private final DriftProperties properties;
    private final MeterRegistry registry;
    private final AtomicReference<DriftReport> lastReport = new AtomicReference<>();
    private volatile double lastSuccessEpochSeconds;

    public DriftMonitor(DriftDetector detector, DriftProperties properties, MeterRegistry registry) {
        this.detector = detector;
        this.properties = properties;
        this.registry = registry;
        for (Status status : Status.values()) {
            Gauge.builder("gitops.drift.objects", lastReport, ref -> count(ref.get(), status))
                    .description("Objects per drift status in the last successful check")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("gitops.drift.last.success", this, m -> m.lastSuccessEpochSeconds)
                .description("Unix time of the last successful check")
                .baseUnit("seconds")
                .register(registry);
    }

    /** Registered in code so the interval comes from the typed {@code drift.interval} property ("5m", "30s", ...). */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::check, properties.interval(), Duration.ofSeconds(5)));
    }

    public void check() {
        try {
            DriftReport report = detector.check(Path.of(properties.manifests()));
            lastReport.set(report);
            lastSuccessEpochSeconds = report.checkedAt().getEpochSecond();
            log.info("Drift check: {}", report.counts());
        } catch (RuntimeException e) {
            registry.counter("gitops.drift.check.failures").increment();
            log.error("Drift check failed: {}", e.getMessage());
        }
    }

    public Optional<DriftReport> lastReport() {
        return Optional.ofNullable(lastReport.get());
    }

    private static double count(DriftReport report, Status status) {
        return report == null ? Double.NaN : report.counts().get(status);
    }
}
