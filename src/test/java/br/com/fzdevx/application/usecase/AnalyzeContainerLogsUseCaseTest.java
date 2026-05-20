package br.com.fzdevx.application.usecase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeContainerLogsUseCaseTest {

    @Test
    void stripDockerTimestamp_wildflyTimeOnly_prependsDate() {
        String input = "2026-05-20T10:40:00.949377221Z 07:40:00,949 INFO  [de.linogistix.MyBean] (Worker-11) message";
        String expected = "2026-05-20 07:40:00,949 INFO  [de.linogistix.MyBean] (Worker-11) message";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_quarkusTimeOnly_prependsDate() {
        String input = "2026-05-20T14:30:01.123456789Z 14:30:01,123 INFO  [io.quarkus] (main) Installed features";
        String expected = "2026-05-20 14:30:01,123 INFO  [io.quarkus] (main) Installed features";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_springBootWithDate_stripsPrefix() {
        // Spring Boot logs already include full datetime — Docker prefix should be stripped, rest left as-is
        String input = "2026-05-20T10:40:00.949377221Z 2026-05-20T10:40:00.949+00:00 INFO 1 --- [main] c.example.App : Started";
        String expected = "2026-05-20T10:40:00.949+00:00 INFO 1 --- [main] c.example.App : Started";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_wildflyFullDatetime_stripsPrefix() {
        // WildFly file handler includes full datetime — Docker prefix stripped, rest matches preset
        String input = "2026-05-20T10:40:00.949377221Z 2026-05-20 07:40:00,949 INFO  [org.jboss] (main) WFLYSRV0025";
        String expected = "2026-05-20 07:40:00,949 INFO  [org.jboss] (main) WFLYSRV0025";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_noDockerPrefix_returnsUnchanged() {
        String input = "2026-05-20 07:40:00,949 INFO  [org.jboss] (main) message";
        assertEquals(input, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_plainText_returnsUnchanged() {
        String input = "This is just a plain log line";
        assertEquals(input, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_timezoneOffset_stripsPrefix() {
        String input = "2026-05-20T10:40:00.949+03:00 07:40:00,949 INFO  [org.jboss] (main) message";
        String expected = "2026-05-20 07:40:00,949 INFO  [org.jboss] (main) message";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_dotMillis_prependsDate() {
        // Some loggers use dot instead of comma for millis separator
        String input = "2026-05-20T10:40:00.949377221Z 07:40:00.949 INFO  [logger] (thread) message";
        String expected = "2026-05-20 07:40:00.949 INFO  [logger] (thread) message";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_noNanos_stripsPrefix() {
        String input = "2026-05-20T10:40:00Z 07:40:00,949 INFO  [logger] (thread) message";
        String expected = "2026-05-20 07:40:00,949 INFO  [logger] (thread) message";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_stackTraceLine_stripsPrefix() {
        String input = "2026-05-20T10:40:01.123456789Z \tat com.example.MyClass.method(MyClass.java:42)";
        String expected = "\tat com.example.MyClass.method(MyClass.java:42)";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_causedByLine_stripsPrefix() {
        String input = "2026-05-20T10:40:01.123456789Z Caused by: java.lang.NullPointerException";
        String expected = "Caused by: java.lang.NullPointerException";
        assertEquals(expected, AnalyzeContainerLogsUseCase.stripDockerTimestamp(input));
    }

    @Test
    void stripDockerTimestamp_emptyString_returnsUnchanged() {
        assertEquals("", AnalyzeContainerLogsUseCase.stripDockerTimestamp(""));
    }
}
