package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@ApplicationScoped
public class LogRotationResolver {

    @Inject
    Config config;

    @ConfigProperty(name = "container.log-rotation.enabled", defaultValue = "true")
    boolean logRotationEnabled;

    @ConfigProperty(name = "container.log-rotation.max-size", defaultValue = "10m")
    String logRotationMaxSize;

    @ConfigProperty(name = "container.log-rotation.max-files", defaultValue = "3")
    String logRotationMaxFiles;

    public boolean isEnabled(String repository) {
        return config.getOptionalValue(
                "repository.log-rotation.enabled." + repository, Boolean.class)
                .orElse(logRotationEnabled);
    }

    public void apply(HostConfig hostConfig, String repository) {
        if (!isEnabled(repository)) {
            return;
        }
        String maxSize = config.getOptionalValue(
                "repository.log-rotation.max-size." + repository, String.class)
                .orElse(logRotationMaxSize);
        String maxFiles = config.getOptionalValue(
                "repository.log-rotation.max-files." + repository, String.class)
                .orElse(logRotationMaxFiles);
        hostConfig.withLogConfig(new LogConfig(
                LogConfig.LoggingType.JSON_FILE,
                Map.of("max-size", maxSize, "max-file", maxFiles)));
    }
}
