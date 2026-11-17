package io.github.yashringe.drift.cli;

import io.github.yashringe.drift.config.DriftProperties;
import io.github.yashringe.drift.core.DriftDetector;
import io.github.yashringe.drift.report.DriftReport;
import io.github.yashringe.drift.report.ReportRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot check for CI pipelines. The exit code is what a pipeline acts on:
 * 0 = in sync, 1 = drift found, 2 = the check itself failed (bad manifest, no cluster access).
 */
@Component
@ConditionalOnProperty(name = "drift.mode", havingValue = "cli", matchIfMissing = true)
public class DriftCheckRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DriftCheckRunner.class);

    private final DriftDetector detector;
    private final ReportRenderer renderer;
    private final DriftProperties properties;
    private int exitCode = 2;

    public DriftCheckRunner(DriftDetector detector, ReportRenderer renderer, DriftProperties properties) {
        this.detector = detector;
        this.renderer = renderer;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            DriftReport report = detector.check(Path.of(properties.manifests()));
            String rendered = renderer.render(report, properties.format());
            if (properties.output() == null || properties.output().isBlank()) {
                System.out.println(rendered);
            } else {
                Files.writeString(Path.of(properties.output()), rendered, StandardCharsets.UTF_8);
                log.info("Report written to {}", properties.output());
            }
            log.info("Drift check finished: {}", report.counts());
            exitCode = report.exitCode();
        } catch (IOException | RuntimeException e) {
            log.error("Drift check failed: {}", e.getMessage());
            exitCode = 2;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
