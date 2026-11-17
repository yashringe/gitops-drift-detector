package io.github.yashringe.drift.manifest;

/**
 * Identifies one Kubernetes object. {@code namespace} is {@code null} for cluster-scoped kinds
 * such as Namespace or ClusterRole.
 */
public record ResourceKey(String apiVersion, String kind, String namespace, String name)
        implements Comparable<ResourceKey> {

    public boolean isNamespaced() {
        return namespace != null;
    }

    /** e.g. {@code apps/v1 Deployment demo/web}. */
    public String display() {
        return apiVersion + " " + kind + " " + (namespace == null ? "" : namespace + "/") + name;
    }

    @Override
    public int compareTo(ResourceKey other) {
        return display().compareTo(other.display());
    }

    @Override
    public String toString() {
        return display();
    }
}
