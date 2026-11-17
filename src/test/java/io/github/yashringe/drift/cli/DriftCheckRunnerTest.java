package io.github.yashringe.drift.cli;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.yashringe.drift.cluster.LiveStateFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application in CLI mode. The cluster is a Mockito mock that knows no
 * objects, so every manifest is reported as missing and the exit code must be 1.
 */
@SpringBootTest(properties = {
        "drift.manifests=src/test/resources/manifests/app",
        "drift.output=target/cli-test-report.md"})
class DriftCheckRunnerTest {

    @MockitoBean
    private KubernetesClient kubernetesClient;

    @MockitoBean
    private LiveStateFetcher liveStateFetcher;

    @Autowired
    private DriftCheckRunner runner;

    @Test
    void writesReportAndSetsExitCode() throws IOException {
        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(Files.readString(Path.of("target/cli-test-report.md")))
                .contains("## GitOps drift report")
                .contains("| Missing in cluster | 4 |");
    }
}
