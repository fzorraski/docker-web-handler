/**
 * Layer: interfaces/rest/util
 * SOLID: S (single responsibility — multipart form parsing for log uploads)
 * Behavior: identical to original LogAnalyzerController#parseUploadForm and related helpers
 */
package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.domain.model.LogPreset;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

public final class UploadFormParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private UploadFormParser() {
    }

    public static AnalyzeLogFileRequest parse(MultipartFormDataInput input,
                                              LogPresetProvider logPresetProvider,
                                              String defaultPresetName,
                                              int defaultSlowThresholdMs,
                                              int maxFileSizeMb) throws Exception {
        Map<String, List<InputPart>> form = input.getFormDataMap();

        String label = extractString(form, "label");
        String presetName = extractString(form, "preset");
        LogPreset basePreset = presetName != null ? logPresetProvider.byName(presetName) : logPresetProvider.byName(defaultPresetName);

        String customLogLineRegex = extractString(form, "logLineRegex");
        String customApiCallRegex = extractString(form, "apiCallRegex");
        String customTimestampFormat = extractString(form, "timestampFormat");
        String customJobStartRegex = extractString(form, "jobStartRegex");
        String customJobEndRegex = extractString(form, "jobEndRegex");
        String customFailureRegex = extractString(form, "failureRegex");
        String customSensitiveFields = extractString(form, "sensitiveFieldNames");
        String customFieldsJson = extractString(form, "customFields");
        String customCriticalIssueExclusions = extractString(form, "criticalIssueExclusions");
        String customUpstreamDurationField = extractString(form, "upstreamDurationField");
        if (customUpstreamDurationField != null) {
            customUpstreamDurationField = customUpstreamDurationField.trim();
            InputValidator.validateLogFieldName(customUpstreamDurationField)
                    .ifPresent(err -> { throw new IllegalArgumentException(err); });
        }

        List<LogPreset.CustomField> uploadCustomFields = parseCustomFieldsJson(customFieldsJson);
        List<LogPreset.CustomField> mergedCustomFields = uploadCustomFields.isEmpty()
                ? basePreset.customFields()
                : uploadCustomFields;

        LogPreset preset = new LogPreset(
                basePreset.name(),
                nonBlankOrDefault(customLogLineRegex, basePreset.logLineRegex()),
                nonBlankOrDefault(customTimestampFormat, basePreset.timestampFormat()),
                nonBlankOrDefault(customApiCallRegex, basePreset.apiCallRegex()),
                nonBlankOrDefault(customJobStartRegex, basePreset.jobStartRegex()),
                nonBlankOrDefault(customJobEndRegex, basePreset.jobEndRegex()),
                nonBlankOrDefault(customFailureRegex, basePreset.failureRegex()),
                customSensitiveFields != null && !customSensitiveFields.isBlank()
                        ? Arrays.asList(customSensitiveFields.split(","))
                        : basePreset.sensitiveFieldNames(),
                mergedCustomFields,
                customCriticalIssueExclusions != null && !customCriticalIssueExclusions.isBlank()
                        ? Arrays.asList(customCriticalIssueExclusions.split(","))
                        : basePreset.criticalIssueExclusions(),
                nonBlankOrDefault(customUpstreamDurationField, basePreset.upstreamDurationField())
        );

        if (preset.logLineRegex() == null || preset.logLineRegex().isBlank()) {
            throw new IllegalArgumentException("Log line regex is required.");
        }

        String slowThresholdStr = extractString(form, "slowThresholdMs");
        int slowThresholdMs;
        if (slowThresholdStr != null) {
            try {
                slowThresholdMs = Integer.parseInt(slowThresholdStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid slowThresholdMs value: " + slowThresholdStr);
            }
        } else {
            slowThresholdMs = defaultSlowThresholdMs;
        }

        List<InputPart> fileParts = form.get("files");
        if (fileParts == null || fileParts.isEmpty()) {
            fileParts = form.get("file");
        }
        if (fileParts == null || fileParts.isEmpty()) {
            throw new IllegalArgumentException("No file(s) provided.");
        }

        List<java.nio.file.Path> tempFiles = new ArrayList<>();
        List<java.nio.file.Path> tempDirs = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        long maxBytes = (long) maxFileSizeMb * 1024 * 1024;

        try {
            for (InputPart filePart : fileParts) {
                String filename = extractFilename(filePart);
                if (filename == null || filename.isBlank()) {
                    filename = "unknown-" + (filenames.size() + 1) + ".log";
                }

                Optional<String> filenameError = InputValidator.validateUploadFilename(filename);
                if (filenameError.isPresent()) {
                    throw new IllegalArgumentException(filenameError.get());
                }

                java.nio.file.Path tempDir = Files.createTempDirectory("log-analyzer-");
                tempDirs.add(tempDir);
                java.nio.file.Path tempFile = tempDir.resolve(filename);
                tempFiles.add(tempFile);
                filenames.add(filename);

                try (InputStream is = filePart.getBody(InputStream.class, null);
                     var out = Files.newOutputStream(tempFile)) {
                    long size = 0;
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        size += read;
                        if (size > maxBytes) {
                            throw new IllegalArgumentException(
                                    "File '" + filename + "' exceeds the maximum size of " + maxFileSizeMb + " MB.");
                        }
                        out.write(buf, 0, read);
                    }
                }
            }
        } catch (Exception e) {
            // Clean up any temp files created before the failure
            cleanupTempFiles(tempFiles, tempDirs);
            throw e;
        }

        AnalysisOptions options = parseAnalysisOptions(extractString(form, "options"));

        return new AnalyzeLogFileRequest(tempFiles, tempDirs, filenames, label, preset, slowThresholdMs, options);
    }

    public static AnalysisOptions parseAnalysisOptions(String json) {
        if (json == null || json.isBlank()) return AnalysisOptions.all();
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            return buildAnalysisOptions(map);
        } catch (Exception e) {
            Log.warnf("Failed to parse analysis options JSON, using defaults: %s", e.getMessage());
            return AnalysisOptions.all();
        }
    }

    @SuppressWarnings("unchecked")
    public static AnalysisOptions parseAnalysisOptionsFromMap(Object optionsObj) {
        if (optionsObj == null) return AnalysisOptions.all();
        if (optionsObj instanceof Map<?, ?> map) {
            return buildAnalysisOptions((Map<String, Object>) map);
        }
        return AnalysisOptions.all();
    }

    static AnalysisOptions buildAnalysisOptions(Map<String, Object> map) {
        return new AnalysisOptions(
                optionFlag(map, "apiCalls"),
                optionFlag(map, "jobs"),
                optionFlag(map, "failures"),
                optionFlag(map, "criticalIssues"),
                optionFlag(map, "npeAnalysis"),
                optionFlag(map, "exceptionAnalysis"),
                optionFlag(map, "customFields")
        );
    }

    static String extractString(Map<String, List<InputPart>> form, String key) {
        List<InputPart> parts = form.get(key);
        if (parts == null || parts.isEmpty()) return null;
        try {
            return parts.getFirst().getBodyAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    static String extractFilename(InputPart part) {
        String header = part.getHeaders().getFirst("Content-Disposition");
        if (header == null) return null;
        for (String s : header.split(";")) {
            String trimmed = s.trim();
            if (trimmed.startsWith("filename")) {
                String[] parts = trimmed.split("=");
                if (parts.length < 2) return null;
                return parts[1].trim().replace("\"", "");
            }
        }
        return null;
    }

    private static void cleanupTempFiles(List<java.nio.file.Path> tempFiles, List<java.nio.file.Path> tempDirs) {
        for (java.nio.file.Path f : tempFiles) {
            try { Files.deleteIfExists(f); } catch (Exception ignored) { }
        }
        for (java.nio.file.Path d : tempDirs) {
            try { Files.deleteIfExists(d); } catch (Exception ignored) { }
        }
    }

    static String nonBlankOrDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    static List<LogPreset.CustomField> parseCustomFieldsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            List<LogPreset.CustomField> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                String regex = (String) item.get("regex");
                boolean countOnly = Boolean.TRUE.equals(item.get("countOnly"));
                if (name != null && regex != null) {
                    result.add(new LogPreset.CustomField(name, regex, countOnly));
                }
            }
            return result;
        } catch (Exception e) {
            Log.warnf("Failed to parse customFields JSON: %s", e.getMessage());
            return List.of();
        }
    }

    private static boolean optionFlag(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return !"false".equalsIgnoreCase(s);
        return true; // default to enabled
    }
}
