# CI/CD Pipeline API

Programmatic API for creating and managing ephemeral Docker environments from CI pipelines (GitHub Actions, GitLab CI, Jenkins, etc.).

## Quick Start

### 1. Enable the API

Set environment variables or edit `application.properties`:

```bash
CI_API_ENABLED=true
CI_API_KEY=your-secret-api-key
```

### 2. Create an environment

```bash
curl -X POST http://your-server:8080/api/ci/environments \
  -H "X-API-Key: your-secret-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "repository": "postgres",
    "tag": "16",
    "environmentName": "ci-pr-123",
    "databaseName": "test_db",
    "createDatabase": true,
    "dumpId": "550e8400-e29b-41d4-a716-446655440000",
    "deleteDatabaseOnExpiration": true,
    "ttlMinutes": 60,
    "pipelineId": "gh-run-456"
  }'
```

### 3. Run your tests

Use the returned `portMappings` to connect to the container.

### 4. Destroy the environment

```bash
curl -X DELETE http://your-server:8080/api/ci/environments/abc1234567?dropDatabase=true \
  -H "X-API-Key: your-secret-api-key"
```

---

## Authentication

All CI endpoints require the `X-API-Key` header. This is independent of the browser-based login authentication — even if `app.auth.enabled=false`, the CI API always requires its own key.

| Header | Value |
|--------|-------|
| `X-API-Key` | The value configured in `ci.api.key` |

**Responses without valid key:**
- `ci.api.enabled=false` → `404 Not Found` (endpoints are invisible)
- Missing or invalid key → `401 Unauthorized`

---

## Configuration

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `ci.api.enabled` | `CI_API_ENABLED` | `false` | Enable the CI API |
| `ci.api.key` | `CI_API_KEY` | _(empty)_ | API key for authentication |
| `ci.default-ttl-minutes` | `CI_DEFAULT_TTL_MINUTES` | `120` | Default environment TTL (2 hours) |
| `ci.max-ttl-minutes` | `CI_MAX_TTL_MINUTES` | `480` | Maximum allowed TTL (8 hours) |

The TTL acts as a safety net: if a pipeline crashes and never calls destroy, the environment is automatically cleaned up after the TTL expires. The maximum TTL prevents creating environments that live forever.

---

## Endpoints

### Create Environment

```
POST /api/ci/environments
```

Creates a container, optionally restores a database dump, and starts the environment. This is a **synchronous** call — it waits for the full pipeline (image pull, container creation, database restore, container start) to complete before returning.

> **Timeout:** This can take several minutes depending on image size and dump size. Set your HTTP client timeout to at least **10 minutes**.

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `repository` | string | yes | Docker image repository (must be in `allowed.run.repositories`) |
| `tag` | string | yes | Image tag |
| `environmentName` | string | no | Container name (must be unique) |
| `envVars` | string[] | no | Environment variables in `KEY=VALUE` format |
| `ttlMinutes` | integer | no | Time-to-live in minutes (default: `ci.default-ttl-minutes`, capped at `ci.max-ttl-minutes`) |
| `memoryMb` | integer | no | Memory limit in MB (4–65536) |
| `databaseName` | string | no | Database name to create/use |
| `createDatabase` | boolean | no | Create the database if it doesn't exist |
| `dumpId` | string (UUID) | no | ID of a previously uploaded dump to restore |
| `snapshotId` | string (UUID) | no | ID of a snapshot to restore (alternative to `dumpId`) |
| `deleteDatabaseOnExpiration` | boolean | no | Drop the database when the environment expires |
| `selectedOptionalScripts` | string[] | no | Post-restore SQL scripts to execute |
| `pipelineId` | string | no | CI pipeline identifier (for tracking and filtering) |

**Response `200 OK`:**

```json
{
  "containerId": "abc1234567",
  "environmentName": "ci-pr-123",
  "status": "running",
  "portMappings": {
    "5432": "10042",
    "8080": "10043"
  },
  "databaseName": "test_db",
  "repository": "postgres",
  "tag": "16",
  "expiresAt": "2026-03-23T12:00:00",
  "pipelineId": "gh-run-456",
  "createdAt": "2026-03-23T10:00:00Z"
}
```

`portMappings` maps container ports (keys) to host ports (values). Connect to `host:hostPort` to reach the service.

**Errors:**

| Status | Cause |
|--------|-------|
| `400` | Invalid input (bad repository, tag, database name, memory, etc.) |
| `401` | Invalid or missing API key |
| `500` | Docker or restore failure |

---

### Destroy Environment

```
DELETE /api/ci/environments/{containerId}?dropDatabase=true
```

Stops and removes the container. Optionally drops the associated database.

**Path parameters:**

| Parameter | Description |
|-----------|-------------|
| `containerId` | The container ID returned by create (10-char hex) |

**Query parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `dropDatabase` | boolean | `false` | Also drop the associated database |

**Response `200 OK`:**

```json
{
  "destroyed": true,
  "containerId": "abc1234567",
  "databaseDropped": true,
  "databaseDropError": null
}
```

If database drop fails, `databaseDropped` is `false` and `databaseDropError` contains the error message. The container is still removed.

---

### Health Check

```
GET /api/ci/environments/{containerId}/health
```

Checks if the container is running and the database is accessible.

**Response `200 OK`:**

```json
{
  "containerId": "abc1234567",
  "containerRunning": true,
  "databaseAccessible": true,
  "containerStatus": "Up 5 minutes",
  "message": "Healthy"
}
```

`databaseAccessible` is `null` if no database is associated with the environment. The check works by opening a JDBC connection to verify the database exists.

---

### List Environments

```
GET /api/ci/environments?pipelineId=gh-run-456
```

Lists all CI-created environments. Only returns containers tagged with the CI label — UI-created containers are excluded.

**Query parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `pipelineId` | string | no | Filter by pipeline ID |

**Response `200 OK`:**

```json
[
  {
    "containerId": "abc1234567",
    "environmentName": "ci-pr-123",
    "status": "running",
    "portMappings": { "5432": "10042" },
    "databaseName": "test_db",
    "repository": "postgres",
    "tag": "16",
    "expiresAt": "2026-03-23T12:00:00Z",
    "pipelineId": "gh-run-456"
  }
]
```

---

## Pipeline Examples

### GitHub Actions

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Create test environment
        id: env
        run: |
          RESPONSE=$(curl -sf -X POST ${{ vars.DOCKER_HANDLER_URL }}/api/ci/environments \
            -H "X-API-Key: ${{ secrets.CI_API_KEY }}" \
            -H "Content-Type: application/json" \
            --max-time 600 \
            -d '{
              "repository": "postgres",
              "tag": "16",
              "environmentName": "ci-${{ github.run_id }}",
              "databaseName": "test_db",
              "createDatabase": true,
              "dumpId": "${{ vars.SEED_DUMP_ID }}",
              "deleteDatabaseOnExpiration": true,
              "ttlMinutes": 60,
              "pipelineId": "${{ github.run_id }}"
            }')
          echo "CONTAINER_ID=$(echo $RESPONSE | jq -r .containerId)" >> $GITHUB_OUTPUT
          echo "DB_PORT=$(echo $RESPONSE | jq -r '.portMappings["5432"]')" >> $GITHUB_OUTPUT

      - name: Wait for environment health
        run: |
          for i in {1..30}; do
            HEALTH=$(curl -sf ${{ vars.DOCKER_HANDLER_URL }}/api/ci/environments/${{ steps.env.outputs.CONTAINER_ID }}/health \
              -H "X-API-Key: ${{ secrets.CI_API_KEY }}")
            if echo "$HEALTH" | jq -e '.containerRunning and .databaseAccessible' > /dev/null 2>&1; then
              echo "Environment is healthy"
              break
            fi
            sleep 2
          done

      - name: Run tests
        env:
          DATABASE_URL: postgresql://user:pass@${{ vars.DOCKER_HANDLER_HOST }}:${{ steps.env.outputs.DB_PORT }}/test_db
        run: ./mvnw test

      - name: Destroy environment
        if: always()
        run: |
          curl -sf -X DELETE "${{ vars.DOCKER_HANDLER_URL }}/api/ci/environments/${{ steps.env.outputs.CONTAINER_ID }}?dropDatabase=true" \
            -H "X-API-Key: ${{ secrets.CI_API_KEY }}"
```

### GitLab CI

```yaml
test:
  script:
    - |
      RESPONSE=$(curl -sf -X POST ${DOCKER_HANDLER_URL}/api/ci/environments \
        -H "X-API-Key: ${CI_API_KEY}" \
        -H "Content-Type: application/json" \
        --max-time 600 \
        -d "{
          \"repository\": \"postgres\",
          \"tag\": \"16\",
          \"environmentName\": \"ci-${CI_PIPELINE_ID}\",
          \"databaseName\": \"test_db\",
          \"createDatabase\": true,
          \"deleteDatabaseOnExpiration\": true,
          \"ttlMinutes\": 60,
          \"pipelineId\": \"${CI_PIPELINE_ID}\"
        }")
      export CONTAINER_ID=$(echo $RESPONSE | jq -r .containerId)
    - ./mvnw test
  after_script:
    - |
      curl -sf -X DELETE "${DOCKER_HANDLER_URL}/api/ci/environments/${CONTAINER_ID}?dropDatabase=true" \
        -H "X-API-Key: ${CI_API_KEY}"
```

---

## Auto-Expiration

Every CI environment has a TTL. If the pipeline crashes and never calls destroy, the environment is automatically cleaned up:

1. Container is stopped and removed
2. Database is dropped (if `deleteDatabaseOnExpiration` was set to `true`)

The TTL defaults to `ci.default-ttl-minutes` (2 hours) and is capped at `ci.max-ttl-minutes` (8 hours). You can override per-request via `ttlMinutes`.

---

## Security

- API key is validated with constant-time comparison (prevents timing attacks)
- Repositories are restricted to the `allowed.run.repositories` whitelist
- All inputs are validated (container IDs, database names, UUIDs, env vars)
- The CI API key is independent of the browser login password
- API key is never logged or included in error responses
