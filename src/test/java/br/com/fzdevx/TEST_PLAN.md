# Suggested Test Cases

## Use Cases

### RunContainerUseCase
- **Happy path**: valid request → pulls image → creates container → starts → schedules expiration → emits SUCCESS event
- **Validation failure**: invalid repository name → emits ERROR event at Validating step
- **Whitelist rejection**: repository not in allowed list → emits ERROR event
- **Pull failure**: registry unreachable → emits ERROR at Pulling step
- **Expiration cap**: requested expiration beyond DB deletion expiration → capped to DB deletion time

### RemoveContainerUseCase
- **Happy path**: valid container ID → cancels expiration → stops → removes → emits SUCCESS
- **Invalid ID**: malformed container ID → emits ERROR at validation
- **Already stopped**: stop throws exception → skips gracefully, proceeds to remove
- **Remove failure**: Docker API error on remove → emits ERROR

### RemoveImageUseCase
- **Happy path**: valid image ID → removes → emits SUCCESS
- **Image in use**: Docker returns "image is being used" → emits specific error message
- **Dependent children**: Docker returns "dependent child images" → emits specific error message
- **Invalid ID**: malformed image ID → emits ERROR at validation

### RestoreDumpUseCase
- **Happy path**: valid dump → decompresses → creates DB → restores → runs scripts → returns true
- **Concurrent restore**: same repository+database already restoring → returns false with OPERATION_IN_PROGRESS
- **Dump not found**: invalid dump ID → returns false
- **DB creation skip**: createDatabase=false → skips DB creation step
- **Cancellation**: user cancels mid-restore → drops newly created DB, cleans up temp files
- **Non-fatal warnings**: pg_restore exit code 1 with warnings → treated as success

### CreateSnapshotUseCase
- **Happy path**: valid request → runs pg_dump → stores gzipped → saves metadata → returns true
- **Concurrent snapshot**: same repository+database already snapshotting → returns false
- **Invalid format**: format not CUSTOM or SQL → returns validation error
- **pg_dump failure**: exit code != 0 → cleans up file, returns false
- **Cancellation**: user cancels → stops ephemeral container, cleans up file

## Domain Shared

### InputValidator
- **validateRepository**: null → error, blank → error, >255 chars → error, invalid chars → error, valid → empty
- **validateTag**: null → error, >128 chars → error, invalid chars → error, valid → empty
- **validateContainerId**: null → error, non-hex → error, valid 10-64 hex → empty
- **validateDatabaseName**: null → error, starts with digit → error, valid → empty
- **validateFilename**: contains ".." → error, no allowed extension → error, valid → empty
- **validateUuid**: null → error, wrong format → error, valid UUID → empty

### DateTimeParser
- **parseExpiresAt**: null → null, blank → null, valid ISO → correct Instant, invalid format → null

## Infrastructure

### PasswordValidationService
- **validateUploadPassword**: correct password → true, wrong → false, empty config → false, null input → false
- **validateOperationsPassword**: same as above

### AbstractJsonFileRepository
- **save**: new entity → persisted to file, existing entity → updated
- **delete**: existing → removed from file, non-existing → no-op
- **findFirst**: matching entity → present, no match → empty
- **Concurrent access**: multiple threads reading/writing → no corruption (ReentrantReadWriteLock)
- **Missing file**: file doesn't exist → returns empty list

## Security Boundary Tests

### GlobalExceptionMapper
- **Unhandled exception**: RuntimeException → 500 with `{"code":"INTERNAL_ERROR","message":"An unexpected error occurred."}`
- **DomainException subtypes**: EntityNotFoundException → 404, InvalidInputException → 400, DuplicateEntityException → 409
- **DuplicateDumpException**: → 409 with DUPLICATE code
- **Stack trace suppression**: exception with internal details → sanitized message in response

### ContentDispositionHelper
- **Filename sanitization**: newlines stripped, quotes stripped, non-ASCII replaced, path separators replaced
- **Null/blank input**: → "download"

### SQL Injection Prevention (DatabaseService)
- **dropDatabase**: name with special chars (validated + quote_ident) → safe execution
- **createDatabase**: same as above
