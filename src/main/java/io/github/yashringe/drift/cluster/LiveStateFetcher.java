package io.github.yashringe.drift.cluster;

import io.github.yashringe.drift.manifest.ResourceKey;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only access to the cluster. The detector only depends on this interface, so its logic
 * is tested with an in-memory fake and the Kubernetes client is tested separately.
 */
public interface LiveStateFetcher {

    /** The live object as a JSON-like map, or empty if it does not exist. */
    Optional<Map<String, Object>> fetch(ResourceKey key);

    /**
     * Objects of the given kinds and namespaces that carry the label. Used to find objects that
     * claim to be managed from Git but have no manifest any more.
     *
     * @param kinds pairs of apiVersion and kind, as {@link ResourceKey} with null name/namespace
     */
    List<ResourceKey> listLabelled(Collection<ResourceKey> kinds, Collection<String> namespaces,
                                   String labelKey, String labelValue);

    /** Name of the cluster or kube context, for the report header. */
    String clusterName();
}
