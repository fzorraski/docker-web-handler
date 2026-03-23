package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.util.Optional;


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
    @ConfigProperty(name = "container.terminal.password")
    Optional<String> terminalPassword;

    @Inject
    @ConfigProperty(name = "container.terminal.password.required", defaultValue = "true")
    boolean terminalPasswordRequired;

    public boolean validateUploadPassword(String password) {
        return validate(uploadPassword, uploadPasswordRequired, password);
    }

    public boolean validateOperationsPassword(String password) {
        return validate(operationsPassword, operationsPasswordRequired, password);
    }

    public boolean validateTerminalPassword(String password) {
        return validate(terminalPassword, terminalPasswordRequired, password);
    }

    public boolean isUploadPasswordRequired() { return uploadPasswordRequired && hasPassword(uploadPassword); }
    public boolean isOperationsPasswordRequired() { return operationsPasswordRequired && hasPassword(operationsPassword); }
    public boolean isTerminalPasswordRequired() { return terminalPasswordRequired && hasPassword(terminalPassword); }

    /**
     * Constant-time password comparison. Returns false if configured is empty/blank.
     * Shared utility for any password check that doesn't use the required/tier pattern.
     */
    public static boolean constantTimeEquals(Optional<String> configured, String input) {
        if (configured.isEmpty() || configured.get().isBlank()) return false;
        return MessageDigest.isEqual(
                configured.get().getBytes(),
                (input != null ? input : "").getBytes());
    }

    private boolean validate(Optional<String> configured, boolean required, String input) {
        if (!required) return true;
        if (!hasPassword(configured)) return true;
        return constantTimeEquals(configured, input);
    }

    private boolean hasPassword(Optional<String> password) {
        return password.isPresent() && !password.get().isBlank();
    }
}
