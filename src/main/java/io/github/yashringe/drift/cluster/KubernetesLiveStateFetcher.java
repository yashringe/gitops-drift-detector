package io.github.yashringe.drift.cluster;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.github.yashringe.drift.manifest.ManifestLoader;
import io.github.yashringe.drift.manifest.ResourceKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads objects through the Fabric8 Kubernetes client. It uses the generic (untyped) API so the
 * same code works for Deployments, Services and custom resources alike.
 * <p>
 * Only {@code get} and {@code list} are used, so a read-only ClusterRole is enough
 * (see {@code deploy/k8s/rbac.yaml}).
 */
public class KubernetesLiveStateFetcher implements LiveStateFetcher {

    private final KubernetesClient client;

    public KubernetesLiveStateFetcher(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public Optional<Map<String, Object>> fetch(ResourceKey key) {
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> op =
                client.genericKubernetesResources(key.apiVersion(), key.kind());
        GenericKubernetesResource live = key.isNamespaced()
                ? op.inNamespace(key.namespace()).withName(key.name()).get()
                : op.withName(key.name()).get();
        return Optional.ofNullable(live).map(this::toMap);
    }

    @Override
    public List<ResourceKey> listLabelled(Collection<ResourceKey> kinds, Collection<String> namespaces,
                                          String labelKey, String labelValue) {
        List<ResourceKey> found = new ArrayList<>();
        for (ResourceKey kind : kinds) {
            var op = client.genericKubernetesResources(kind.apiVersion(), kind.kind());
            if (ManifestLoader.isClusterScoped(kind.kind())) {
                addAll(found, kind, op.withLabel(labelKey, labelValue).list());
            } else {
                for (String namespace : namespaces) {
                    addAll(found, kind, op.inNamespace(namespace).withLabel(labelKey, labelValue).list());
                }
            }
        }
        return found;
    }

    @Override
    public String clusterName() {
        var context = client.getConfiguration().getCurrentContext();
        if (context != null && context.getName() != null) {
            return context.getName();
        }
        return client.getMasterUrl().getHost();
    }

    private static void addAll(List<ResourceKey> out, ResourceKey kind, GenericKubernetesResourceList list) {
        for (GenericKubernetesResource item : list.getItems()) {
            out.add(new ResourceKey(kind.apiVersion(), kind.kind(), item.getMetadata().getNamespace(),
                    item.getMetadata().getName()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(GenericKubernetesResource resource) {
        return client.getKubernetesSerialization().convertValue(resource, Map.class);
    }
}
