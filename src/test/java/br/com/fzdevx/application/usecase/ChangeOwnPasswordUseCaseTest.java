package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.persistence.JsonFileUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChangeOwnPasswordUseCaseTest {

    @TempDir Path tempDir;
    @Mock AuthSessionManager sessionManager;
    @Mock AuditLogger auditLogger;

    ChangeOwnPasswordUseCase useCase;
    JsonFileUserRepository userRepository;
    User alice;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = new JsonFileUserRepository(tempDir.resolve("users.json").toString());
        alice = new User("alice", PasswordHasher.hash("old-secret"), BuiltInRoles.OPERATOR_ID);
        userRepository.save(alice);

        AuthorizationService authorizationService = new AuthorizationService();
        Field userRepoField = AuthorizationService.class.getDeclaredField("userRepository");
        userRepoField.setAccessible(true);
        userRepoField.set(authorizationService, userRepository);

        useCase = new ChangeOwnPasswordUseCase();
        useCase.userRepository = userRepository;
        useCase.authorizationService = authorizationService;
        useCase.sessionManager = sessionManager;
        useCase.auditLogger = auditLogger;
    }

    @Test
    void execute_correctCurrentPassword_updatesHashAndKeepsCurrentSession() {
        useCase.execute(alice.getId(), "session-1", "old-secret", "new-secret");

        User updated = userRepository.findById(alice.getId()).orElseThrow();
        assertTrue(PasswordHasher.verify("new-secret", updated.getPasswordHash()));
        assertFalse(PasswordHasher.verify("old-secret", updated.getPasswordHash()));
        verify(sessionManager).invalidateSessionsForUserExcept(alice.getId(), "session-1");
    }

    @Test
    void execute_wrongCurrentPassword_throws() {
        assertThrows(InvalidInputException.class,
                () -> useCase.execute(alice.getId(), "session-1", "wrong", "new-secret"));
        assertTrue(PasswordHasher.verify("old-secret",
                userRepository.findById(alice.getId()).orElseThrow().getPasswordHash()));
    }

    @Test
    void execute_shortNewPassword_throws() {
        assertThrows(InvalidInputException.class,
                () -> useCase.execute(alice.getId(), "session-1", "old-secret", "short"));
    }

    @Test
    void execute_unknownUser_throws() {
        assertThrows(EntityNotFoundException.class,
                () -> useCase.execute("no-such-user", "session-1", "old-secret", "new-secret"));
    }
}
