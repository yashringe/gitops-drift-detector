package io.github.yashringe.drift.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.yashringe.drift.cluster.KubernetesLiveStateFetcher;
import io.github.yashringe.drift.cluster.LiveStateFetcher;
import io.github.yashringe.drift.core.DriftDetector;
import io.github.yashringe.drift.diff.DriftCalculator;
import io.github.yashringe.drift.manifest.ManifestLoader;
import io.github.yashringe.drift.report.MarkdownReportWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class DriftConfiguration {

    /**
     * Uses the same lookup as kubectl: $KUBECONFIG or ~/.kube/config on a laptop or CI runner,
     * the mounted service account token when running inside a pod.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    LiveStateFetcher liveStateFetcher(KubernetesClient client) {
        return new KubernetesLiveStateFetcher(client);
    }

    @Bean
    DriftDetector driftDetector(LiveStateFetcher fetcher, DriftProperties properties) {
        return new DriftDetector(
                new ManifestLoader(properties.defaultNamespace()),
                fetcher,
                new DriftCalculator(properties.ignoreFields()),
                new DriftDetector.Options(properties.labelKey(), properties.labelValue()),
                Clock.systemUTC());
    }

    @Bean
    MarkdownReportWriter markdownReportWriter() {
        return new MarkdownReportWriter();
    }
}
