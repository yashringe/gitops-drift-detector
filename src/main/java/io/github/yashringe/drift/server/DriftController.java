package io.github.yashringe.drift.server;

import io.github.yashringe.drift.config.DriftProperties.Format;
import io.github.yashringe.drift.report.ReportRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Latest report in server mode. 503 until the first check has finished. */
@RestController
@ConditionalOnProperty(name = "drift.mode", havingValue = "server")
public class DriftController {

    private final DriftMonitor monitor;
    private final ReportRenderer renderer;

    public DriftController(DriftMonitor monitor, ReportRenderer renderer) {
        this.monitor = monitor;
        this.renderer = renderer;
    }

    @GetMapping(value = "/api/drift", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> json() {
        return monitor.lastReport()
                .map(r -> ResponseEntity.ok(renderer.render(r, Format.JSON)))
                .orElseGet(() -> ResponseEntity.status(503).body("{\"error\":\"first check still running\"}"));
    }

    @GetMapping(value = "/api/drift.md", produces = "text/markdown")
    public ResponseEntity<String> markdown() {
        return monitor.lastReport()
                .map(r -> ResponseEntity.ok(renderer.render(r, Format.MARKDOWN)))
                .orElseGet(() -> ResponseEntity.status(503).body("first check still running"));
    }
}
