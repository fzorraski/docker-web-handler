package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.domain.model.AuditEntry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REST layer must send explicit nulls. JSON-B drops null properties by
 * default, which turns "this entry has no tenant" into "there is no tenantId
 * field" - indistinguishable, in the browser, from a field the API never had,
 * and the reason a === null check on the audit tenant silently failed.
 */
@QuarkusTest
class RestJsonbCustomizerTest {

    @Inject
    Jsonb jsonb;

    @Test
    void nullPropertiesAreSerialisedRatherThanOmitted() {
        AuditEntry untenanted = new AuditEntry(
                Instant.parse("2026-08-12T10:00:00Z"), "admin", "USER_CREATE", "teste", null, null);

        String json = jsonb.toJson(untenanted);

        assertTrue(json.contains("\"tenantId\":null"),
                "an untenanted entry must carry an explicit null, got: " + json);
        assertTrue(json.contains("\"detail\":null"),
                "every null property, not just the one we noticed: " + json);
    }

    @Test
    void presentValuesAreUnaffected() {
        AuditEntry tenanted = new AuditEntry(
                Instant.parse("2026-08-12T10:00:00Z"), "alice", "LOGIN", "session", "ip=1.1.1.1", "t1");

        String json = jsonb.toJson(tenanted);

        assertTrue(json.contains("\"tenantId\":\"t1\""));
        assertTrue(json.contains("\"actor\":\"alice\""));
    }
}
