package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogPresetTest {

    // ---- allPresets ----

    @Test
    void allPresets_containsFourPresets() {
        List<LogPreset> presets = LogPreset.allPresets();
        assertEquals(4, presets.size());
    }

    @Test
    void allPresets_namesAreDistinct() {
        List<String> names = LogPreset.allPresets().stream().map(LogPreset::name).toList();
        assertEquals(names.size(), names.stream().distinct().count());
    }

    // ---- byName ----

    @Test
    void byName_findsWildfly() {
        LogPreset preset = LogPreset.byName("WildFly");
        assertEquals("WildFly", preset.name());
        assertNotNull(preset.logLineRegex());
        assertFalse(preset.logLineRegex().isBlank());
    }

    @Test
    void byName_caseInsensitive() {
        assertEquals("WildFly", LogPreset.byName("wildfly").name());
        assertEquals("Quarkus", LogPreset.byName("QUARKUS").name());
        assertEquals("Spring Boot", LogPreset.byName("spring boot").name());
    }

    @Test
    void byName_unknownName_returnsCustom() {
        LogPreset preset = LogPreset.byName("UnknownFramework");
        assertEquals("Custom", preset.name());
    }

    @Test
    void byName_null_returnsCustom() {
        LogPreset preset = LogPreset.byName(null);
        assertEquals("Custom", preset.name());
    }

    // ---- hasJobPatterns ----

    @Test
    void wildfly_hasJobPatterns() {
        assertTrue(LogPreset.WILDFLY.hasJobPatterns());
    }

    @Test
    void quarkus_hasNoJobPatterns() {
        assertFalse(LogPreset.QUARKUS.hasJobPatterns());
    }

    @Test
    void springBoot_hasNoJobPatterns() {
        assertFalse(LogPreset.SPRING_BOOT.hasJobPatterns());
    }

    @Test
    void custom_hasNoJobPatterns() {
        assertFalse(LogPreset.CUSTOM.hasJobPatterns());
    }

    // ---- hasFailurePattern ----

    @Test
    void wildfly_hasFailurePattern() {
        assertTrue(LogPreset.WILDFLY.hasFailurePattern());
    }

    @Test
    void quarkus_hasNoFailurePattern() {
        assertFalse(LogPreset.QUARKUS.hasFailurePattern());
    }

    @Test
    void springBoot_hasNoFailurePattern() {
        assertFalse(LogPreset.SPRING_BOOT.hasFailurePattern());
    }

    @Test
    void custom_hasNoFailurePattern() {
        assertFalse(LogPreset.CUSTOM.hasFailurePattern());
    }

    // ---- sensitiveFieldNames ----

    @Test
    void wildfly_hasSensitiveFields() {
        assertFalse(LogPreset.WILDFLY.sensitiveFieldNames().isEmpty());
        assertTrue(LogPreset.WILDFLY.sensitiveFieldNames().contains("token"));
        assertTrue(LogPreset.WILDFLY.sensitiveFieldNames().contains("senha"));
    }

    @Test
    void custom_hasEmptySensitiveFields() {
        assertTrue(LogPreset.CUSTOM.sensitiveFieldNames().isEmpty());
    }

    // ---- regex validity ----

    @Test
    void wildfly_logLineRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.WILDFLY.logLineRegex()));
    }

    @Test
    void wildfly_apiCallRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.WILDFLY.apiCallRegex()));
    }

    @Test
    void springBoot_logLineRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.SPRING_BOOT.logLineRegex()));
    }

    @Test
    void quarkus_logLineRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.QUARKUS.logLineRegex()));
    }

    @Test
    void wildfly_jobStartRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.WILDFLY.jobStartRegex()));
    }

    @Test
    void wildfly_jobEndRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.WILDFLY.jobEndRegex()));
    }

    @Test
    void wildfly_failureRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.WILDFLY.failureRegex()));
    }

    // ---- WildFly log line regex matches expected format ----

    @Test
    void wildfly_logLineRegex_matchesWildFlyFormat() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.WILDFLY.logLineRegex());
        var matcher = pattern.matcher("2026-03-30 07:31:13,938 INFO  [stdout] (default task-11155) Some message here");
        assertTrue(matcher.matches());
        assertEquals("2026-03-30 07:31:13,938", matcher.group("timestamp"));
        assertEquals("INFO", matcher.group("level"));
        assertEquals("stdout", matcher.group("logger"));
        assertEquals("default task-11155", matcher.group("thread"));
        assertEquals("Some message here", matcher.group("message"));
    }

    // ---- WildFly API call regex matches both variants ----

    @Test
    void wildfly_apiCallRegex_matchesWithCorrelationId() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.WILDFLY.apiCallRegex());
        var matcher = pattern.matcher("CustomerOrderResource/update 73938 Request = Body: {\"id\":1}");
        assertTrue(matcher.matches());
        assertEquals("CustomerOrderResource/update", matcher.group("endpoint"));
        assertEquals("73938", matcher.group("correlationId"));
        assertEquals("Request", matcher.group("direction"));
        assertEquals("Body: {\"id\":1}", matcher.group("payload"));
    }

    @Test
    void wildfly_apiCallRegex_matchesWithoutCorrelationId() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.WILDFLY.apiCallRegex());
        var matcher = pattern.matcher("AplicativoWS/getApk Request = 6185000");
        assertTrue(matcher.matches());
        assertEquals("AplicativoWS/getApk", matcher.group("endpoint"));
        assertNull(matcher.group("correlationId"));
        assertEquals("Request", matcher.group("direction"));
        assertEquals("6185000", matcher.group("payload"));
    }
}
