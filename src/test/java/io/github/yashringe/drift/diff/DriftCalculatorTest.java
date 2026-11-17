package io.github.yashringe.drift.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriftCalculatorTest {

    private final DriftCalculator calculator = new DriftCalculator(List.of());

    @Test
    void ignoresDefaultsAndServerMetadata() {
        Map<String, Object> desired = deployment("nginx:1.27-alpine", 2, "0.1");
        Map<String, Object> live = deployment("nginx:1.27-alpine", 2, "100m");
        meta(live).put("uid", "8f6c");
        meta(live).put("resourceVersion", "4711");
        meta(live).put("annotations", Map.of("deployment.kubernetes.io/revision", "3"));
        spec(live).put("revisionHistoryLimit", 10);
        live.put("status", Map.of("readyReplicas", 2));
        container(live).put("imagePullPolicy", "IfNotPresent");

        assertThat(calculator.compare(desired, live)).isEmpty();
    }

    @Test
    void reportsChangedFieldsWithReadablePaths() {
        Map<String, Object> desired = deployment("nginx:1.27-alpine", 2, "100m");
        Map<String, Object> live = deployment("nginx:1.27.1", 5, "250m");

        assertThat(calculator.compare(desired, live)).containsExactlyInAnyOrder(
                new FieldDrift("spec.replicas", "2", "5"),
                new FieldDrift("spec.template.spec.containers[name=web].image", "nginx:1.27-alpine", "nginx:1.27.1"),
                new FieldDrift("spec.template.spec.containers[name=web].resources.requests.cpu", "100m", "250m"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchesContainersByNameNotPosition() {
        Map<String, Object> desired = deployment("nginx:1.27-alpine", 2, "100m");
        Map<String, Object> live = deployment("nginx:1.27-alpine", 2, "100m");
        List<Object> containers = (List<Object>) podSpec(live).get("containers");
        containers.add(0, Map.of("name", "istio-proxy", "image", "istio/proxyv2"));

        assertThat(calculator.compare(desired, live)).isEmpty();
    }

    @Test
    void reportsMissingListElement() {
        Map<String, Object> desired = Map.of("spec", Map.of("ports", List.of(
                Map.of("name", "http", "port", 80), Map.of("name", "metrics", "port", 9090))));
        Map<String, Object> live = Map.of("spec", Map.of("ports", List.of(Map.of("name", "http", "port", 80))));

        assertThat(calculator.compare(desired, live)).singleElement().satisfies(drift -> {
            assertThat(drift.path()).isEqualTo("spec.ports[name=metrics]");
            assertThat(drift.desired()).contains("port: 9090");
            assertThat(drift.live()).isNull();
        });
    }

    @Test
    void comparesPlainListsInOrder() {
        Map<String, Object> desired = Map.of("args", List.of("--port", 8080, "--verbose"));

        assertThat(calculator.compare(desired, Map.of("args", List.of("--port", "8080", "--verbose")))).isEmpty();
        assertThat(calculator.compare(desired, Map.of("args", List.of("--verbose", "--port", "8080")))).hasSize(1);
    }

    @Test
    void treatsNumbersAndNumericStringsAlikeButNotVersions() {
        assertThat(calculator.compare(Map.of("port", 8080), Map.of("port", "8080"))).isEmpty();
        assertThat(calculator.compare(Map.of("v", "1.10"), Map.of("v", "1.1"))).hasSize(1);
    }

    @Test
    void writesAnnotationKeysInBrackets() {
        Map<String, Object> desired = Map.of("metadata", Map.of("annotations", Map.of("example.com/owner", "team-a")));
        Map<String, Object> live = Map.of("metadata", Map.of("annotations", Map.of("example.com/owner", "team-b")));

        assertThat(calculator.compare(desired, live)).containsExactly(
                new FieldDrift("metadata.annotations[\"example.com/owner\"]", "team-a", "team-b"));
    }

    @Test
    void reportsFieldMissingInCluster() {
        assertThat(calculator.compare(Map.of("data", Map.of("A", "1", "B", "2")), Map.of("data", Map.of("A", "1"))))
                .containsExactly(new FieldDrift("data.B", "2", null));
    }

    @Test
    void canIgnoreFieldsOwnedByOtherControllers() {
        DriftCalculator withHpa = new DriftCalculator(List.of("spec.replicas"));

        assertThat(withHpa.compare(deployment("x", 2, "1"), deployment("x", 7, "1"))).isEmpty();
    }

    @Test
    void convertsSecretStringDataToBase64Data() {
        Map<String, Object> secret = Map.of("kind", "Secret", "stringData", Map.of("token", "s3cr3t"));

        Map<String, Object> normalised = DriftCalculator.normaliseSecret(secret);

        assertThat(normalised).doesNotContainKey("stringData");
        assertThat(normalised.get("data")).isEqualTo(Map.of("token", "czNjcjN0"));
    }

    // --- helpers building a mutable Deployment map ---

    private static Map<String, Object> deployment(String image, int replicas, String cpu) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "web");
        container.put("image", image);
        container.put("resources", new LinkedHashMap<>(Map.of("requests", new LinkedHashMap<>(Map.of("cpu", cpu)))));
        List<Object> containers = new ArrayList<>(List.of(container));

        Map<String, Object> podSpec = new LinkedHashMap<>(Map.of("containers", containers));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("replicas", replicas);
        spec.put("template", new LinkedHashMap<>(Map.of("spec", podSpec)));

        Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put("apiVersion", "apps/v1");
        deployment.put("kind", "Deployment");
        deployment.put("metadata", new LinkedHashMap<>(Map.of("name", "web", "namespace", "demo")));
        deployment.put("spec", spec);
        return deployment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> d) {
        return (Map<String, Object>) d.get("metadata");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(Map<String, Object> d) {
        return (Map<String, Object>) d.get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podSpec(Map<String, Object> d) {
        return (Map<String, Object>) ((Map<String, Object>) spec(d).get("template")).get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> container(Map<String, Object> d) {
        return (Map<String, Object>) ((List<Object>) podSpec(d).get("containers")).get(0);
    }
}
