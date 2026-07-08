package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

@ApplicationScoped
public class ChangeOwnPasswordUseCase {

    @Inject
    UserRepository userRepository;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    AuthSessionManager sessionManager;

    @Inject
    AuditLogger auditLogger;

    /**
     * Changes the calling user's own password after verifying the current one.
     * All other sessions of the user are invalidated; the session performing
     * the change stays alive.
     */
    public void execute(String userId, String currentSessionId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found."));

        if (!PasswordHasher.verify(currentPassword, user.getPasswordHash())) {
            throw new InvalidInputException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < ManageUsersUseCase.MIN_PASSWORD_LENGTH) {
            throw new InvalidInputException("Password must be at least "
                    + ManageUsersUseCase.MIN_PASSWORD_LENGTH + " characters long.");
        }

        user.setPasswordHash(PasswordHasher.hash(newPassword));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        authorizationService.invalidateCache();
        sessionManager.invalidateSessionsForUserExcept(userId, currentSessionId);
        auditLogger.log("PASSWORD_CHANGE", user.getUsername(), null);
    }
}
