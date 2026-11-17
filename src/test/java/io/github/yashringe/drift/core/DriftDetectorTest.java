package io.github.yashringe.drift.core;

import io.github.yashringe.drift.cluster.LiveStateFetcher;
import io.github.yashringe.drift.diff.DriftCalculator;
import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.manifest.DesiredResource;
import io.github.yashringe.drift.manifest.ManifestLoader;
import io.github.yashringe.drift.manifest.ResourceKey;
import io.github.yashringe.drift.report.DriftReport;
import io.github.yashringe.drift.report.DriftReport.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DriftDetectorTest {

    private static final Path APP = Path.of("src/test/resources/manifests/app");
    private static final Path ALL = Path.of("src/test/resources/manifests");
    private static final ResourceKey CONFIG = new ResourceKey("v1", "ConfigMap", "demo", "web-config");
    private static final ResourceKey SERVICE = new ResourceKey("v1", "Service", "demo", "web");

    private final ManifestLoader loader = new ManifestLoader("default");
    private FakeCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new FakeCluster();
        for (DesiredResource r : loader.load(ALL)) {
            cluster.objects.put(r.key(), deepCopy(r.body()));
        }
    }

    @Test
    void clusterThatMatchesGitIsInSync() {
        DriftReport report = detector(null, null).check(APP);

        assertThat(report.inSync()).isTrue();
        assertThat(report.exitCode()).isZero();
        assertThat(report.cluster()).isEqualTo("fake");
    }

    @Test
    void reportsDriftedMissingAndUnmanagedObjects() {
        data(cluster.objects.get(CONFIG)).put("GREETING", "hallo");
        cluster.objects.remove(SERVICE);
        ResourceKey hotfix = new ResourceKey("v1", "ConfigMap", "demo", "manual-hotfix");
        cluster.labelled.addAll(List.of(CONFIG, hotfix));

        DriftReport report = detector("app.kubernetes.io/managed-by", "gitops").check(APP);

        assertThat(report.counts()).containsEntry(Status.DRIFTED, 1L).containsEntry(Status.MISSING, 1L)
                .containsEntry(Status.NOT_IN_GIT, 1L).containsEntry(Status.IN_SYNC, 2L);
        assertThat(report.withStatus(Status.DRIFTED).getFirst().drifts())
                .containsExactly(new FieldDrift("data.GREETING", "hello", "hallo"));
        assertThat(report.withStatus(Status.NOT_IN_GIT).getFirst().resource()).isEqualTo(hotfix);
        assertThat(report.exitCode()).isEqualTo(1);
    }

    @Test
    void neverShowsSecretValues() {
        ResourceKey secret = new ResourceKey("v1", "Secret", "demo", "api-token");
        Map<String, Object> live = new LinkedHashMap<>(Map.of("apiVersion", "v1", "kind", "Secret",
                "metadata", Map.of("name", "api-token", "namespace", "demo"),
                "type", "Opaque",
                "data", Map.of("token", "Y2hhbmdlZA==")));
        cluster.objects.put(secret, live);

        DriftReport report = detector(null, null).check(ALL);

        assertThat(report.withStatus(Status.DRIFTED)).singleElement().satisfies(r -> {
            assertThat(r.resource()).isEqualTo(secret);
            assertThat(r.drifts()).containsExactly(new FieldDrift("data.token", "<redacted>", "<redacted>"));
        });
    }

    @Test
    void oneUnreadableObjectDoesNotStopTheCheck() {
        cluster.forbidden = SERVICE;

        DriftReport report = detector(null, null).check(APP);

        assertThat(report.withStatus(Status.ERROR)).singleElement()
                .satisfies(r -> assertThat(r.error()).contains("forbidden"));
        assertThat(report.withStatus(Status.IN_SYNC)).hasSize(3);
        assertThat(report.exitCode()).isEqualTo(2);
    }

    private DriftDetector detector(String labelKey, String labelValue) {
        return new DriftDetector(loader, cluster, new DriftCalculator(List.of()),
                new DriftDetector.Options(labelKey, labelValue),
                Clock.fixed(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC));
    }

    /** In-memory stand-in for the Kubernetes API. */
    static final class FakeCluster implements LiveStateFetcher {
        final Map<ResourceKey, Map<String, Object>> objects = new HashMap<>();
        final List<ResourceKey> labelled = new ArrayList<>();
        ResourceKey forbidden;

        @Override
        public Optional<Map<String, Object>> fetch(ResourceKey key) {
            if (key.equals(forbidden)) {
                throw new IllegalStateException("services \"web\" is forbidden: User cannot get resource");
            }
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public List<ResourceKey> listLabelled(Collection<ResourceKey> kinds, Collection<String> namespaces,
                                              String labelKey, String labelValue) {
            return labelled;
        }

        @Override
        public String clusterName() {
            return "fake";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> configMap) {
        return (Map<String, Object>) configMap.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, v instanceof Map<?, ?> m ? deepCopy((Map<String, Object>) m)
                : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return copy;
    }
}
