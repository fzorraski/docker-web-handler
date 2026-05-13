package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.domain.exception.RateLimitedException;
import br.com.fzdevx.interfaces.rest.AuthController;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import io.quarkus.logging.Log;


@ApplicationScoped
public class PasswordValidationService {

    @Inject
    @ConfigProperty(name = "database.dump.upload-password")
    Optional<String> uploadPassword;

    @Inject
    @ConfigProperty(name = "database.dump.upload-password.required", defaultValue = "true")
    boolean uploadPasswordRequired;

    @Inject
    @ConfigProperty(name = "database.dump.operations-password")
    Optional<String> operationsPassword;

    @Inject
    @ConfigProperty(name = "database.dump.operations-password.required", defaultValue = "true")
    boolean operationsPasswordRequired;

    @Inject
    @ConfigProperty(name = "container.scheduling.password")
    Optional<String> schedulingPassword;

    @Inject
    @ConfigProperty(name = "container.scheduling.password.required", defaultValue = "true")
    boolean schedulingPasswordRequired;

    @Inject
    @ConfigProperty(name = "container.terminal.password")
    Optional<String> terminalPassword;

    @Inject
    @ConfigProperty(name = "container.terminal.password.required", defaultValue = "true")
    boolean terminalPasswordRequired;

    @Inject
    RateLimitPort rateLimitPort;

    @Inject
    jakarta.inject.Provider<HttpServerRequest> requestProvider;

    @Inject
    @ConfigProperty(name = "app.rate-limit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    public boolean validateUploadPassword(String password) {
        return validate("upload-pw", uploadPassword, uploadPasswordRequired, password);
    }

    public boolean validateOperationsPassword(String password) {
        return validate("ops-pw", operationsPassword, operationsPasswordRequired, password);
    }

    public boolean validateSchedulingPassword(String password) {
        return validate("schedule-pw", schedulingPassword, schedulingPasswordRequired, password);
    }

    public boolean validateTerminalPassword(String password) {
        return validate("terminal-pw", terminalPassword, terminalPasswordRequired, password);
    }

    public boolean isUploadPasswordRequired() { return uploadPasswordRequired || hasPassword(uploadPassword); }
    public boolean isOperationsPasswordRequired() { return operationsPasswordRequired || hasPassword(operationsPassword); }
    public boolean isSchedulingPasswordRequired() { return schedulingPasswordRequired || hasPassword(schedulingPassword); }
    public boolean isTerminalPasswordRequired() { return terminalPasswordRequired || hasPassword(terminalPassword); }

    /**
     * Constant-time password comparison. Returns false if configured is empty/blank.
     * Hashes both values with SHA-256 before comparing to prevent password length leakage.
     */
    public static boolean constantTimeEquals(Optional<String> configured, String input) {
        if (configured.isEmpty() || configured.get().isBlank()) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] configuredHash = digest.digest(configured.get().getBytes(StandardCharsets.UTF_8));
            byte[] inputHash = digest.digest((input != null ? input : "").getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(configuredHash, inputHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private boolean validate(String rateLimitCategory, Optional<String> configured, boolean required, String input) {
        if (!hasPassword(configured)) return !required;
        return compareWithRateLimit(rateLimitCategory, configured, input);
    }

    private boolean compareWithRateLimit(String category, Optional<String> configured, String input) {
        String rateLimitKey = resolveRateLimitKey(category);
        if (rateLimitKey != null) {
            Optional<Long> blocked = rateLimitPort.checkRateLimit(rateLimitKey);
            if (blocked.isPresent()) {
                throw new RateLimitedException(blocked.get());
            }
        }

        boolean valid = constantTimeEquals(configured, input);

        if (rateLimitKey != null) {
            if (valid) rateLimitPort.recordSuccess(rateLimitKey);
            else rateLimitPort.recordFailure(rateLimitKey);
        }

        return valid;
    }

    private String resolveRateLimitKey(String category) {
        String clientIp = getClientIp();
        if (clientIp == null) return null;
        return category + ":" + clientIp;
    }

    private String getClientIp() {
        try {
            HttpServerRequest request = requestProvider.get();
            return AuthController.extractClientIp(request, trustForwardedHeaders);
        } catch (Exception e) {
            Log.warn("Could not determine client IP for rate limiting — rate limiting is disabled for this request");
            return null;
        }
    }

    private boolean hasPassword(Optional<String> password) {
        return password.isPresent() && !password.get().isBlank();
    }
}
