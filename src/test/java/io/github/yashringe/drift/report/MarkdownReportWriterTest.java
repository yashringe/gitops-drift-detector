package io.github.yashringe.drift.report;

import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.manifest.ResourceKey;
import io.github.yashringe.drift.report.DriftReport.ResourceResult;
import io.github.yashringe.drift.report.DriftReport.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportWriterTest {

    private static final Instant AT = Instant.parse("2026-10-28T10:30:00Z");
    private final MarkdownReportWriter writer = new MarkdownReportWriter();

    @Test
    void summarisesInSyncClusterInOneLine() {
        DriftReport report = new DriftReport("kind-ci", "k8s", AT, List.of(
                new ResourceResult(new ResourceKey("v1", "ConfigMap", "demo", "a"), Status.IN_SYNC, "k8s/a.yaml", List.of(), null)));

        String md = writer.write(report);

        assertThat(md).startsWith(MarkdownReportWriter.MARKER)
                .contains("Cluster `kind-ci` compared with `k8s` at 2026-10-28 10:30 UTC.")
                .contains("**No drift.** All 1 objects match");
    }

    @Test
    void listsDriftedFieldsAndEscapesTableCharacters() {
        ResourceKey deployment = new ResourceKey("apps/v1", "Deployment", "demo", "web");
        DriftReport report = new DriftReport("kind-ci", "k8s", AT, List.of(
                new ResourceResult(deployment, Status.DRIFTED, "k8s/web.yaml", List.of(
                        new FieldDrift("spec.replicas", "2", "5"),
                        new FieldDrift("spec.template.spec.containers[name=web].args", "[a|b]", null)), null),
                new ResourceResult(new ResourceKey("v1", "Service", "demo", "web"), Status.MISSING, "k8s/web.yaml",
                        List.of(), null)));

        String md = writer.write(report);

        assertThat(md)
                .contains("| Drifted | 1 |")
                .contains("| Missing in cluster | 1 |")
                .contains("<code>apps/v1 Deployment demo/web</code>: 2 fields")
                .contains("| `spec.replicas` | `2` | `5` |")
                .contains("| `[a\\|b]` | _missing_ |")
                .contains("- `v1 Service demo/web` (from `k8s/web.yaml`)");
    }
}
