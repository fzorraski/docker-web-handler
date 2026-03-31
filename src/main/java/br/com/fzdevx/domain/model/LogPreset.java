package br.com.fzdevx.domain.model;

import java.util.List;

public record LogPreset(
        String name,
        String logLineRegex,
        String timestampFormat,
        String apiCallRegex,
        String jobStartRegex,
        String jobEndRegex,
        String failureRegex,
        List<String> sensitiveFieldNames,
        List<CustomField> customFields
) {

    public record CustomField(
            String name,
            String regex,
            boolean countOnly
    ) {}

    public static final LogPreset WILDFLY = new LogPreset(
            "WildFly",
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(?<level>\\w+)\\s+\\[(?<logger>[^\\]]+)\\]\\s+\\((?<thread>[^)]+)\\)\\s+(?<message>.*)$",
            "yyyy-MM-dd HH:mm:ss,SSS",
            "^(?<endpoint>\\w+(?:WS|Resource)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$",
            "^Job \\[(?<jobName>.+?)\\] vai ser disparado pelo trigger \\[(?<trigger>.+?)\\]",
            "^Job \\[(?<jobName>.+?)\\] executou em .+ and reports: (?<result>.+)$",
            "ORDEM (?<entityId>ORDER \\d+) FALHA AO INICIAR (?<reason>\\w+):",
            List.of("token", "senha", "password", "secret", "authorization"),
            List.of()
    );

    public static final LogPreset QUARKUS = new LogPreset(
            "Quarkus",
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(?<level>\\w+)\\s+\\[(?<logger>[^\\]]+)\\]\\s+\\((?<thread>[^)]+)\\)\\s+(?<message>.*)$",
            "yyyy-MM-dd HH:mm:ss,SSS",
            "^(?<endpoint>\\w+(?:WS|Resource|Controller)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$",
            null,
            null,
            null,
            List.of("token", "password", "secret", "authorization"),
            List.of()
    );

    public static final LogPreset SPRING_BOOT = new LogPreset(
            "Spring Boot",
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2})\\s+(?<level>\\w+)\\s+\\d+\\s+---\\s+\\[(?<thread>[^\\]]+)\\]\\s+(?<logger>\\S+)\\s+:\\s+(?<message>.*)$",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "^(?<endpoint>\\w+(?:Controller|Resource|Service)/\\w+)(?:\\s+(?<correlationId>\\d+))?\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$",
            null,
            null,
            null,
            List.of("token", "password", "secret", "authorization"),
            List.of()
    );

    public static final LogPreset CUSTOM = new LogPreset(
            "Custom",
            "",
            "",
            "",
            null,
            null,
            null,
            List.of(),
            List.of()
    );

    public static List<LogPreset> allPresets() {
        return List.of(WILDFLY, QUARKUS, SPRING_BOOT, CUSTOM);
    }

    public static LogPreset byName(String name) {
        return allPresets().stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(CUSTOM);
    }

    public boolean hasJobPatterns() {
        return jobStartRegex != null && !jobStartRegex.isBlank()
                && jobEndRegex != null && !jobEndRegex.isBlank();
    }

    public boolean hasFailurePattern() {
        return failureRegex != null && !failureRegex.isBlank();
    }

    public boolean hasCustomFields() {
        return customFields != null && !customFields.isEmpty();
    }
}
