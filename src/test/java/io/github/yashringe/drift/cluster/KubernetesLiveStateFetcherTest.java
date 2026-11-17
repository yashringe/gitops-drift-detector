package io.github.yashringe.drift.cluster;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.yashringe.drift.core.DriftDetector;
import io.github.yashringe.drift.diff.DriftCalculator;
import io.github.yashringe.drift.diff.FieldDrift;
import io.github.yashringe.drift.manifest.ManifestLoader;
import io.github.yashringe.drift.manifest.ResourceKey;
import io.github.yashringe.drift.report.DriftReport;
import io.github.yashringe.drift.report.DriftReport.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real Fabric8 client against Fabric8's mock API server in CRUD mode, which stores
 * objects like a tiny in-memory Kubernetes API.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesLiveStateFetcherTest {

    private static final Path APP = Path.of("src/test/resources/manifests/app");

    KubernetesClient client;
    private KubernetesLiveStateFetcher fetcher;

    @BeforeEach
    void applyManifests() throws IOException {
        fetcher = new KubernetesLiveStateFetcher(client);
        try (InputStream namespace = Files.newInputStream(APP.resolve("00-namespace.yaml"));
             InputStream web = Files.newInputStream(APP.resolve("web.yaml"))) {
            createAll(client.load(namespace).items());
            createAll(client.load(web).items());
        }
    }

    @Test
    void fetchesNamespacedAndClusterScopedObjects() {
        assertThat(fetcher.fetch(new ResourceKey("apps/v1", "Deployment", "demo", "web")))
                .hasValueSatisfying(live -> assertThat(live).containsKey("spec"));
        assertThat(fetcher.fetch(new ResourceKey("v1", "Namespace", null, "demo"))).isPresent();
        assertThat(fetcher.fetch(new ResourceKey("v1", "ConfigMap", "demo", "nope"))).isEmpty();
    }

    @Test
    void findsLabelledObjects() {
        client.configMaps().inNamespace("demo").resource(new ConfigMapBuilder()
                .withNewMetadata().withName("manual-hotfix").withNamespace("demo")
                .addToLabels("app.kubernetes.io/managed-by", "gitops").endMetadata()
                .addToData("FLAG", "on").build()).create();

        List<ResourceKey> found = fetcher.listLabelled(
                List.of(new ResourceKey("v1", "ConfigMap", null, null)), List.of("demo"),
                "app.kubernetes.io/managed-by", "gitops");

        assertThat(found).containsExactlyInAnyOrder(
                new ResourceKey("v1", "ConfigMap", "demo", "web-config"),
                new ResourceKey("v1", "ConfigMap", "demo", "manual-hotfix"));
    }

    @Test
    void endToEndAgainstMockApiServer() {
        DriftDetector detector = new DriftDetector(new ManifestLoader("default"), fetcher,
                new DriftCalculator(List.of()),
                new DriftDetector.Options(null, null), Clock.systemUTC());

        assertThat(detector.check(APP).inSync()).as("freshly applied manifests").isTrue();

        // Someone "fixes" production by hand...
        client.configMaps().inNamespace("demo").withName("web-config")
                .edit(cm -> {
                    cm.getData().put("GREETING", "hallo");
                    return cm;
                });
        client.services().inNamespace("demo").withName("web").delete();

        DriftReport report = detector.check(APP);

        assertThat(report.counts()).containsEntry(Status.DRIFTED, 1L).containsEntry(Status.MISSING, 1L);
        assertThat(report.withStatus(Status.DRIFTED).getFirst().drifts())
                .containsExactly(new FieldDrift("data.GREETING", "hello", "hallo"));
        assertThat(report.exitCode()).isEqualTo(1);
    }

    private void createAll(List<HasMetadata> items) {
        for (HasMetadata item : items) {
            // createOr(update) keeps the setup working whether the mock server is shared or per test.
            client.resource(item).createOr(NonDeletingOperation::update);
        }
    }
}
