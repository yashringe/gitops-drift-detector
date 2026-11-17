package io.github.yashringe.drift.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Settings under {@code drift.*}. On the command line: {@code --drift.manifests=k8s/ --drift.format=json}.
 *
 * @param mode             {@code cli}: check once and exit with 0/1/2; {@code server}: check on a schedule and expose metrics
 * @param manifests        folder with the rendered manifests
 * @param output           file to write the report to; empty means standard output
 * @param ignoreFields     path prefixes that are allowed to differ, e.g. {@code spec.replicas} with an HPA
 * @param managedByLabel   {@code key=value}; objects in the cluster with this label but no manifest are reported. Empty = off.
 * @param interval         how often server mode checks
 */
@Validated
@ConfigurationProperties(prefix = "drift")
public record DriftProperties(
        @DefaultValue("cli") Mode mode,
        @NotBlank String manifests,
        @DefaultValue("default") String defaultNamespace,
        @DefaultValue("markdown") Format format,
        String output,
        List<String> ignoreFields,
        String managedByLabel,
        @DefaultValue("5m") Duration interval) {

    public enum Mode { CLI, SERVER }

    public enum Format { MARKDOWN, JSON }

    public DriftProperties {
        ignoreFields = ignoreFields == null ? List.of() : List.copyOf(ignoreFields);
    }

    public String labelKey() {
        return labelPart(0);
    }

    public String labelValue() {
        return labelPart(1);
    }

    private String labelPart(int index) {
        if (managedByLabel == null || managedByLabel.isBlank()) {
            return null;
        }
        String[] parts = managedByLabel.split("=", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("drift.managed-by-label must look like key=value, got: " + managedByLabel);
        }
        return parts[index].strip();
    }
}
