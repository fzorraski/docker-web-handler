package br.com.fzdevx.domain.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InputValidatorTest {

    // ---- validateRepository ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void validateRepository_nullOrBlank_returnsError(String repo) {
        assertTrue(InputValidator.validateRepository(repo).isPresent());
    }

    @Test
    void validateRepository_uppercase_returnsError() {
        assertTrue(InputValidator.validateRepository("UPPERCASE").isPresent());
    }

    @Test
    void validateRepository_tooLong_returnsError() {
        String longName = "a".repeat(256);
        assertTrue(InputValidator.validateRepository(longName).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "my/repo", "my-repo", "repo_name", "org/repo.name"})
    void validateRepository_validNames_returnsEmpty(String repo) {
        assertTrue(InputValidator.validateRepository(repo).isEmpty());
    }

    // ---- validateTag ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateTag_nullOrBlank_returnsError(String tag) {
        assertTrue(InputValidator.validateTag(tag).isPresent());
    }

    @Test
    void validateTag_tooLong_returnsError() {
        String longTag = "a".repeat(129);
        assertTrue(InputValidator.validateTag(longTag).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "16.4", "v1.0.0-rc1", "sha-abc123"})
    void validateTag_validTags_returnsEmpty(String tag) {
        assertTrue(InputValidator.validateTag(tag).isEmpty());
    }

    @Test
    void validateTag_startingWithHyphen_returnsError() {
        assertTrue(InputValidator.validateTag("-invalid").isPresent());
    }

    // ---- validateContainerName ----

    @Test
    void validateContainerName_null_returnsEmpty() {
        assertTrue(InputValidator.validateContainerName(null).isEmpty());
    }

    @Test
    void validateContainerName_blank_returnsEmpty() {
        assertTrue(InputValidator.validateContainerName("   ").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-container", "web_server", "app.v2", "Test123"})
    void validateContainerName_validNames_returnsEmpty(String name) {
        assertTrue(InputValidator.validateContainerName(name).isEmpty());
    }

    @Test
    void validateContainerName_startingWithHyphen_returnsError() {
        assertTrue(InputValidator.validateContainerName("-invalid").isPresent());
    }

    @Test
    void validateContainerName_tooLong_returnsError() {
        String longName = "a".repeat(256);
        assertTrue(InputValidator.validateContainerName(longName).isPresent());
    }

    // ---- validateContainerId ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateContainerId_nullOrBlank_returnsError(String id) {
        assertTrue(InputValidator.validateContainerId(id).isPresent());
    }

    @Test
    void validateContainerId_tooShort_returnsError() {
        assertTrue(InputValidator.validateContainerId("abc").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdef1234", "ABCDEF1234", "abcdef1234abcdef1234abcdef1234abcdef1234abcdef1234abcdef1234abcd"})
    void validateContainerId_validIds_returnsEmpty(String id) {
        assertTrue(InputValidator.validateContainerId(id).isEmpty());
    }

    @Test
    void validateContainerId_nonHex_returnsError() {
        assertTrue(InputValidator.validateContainerId("ghijklmnop").isPresent());
    }

    // ---- validateEnvVars ----

    @Test
    void validateEnvVars_null_returnsEmpty() {
        assertTrue(InputValidator.validateEnvVars(null).isEmpty());
    }

    @Test
    void validateEnvVars_emptyList_returnsEmpty() {
        assertTrue(InputValidator.validateEnvVars(List.of()).isEmpty());
    }

    @Test
    void validateEnvVars_valid_returnsEmpty() {
        assertTrue(InputValidator.validateEnvVars(List.of("KEY=value", "_VAR=123", "MY_VAR=")).isEmpty());
    }

    @Test
    void validateEnvVars_missingEquals_returnsError() {
        Optional<String> result = InputValidator.validateEnvVars(List.of("NOEQUALS"));
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("KEY=VALUE"));
    }

    @Test
    void validateEnvVars_invalidKey_returnsError() {
        Optional<String> result = InputValidator.validateEnvVars(List.of("BAD-KEY=value"));
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Invalid environment variable key"));
    }

    @Test
    void validateEnvVars_keyStartsWithDigit_returnsError() {
        assertTrue(InputValidator.validateEnvVars(List.of("1BAD=value")).isPresent());
    }

    // ---- validateMemoryMb ----

    @Test
    void validateMemoryMb_null_returnsEmpty() {
        assertTrue(InputValidator.validateMemoryMb(null).isEmpty());
    }

    @Test
    void validateMemoryMb_tooLow_returnsError() {
        assertTrue(InputValidator.validateMemoryMb(3L).isPresent());
    }

    @Test
    void validateMemoryMb_tooHigh_returnsError() {
        assertTrue(InputValidator.validateMemoryMb(100_000L).isPresent());
    }

    @Test
    void validateMemoryMb_valid_returnsEmpty() {
        assertTrue(InputValidator.validateMemoryMb(512L).isEmpty());
    }

    @Test
    void validateMemoryMb_exactBounds_returnsEmpty() {
        assertTrue(InputValidator.validateMemoryMb(4L).isEmpty());
        assertTrue(InputValidator.validateMemoryMb(65536L).isEmpty());
    }

    // ---- validateDatabaseName ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateDatabaseName_nullOrBlank_returnsError(String name) {
        assertTrue(InputValidator.validateDatabaseName(name).isPresent());
    }

    @Test
    void validateDatabaseName_startsWithDigit_returnsError() {
        assertTrue(InputValidator.validateDatabaseName("123bad").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mydb", "_private", "my_database", "DB-test"})
    void validateDatabaseName_validNames_returnsEmpty(String name) {
        assertTrue(InputValidator.validateDatabaseName(name).isEmpty());
    }

    // ---- validateImageId ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateImageId_nullOrBlank_returnsError(String id) {
        assertTrue(InputValidator.validateImageId(id).isPresent());
    }

    @Test
    void validateImageId_validShortId_returnsEmpty() {
        assertTrue(InputValidator.validateImageId("abcdef1").isEmpty());
    }

    @Test
    void validateImageId_validSha256_returnsEmpty() {
        assertTrue(InputValidator.validateImageId("sha256:abcdef1234567890").isEmpty());
    }

    @Test
    void validateImageId_nonHex_returnsError() {
        assertTrue(InputValidator.validateImageId("zzzzzzzz").isPresent());
    }

    // ---- validateFilename ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateFilename_nullOrBlank_returnsError(String filename) {
        assertTrue(InputValidator.validateFilename(filename).isPresent());
    }

    @Test
    void validateFilename_pathTraversal_returnsError() {
        assertTrue(InputValidator.validateFilename("../etc/passwd.sql").isPresent());
    }

    @Test
    void validateFilename_unsupportedExtension_returnsError() {
        assertTrue(InputValidator.validateFilename("data.txt").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dump.sql", "backup.dump", "data.gz", "archive.tar.gz"})
    void validateFilename_validNames_returnsEmpty(String filename) {
        assertTrue(InputValidator.validateFilename(filename).isEmpty());
    }

    // ---- validateUuid ----

    @ParameterizedTest
    @NullAndEmptySource
    void validateUuid_nullOrBlank_returnsError(String uuid) {
        assertTrue(InputValidator.validateUuid(uuid).isPresent());
    }

    @Test
    void validateUuid_valid_returnsEmpty() {
        assertTrue(InputValidator.validateUuid("550e8400-e29b-41d4-a716-446655440000").isEmpty());
    }

    @Test
    void validateUuid_invalid_returnsError() {
        assertTrue(InputValidator.validateUuid("not-a-uuid").isPresent());
    }

    // ---- validateSnapshotFormat ----

    @Test
    void validateSnapshotFormat_custom_returnsEmpty() {
        assertTrue(InputValidator.validateSnapshotFormat("CUSTOM").isEmpty());
    }

    @Test
    void validateSnapshotFormat_sql_returnsEmpty() {
        assertTrue(InputValidator.validateSnapshotFormat("SQL").isEmpty());
    }

    @Test
    void validateSnapshotFormat_invalid_returnsError() {
        assertTrue(InputValidator.validateSnapshotFormat("PLAIN").isPresent());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void validateSnapshotFormat_nullOrBlank_returnsError(String format) {
        assertTrue(InputValidator.validateSnapshotFormat(format).isPresent());
    }

    // ---- validateSnapshotLabel ----

    @Test
    void validateSnapshotLabel_null_returnsEmpty() {
        assertTrue(InputValidator.validateSnapshotLabel(null).isEmpty());
    }

    @Test
    void validateSnapshotLabel_tooLong_returnsError() {
        assertTrue(InputValidator.validateSnapshotLabel("a".repeat(101)).isPresent());
    }

    @Test
    void validateSnapshotLabel_valid_returnsEmpty() {
        assertTrue(InputValidator.validateSnapshotLabel("Release 2.0 snapshot").isEmpty());
    }
}
