package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogPresetTest {

    // ---- allPresets ----

    @Test
    void allPresets_containsFivePresets() {
        List<LogPreset> presets = LogPreset.allPresets();
        assertEquals(5, presets.size());
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

    // ---- Nginx preset ----

    @Test
    void byName_findsNginx() {
        LogPreset preset = LogPreset.byName("Nginx");
        assertEquals("Nginx", preset.name());
        assertNotNull(preset.logLineRegex());
        assertFalse(preset.logLineRegex().isBlank());
    }

    @Test
    void nginx_hasNoJobPatterns() {
        assertFalse(LogPreset.NGINX.hasJobPatterns());
    }

    @Test
    void nginx_hasNoFailurePattern() {
        assertFalse(LogPreset.NGINX.hasFailurePattern());
    }

    @Test
    void nginx_logLineRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.NGINX.logLineRegex()));
    }

    @Test
    void nginx_apiCallRegexCompiles() {
        assertDoesNotThrow(() -> java.util.regex.Pattern.compile(LogPreset.NGINX.apiCallRegex()));
    }

    @Test
    void nginx_apiCallRegexHasNoDirectionGroup() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.apiCallRegex());
        assertFalse(pattern.namedGroups().containsKey("direction"));
        assertTrue(pattern.namedGroups().containsKey("endpoint"));
    }

    @Test
    void nginx_logLineRegex_matchesCombinedFormat() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.logLineRegex());
        var matcher = pattern.matcher("192.168.1.1 - frank [10/Oct/2000:13:55:36 -0700] \"GET /apache_pb.gif HTTP/1.1\" 200 2326 \"http://www.example.com\" \"Mozilla/4.08\"");
        assertTrue(matcher.matches());
        assertEquals("192.168.1.1", matcher.group("thread"));
        assertEquals("10/Oct/2000:13:55:36", matcher.group("timestamp"));
        assertEquals("GET /apache_pb.gif", matcher.group("logger"));
        assertEquals("200", matcher.group("level"));
        assertTrue(matcher.group("message").startsWith("\"GET"));
    }

    @Test
    void nginx_logLineRegex_matchesCacheLogFormat() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.logLineRegex());
        var matcher = pattern.matcher("10.0.0.5 - [29/Apr/2026:14:22:33 -0300] \"POST /api/orders HTTP/1.1\" 201 567 cache=MISS rt=0.045 urt=0.032 resp_size=567");
        assertTrue(matcher.matches());
        assertEquals("10.0.0.5", matcher.group("thread"));
        assertEquals("29/Apr/2026:14:22:33", matcher.group("timestamp"));
        assertEquals("POST /api/orders", matcher.group("logger"));
        assertEquals("201", matcher.group("level"));
    }

    @Test
    void nginx_apiCallRegex_extractsEndpointAndDuration() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.apiCallRegex());
        var matcher = pattern.matcher("\"GET /api/users HTTP/1.1\" 200 1234 cache=HIT rt=0.045 urt=0.032 resp_size=1234");
        assertTrue(matcher.matches());
        assertEquals("GET /api/users", matcher.group("endpoint"));
        assertEquals("0.045", matcher.group("duration"));
    }

    @Test
    void nginx_apiCallRegex_stripsQueryParams() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.apiCallRegex());
        var matcher = pattern.matcher("\"GET /api/users?page=1&sort=name HTTP/1.1\" 200 500 cache=HIT rt=0.010 urt=0.005 resp_size=500");
        assertTrue(matcher.matches());
        assertEquals("GET /api/users", matcher.group("endpoint"));
    }

    @Test
    void nginx_apiCallRegex_matchesWithoutDuration() {
        var pattern = java.util.regex.Pattern.compile(LogPreset.NGINX.apiCallRegex());
        var matcher = pattern.matcher("\"GET /index.html HTTP/1.1\" 200 5000 \"http://ref\" \"Mozilla/5.0\"");
        assertTrue(matcher.matches());
        assertEquals("GET /index.html", matcher.group("endpoint"));
        assertNull(matcher.group("duration"));
    }

    @Test
    void nginx_upstreamDurationField_isUrt() {
        assertEquals("urt", LogPreset.NGINX.upstreamDurationField());
    }

    @Test
    void nonNginxPresets_upstreamDurationFieldIsNull() {
        assertNull(LogPreset.WILDFLY.upstreamDurationField());
        assertNull(LogPreset.QUARKUS.upstreamDurationField());
        assertNull(LogPreset.SPRING_BOOT.upstreamDurationField());
        assertNull(LogPreset.CUSTOM.upstreamDurationField());
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
