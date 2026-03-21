package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.util.Optional;

// ⚠ SOLID — SRP: extracted password validation from DumpStorageService into its own class
@ApplicationScoped
public class PasswordValidationService {

    @Inject
    @ConfigProperty(name = "database.dump.upload-password")
    Optional<String> uploadPassword;

    @Inject
    @ConfigProperty(name = "database.dump.operations-password")
    Optional<String> operationsPassword;

    @Inject
    @ConfigProperty(name = "container.terminal.password")
    Optional<String> terminalPassword;

    public boolean validateUploadPassword(String password) {
        if (uploadPassword.isEmpty() || uploadPassword.get().isBlank()) return false;
        return MessageDigest.isEqual(
                uploadPassword.get().getBytes(),
                (password != null ? password : "").getBytes());
    }

    public boolean validateOperationsPassword(String password) {
        if (operationsPassword.isEmpty() || operationsPassword.get().isBlank()) return false;
        return MessageDigest.isEqual(
                operationsPassword.get().getBytes(),
                (password != null ? password : "").getBytes());
    }

    public boolean validateTerminalPassword(String password) {
        if (terminalPassword.isEmpty() || terminalPassword.get().isBlank()) return false;
        return MessageDigest.isEqual(
                terminalPassword.get().getBytes(),
                (password != null ? password : "").getBytes());
    }
}
