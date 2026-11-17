package io.github.yashringe.drift.manifest;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads every {@code .yaml}/{@code .yml} file below a directory. Files may contain several
 * documents separated by {@code ---} and {@code kind: List} wrappers (as produced by
 * {@code kubectl get -o yaml}).
 * <p>
 * For Helm or Kustomize, render first ({@code helm template ... > rendered/all.yaml} or
 * {@code kustomize build > ...}) and point the loader at the output. That way the tool compares
 * exactly what would be applied, without reimplementing templating.
 * <p>
 * SnakeYAML's {@link SafeConstructor} only builds maps, lists and scalars, so a manifest cannot
 * make the loader instantiate arbitrary Java classes.
 */
public final class ManifestLoader {

    /** Kinds without a namespace. Objects of other kinds without metadata.namespace get the default namespace. */
    static final Set<String> CLUSTER_SCOPED_KINDS = Set.of(
            "Namespace", "Node", "PersistentVolume", "StorageClass", "ClusterRole", "ClusterRoleBinding",
            "CustomResourceDefinition", "PriorityClass", "IngressClass", "RuntimeClass", "APIService",
            "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration", "CSIDriver", "VolumeSnapshotClass",
            "ClusterIssuer");

    private final String defaultNamespace;

    public static boolean isClusterScoped(String kind) {
        return CLUSTER_SCOPED_KINDS.contains(kind);
    }

    public ManifestLoader(String defaultNamespace) {
        this.defaultNamespace = defaultNamespace == null || defaultNamespace.isBlank() ? "default" : defaultNamespace;
    }

    public List<DesiredResource> load(Path root) {
        if (!Files.exists(root)) {
            throw new ManifestException("Manifest path does not exist: " + root);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("(?i).*\\.ya?ml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<DesiredResource> resources = new ArrayList<>();
        Map<ResourceKey, Path> seen = new HashMap<>();
        for (Path file : files) {
            for (DesiredResource resource : loadFile(file)) {
                Path earlier = seen.putIfAbsent(resource.key(), file);
                if (earlier != null) {
                    throw new ManifestException(resource.key() + " is defined twice: in " + earlier + " and " + file);
                }
                resources.add(resource);
            }
        }
        return resources;
    }

    List<DesiredResource> loadFile(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<DesiredResource> resources = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (Object document : yaml.loadAll(reader)) {
                collect(document, file, resources);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (YAMLException e) {
            throw new ManifestException("Invalid YAML in " + file + ": " + e.getMessage(), e);
        }
        return resources;
    }

    @SuppressWarnings("unchecked")
    private void collect(Object document, Path file, List<DesiredResource> out) {
        if (document == null) {
            return; // empty document, e.g. a trailing "---"
        }
        if (!(document instanceof Map<?, ?> map)) {
            throw new ManifestException("Expected a YAML mapping in " + file + " but found " + document.getClass().getSimpleName());
        }
        Map<String, Object> body = (Map<String, Object>) map;
        String kind = string(body.get("kind"));
        if ("List".equals(kind) || (kind != null && kind.endsWith("List") && body.get("items") instanceof List<?>)) {
            for (Object item : (List<Object>) body.getOrDefault("items", List.of())) {
                collect(item, file, out);
            }
            return;
        }
        String apiVersion = string(body.get("apiVersion"));
        Map<String, Object> metadata = body.get("metadata") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String name = string(metadata.get("name"));
        if (apiVersion == null || kind == null || name == null) {
            throw new ManifestException("Object in " + file + " needs apiVersion, kind and metadata.name");
        }
        String namespace = isClusterScoped(kind) ? null
                : metadata.get("namespace") == null ? defaultNamespace : string(metadata.get("namespace"));
        out.add(new DesiredResource(new ResourceKey(apiVersion, kind, namespace, name), body, file));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
