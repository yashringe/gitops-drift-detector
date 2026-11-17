package io.github.yashringe.drift;

import io.github.yashringe.drift.config.DriftProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GitOpsDriftDetectorApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(GitOpsDriftDetectorApplication.class, args);
        // In CLI mode the check already ran inside run(); pass its result on as the process exit code.
        if (context.getBean(DriftProperties.class).mode() == DriftProperties.Mode.CLI) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
