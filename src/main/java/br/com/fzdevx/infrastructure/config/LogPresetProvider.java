package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.LogPreset;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class LogPresetProvider {

    private List<LogPreset> presets;

    // ---- WildFly ----
    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.log-line-regex",
            defaultValue = "^(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(?<level>\\w+)\\s+\\[(?<logger>[^\\]]+)\\]\\s+\\((?<thread>[^)]+)\\)\\s+(?<message>.*)$")
    String wildflyLogLineRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.timestamp-format",
            defaultValue = "yyyy-MM-dd HH:mm:ss,SSS")
    String wildflyTimestampFormat;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.api-call-regex",
            defaultValue = "^(?<endpoint>\\w+(?:WS|Resource)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$")
    String wildflyApiCallRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.job-start-regex",
            defaultValue = "^Job \\[(?<jobName>.+?)\\] vai ser disparado pelo trigger \\[(?<trigger>.+?)\\]")
    String wildflyJobStartRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.job-end-regex",
            defaultValue = "^Job \\[(?<jobName>.+?)\\] (?:executou em .+ and reports|execucao falhou com o erro): (?<result>.+)$")
    String wildflyJobEndRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.failure-regex",
            defaultValue = "ORDEM (?<entityId>ORDER \\d+) FALHA AO INICIAR (?<reason>\\w+):")
    String wildflyFailureRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.sensitive-field-names",
            defaultValue = "token,senha,password,secret,authorization")
    String wildflySensitiveFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.custom-fields")
    Optional<String> wildflyCustomFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.wildfly.critical-issue-exclusions")
    Optional<String> wildflyCriticalIssueExclusions;

    // ---- Quarkus ----
    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.log-line-regex",
            defaultValue = "^(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(?<level>\\w+)\\s+\\[(?<logger>[^\\]]+)\\]\\s+\\((?<thread>[^)]+)\\)\\s+(?<message>.*)$")
    String quarkusLogLineRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.timestamp-format",
            defaultValue = "yyyy-MM-dd HH:mm:ss,SSS")
    String quarkusTimestampFormat;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.api-call-regex",
            defaultValue = "^(?<endpoint>\\w+(?:WS|Resource|Controller)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$")
    String quarkusApiCallRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.job-start-regex")
    Optional<String> quarkusJobStartRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.job-end-regex")
    Optional<String> quarkusJobEndRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.failure-regex")
    Optional<String> quarkusFailureRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.sensitive-field-names",
            defaultValue = "token,password,secret,authorization")
    String quarkusSensitiveFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.custom-fields")
    Optional<String> quarkusCustomFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.quarkus.critical-issue-exclusions")
    Optional<String> quarkusCriticalIssueExclusions;

    // ---- Spring Boot ----
    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.log-line-regex",
            defaultValue = "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2})\\s+(?<level>\\w+)\\s+\\d+\\s+---\\s+\\[(?<thread>[^\\]]+)\\]\\s+(?<logger>\\S+)\\s+:\\s+(?<message>.*)$")
    String springBootLogLineRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.timestamp-format",
            defaultValue = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    String springBootTimestampFormat;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.api-call-regex",
            defaultValue = "^(?<endpoint>\\w+(?:Controller|Resource|Service)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$")
    String springBootApiCallRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.job-start-regex")
    Optional<String> springBootJobStartRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.job-end-regex")
    Optional<String> springBootJobEndRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.failure-regex")
    Optional<String> springBootFailureRegex;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.sensitive-field-names",
            defaultValue = "token,password,secret,authorization")
    String springBootSensitiveFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.custom-fields")
    Optional<String> springBootCustomFields;

    @Inject @ConfigProperty(name = "log.analyzer.preset.spring-boot.critical-issue-exclusions")
    Optional<String> springBootCriticalIssueExclusions;

    void onStart(@Observes StartupEvent ev) {
        buildPresets();
    }

    private void buildPresets() {
        presets = List.of(
                new LogPreset("WildFly", wildflyLogLineRegex, wildflyTimestampFormat,
                        wildflyApiCallRegex, wildflyJobStartRegex, wildflyJobEndRegex,
                        wildflyFailureRegex, splitFields(wildflySensitiveFields),
                        parseCustomFields(wildflyCustomFields.orElse("")),
                        splitFields(wildflyCriticalIssueExclusions.orElse(""))),

                new LogPreset("Quarkus", quarkusLogLineRegex, quarkusTimestampFormat,
                        quarkusApiCallRegex, quarkusJobStartRegex.orElse(null),
                        quarkusJobEndRegex.orElse(null), quarkusFailureRegex.orElse(null),
                        splitFields(quarkusSensitiveFields),
                        parseCustomFields(quarkusCustomFields.orElse("")),
                        splitFields(quarkusCriticalIssueExclusions.orElse(""))),

                new LogPreset("Spring Boot", springBootLogLineRegex, springBootTimestampFormat,
                        springBootApiCallRegex, springBootJobStartRegex.orElse(null),
                        springBootJobEndRegex.orElse(null), springBootFailureRegex.orElse(null),
                        splitFields(springBootSensitiveFields),
                        parseCustomFields(springBootCustomFields.orElse("")),
                        splitFields(springBootCriticalIssueExclusions.orElse(""))),

                new LogPreset("Custom", "", "", "", null, null, null, List.of(), List.of(), List.of())
        );
    }

    public List<LogPreset> allPresets() {
        if (presets == null) buildPresets();
        return presets;
    }

    public LogPreset byName(String name) {
        return allPresets().stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(allPresets().getLast());
    }

    private List<String> splitFields(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Parses custom fields from a semicolon-separated string.
     * Each entry has format: name|regex|countOnly
     * Example: "SQL Queries|(?&lt;query&gt;SELECT .+)|false;Error Codes|code=(?&lt;code&gt;\\d+)|true"
     *
     * Note: Config-defined regex patterns are trusted (admin-only) and bypass the ReDoS safety check
     * that is applied to user-submitted patterns in CustomFieldExtractor and LogFileParser.
     */
    private List<LogPreset.CustomField> parseCustomFields(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<LogPreset.CustomField> result = new ArrayList<>();
        for (String entry : csv.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length < 2) continue;
            String name = parts[0].trim();
            String regex = parts[1].trim();
            boolean countOnly = parts.length >= 3 && Boolean.parseBoolean(parts[2].trim());
            if (!name.isEmpty() && !regex.isEmpty()) {
                result.add(new LogPreset.CustomField(name, regex, countOnly));
            }
        }
        return result;
    }
}
