package br.com.fzdevx.infrastructure.config;

import io.quarkus.jsonb.JsonbConfigCustomizer;
import jakarta.inject.Singleton;
import jakarta.json.bind.JsonbConfig;

/**
 * Makes REST responses carry explicit nulls.
 *
 * <p>JSON-B omits null properties by default, so a field the backend left null
 * arrives at the browser as a MISSING KEY rather than {@code null}. TypeScript
 * types across the UI declare those fields as {@code T | null}, which is then a
 * lie: a {@code === null} check compiles happily and fails at runtime. That is
 * exactly how untenanted audit entries came to render as an empty chip instead
 * of "System".</p>
 *
 * <p>Including nulls makes the wire format match the declared types everywhere,
 * rather than correcting every declaration and hoping the next one is right.</p>
 *
 * <p>Scope note: this configures the CDI {@code Jsonb} used by the REST layer.
 * {@code JdbcSupport.JSONB} is a separate instance built with
 * {@code JsonbBuilder.create()}, so jsonb columns and the audit file keep their
 * current, more compact shape - stored data is deliberately left alone.</p>
 */
@Singleton
public class RestJsonbCustomizer implements JsonbConfigCustomizer {

    @Override
    public void customize(JsonbConfig config) {
        config.withNullValues(true);
    }
}
