package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.port.CustomFieldExtractorPort;
import br.com.fzdevx.domain.model.CustomFieldMatch;
import br.com.fzdevx.domain.model.CustomFieldResult;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.LogPreset;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class CustomFieldExtractor implements CustomFieldExtractorPort {

    private static final Logger LOG = Logger.getLogger(CustomFieldExtractor.class.getName());
    private static final long REGEX_SAFETY_TIMEOUT_MS = 2000;

    @Inject
    @ConfigProperty(name = "log.analyzer.custom-fields.max-matches", defaultValue = "10000")
    int maxMatches;
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9 \\-]{1,50}$");

    @Override
    public List<CustomFieldResult> extract(List<LogLine> lines, List<LogPreset.CustomField> customFields) {
        if (customFields == null || customFields.isEmpty()) {
            return List.of();
        }

        // Compile and validate all fields first
        record CompiledField(LogPreset.CustomField field, Pattern pattern, Map<String, Integer> namedGroups) {}
        List<CompiledField> compiledFields = new ArrayList<>();

        for (LogPreset.CustomField field : customFields) {
            if (!isValidName(field.name())) {
                LOG.warning(String.format("Skipping custom field with invalid name: '%s' " +
                        "(must be alphanumeric, spaces, or hyphens, max 50 chars)", field.name()));
                continue;
            }

            Pattern pattern = compileAndValidate(field.regex(), field.name());
            if (pattern == null) {
                continue;
            }

            compiledFields.add(new CompiledField(field, pattern, pattern.namedGroups()));
        }

        if (compiledFields.isEmpty()) {
            return List.of();
        }

        // Per-field accumulators
        int[] matchCounts = new int[compiledFields.size()];
        @SuppressWarnings("unchecked")
        List<CustomFieldMatch>[] matchLists = new List[compiledFields.size()];
        for (int i = 0; i < compiledFields.size(); i++) {
            matchLists[i] = compiledFields.get(i).field.countOnly() ? List.of() : new ArrayList<>();
        }

        // Single pass over all lines
        for (LogLine line : lines) {
            if (line.message() == null) continue;
            for (int i = 0; i < compiledFields.size(); i++) {
                CompiledField cf = compiledFields.get(i);
                Matcher matcher = cf.pattern.matcher(line.message());
                if (matcher.find()) {
                    matchCounts[i]++;

                    if (!cf.field.countOnly() && matchLists[i].size() < maxMatches) {
                        Map<String, String> groups = new LinkedHashMap<>();
                        for (String groupName : cf.namedGroups.keySet()) {
                            String value = matcher.group(groupName);
                            if (value != null) {
                                groups.put(groupName, value);
                            }
                        }
                        matchLists[i].add(new CustomFieldMatch(
                                line.lineNumber(),
                                line.timestamp(),
                                line.thread(),
                                line.sourceFile(),
                                line.message(),
                                groups
                        ));
                    }
                }
            }
        }

        List<CustomFieldResult> results = new ArrayList<>();
        for (int i = 0; i < compiledFields.size(); i++) {
            CompiledField cf = compiledFields.get(i);
            results.add(new CustomFieldResult(cf.field.name(), matchCounts[i], cf.field.countOnly(), matchLists[i]));
        }
        return results;
    }

    private boolean isValidName(String name) {
        return name != null && VALID_NAME_PATTERN.matcher(name).matches();
    }

    private Pattern compileAndValidate(String regex, String fieldName) {
        if (regex == null || regex.isBlank()) {
            LOG.warning(String.format("Skipping custom field '%s': regex is blank", fieldName));
            return null;
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            LOG.warning(String.format("Skipping custom field '%s': invalid regex: %s", fieldName, e.getDescription()));
            return null;
        }

        if (!isRegexSafe(pattern, fieldName)) {
            return null;
        }

        return pattern;
    }

    private boolean isRegexSafe(Pattern pattern, String fieldName) {
        String[] testInputs = {
            "a".repeat(1000),
            "ab".repeat(500),
            "a b c ".repeat(167),
            "abc123!@#".repeat(111),
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (String input : testInputs) {
                Future<?> future = executor.submit(() -> pattern.matcher(input).find());
                try {
                    future.get(REGEX_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    LOG.warning(String.format("Skipping custom field '%s': regex timed out on safety check", fieldName));
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException e) {
                    return false;
                }
            }
            return true;
        } finally {
            executor.shutdownNow();
        }
    }
}
