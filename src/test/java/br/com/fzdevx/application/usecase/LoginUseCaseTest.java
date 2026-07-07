package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.RbacSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

    @Mock
    RateLimitPort rateLimitPort;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    LoginUseCase useCase;

    @BeforeEach
    void setUp() {
        setField("authPassword", Optional.of("secret"));
        setField("rbacSettings", rbacSettings(true, "password"));
        lenient().when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
    }

    private void setField(String name, Object value) {
        try {
            Field f = LoginUseCase.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(useCase, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static RbacSettings rbacSettings(boolean enabled, String mode) {
        try {
            RbacSettings settings = new RbacSettings();
            Field enabledField = RbacSettings.class.getDeclaredField("authEnabled");
            enabledField.setAccessible(true);
            enabledField.set(settings, enabled);
            Field modeField = RbacSettings.class.getDeclaredField("authMode");
            modeField.setAccessible(true);
            modeField.set(settings, mode);
            return settings;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- success ----

    @Test
    void execute_correctPassword_returnsSuccess() {
        LoginResult result = useCase.execute("1.2.3.4", "secret");

        assertEquals(LoginResult.Status.SUCCESS, result.status());
        verify(rateLimitPort).recordSuccess("login:1.2.3.4");
    }

    @Test
    void execute_correctPassword_doesNotRecordFailure() {
        useCase.execute("1.2.3.4", "secret");

        verify(rateLimitPort, never()).recordFailure(anyString());
    }

    // ---- invalid password ----

    @Test
    void execute_wrongPassword_returnsInvalidPassword() {
        LoginResult result = useCase.execute("1.2.3.4", "wrong");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
    }

    @Test
    void execute_wrongPassword_recordsFailure() {
        useCase.execute("1.2.3.4", "wrong");

        verify(rateLimitPort).recordFailure("login:1.2.3.4");
    }

    @Test
    void execute_wrongPassword_doesNotRecordSuccess() {
        useCase.execute("1.2.3.4", "wrong");

        verify(rateLimitPort, never()).recordSuccess(anyString());
    }

    @Test
    void execute_nullPassword_returnsInvalidPassword() {
        LoginResult result = useCase.execute("1.2.3.4", null);

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
        verify(rateLimitPort).recordFailure("login:1.2.3.4");
    }

    @Test
    void execute_emptyPassword_returnsInvalidPassword() {
        LoginResult result = useCase.execute("1.2.3.4", "");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
        verify(rateLimitPort).recordFailure("login:1.2.3.4");
    }

    // ---- rate limited ----

    @Test
    void execute_rateLimited_returnsRateLimited() {
        when(rateLimitPort.checkRateLimit("login:1.2.3.4")).thenReturn(Optional.of(30L));

        LoginResult result = useCase.execute("1.2.3.4", "secret");

        assertEquals(LoginResult.Status.RATE_LIMITED, result.status());
        assertEquals(30, result.retryAfterSeconds());
    }

    @Test
    void execute_rateLimited_doesNotRecordFailureOrSuccess() {
        when(rateLimitPort.checkRateLimit("login:1.2.3.4")).thenReturn(Optional.of(30L));

        useCase.execute("1.2.3.4", "secret");

        verify(rateLimitPort, never()).recordFailure(anyString());
        verify(rateLimitPort, never()).recordSuccess(anyString());
    }

    // ---- key construction ----

    @Test
    void execute_usesLoginPrefixedKey() {
        useCase.execute("10.0.0.1", "secret");

        verify(rateLimitPort).checkRateLimit("login:10.0.0.1");
        verify(rateLimitPort).recordSuccess("login:10.0.0.1");
    }

    // ---- no password configured ----

    @Test
    void execute_noPasswordConfigured_returnsInvalidPassword() {
        setField("authPassword", Optional.empty());

        LoginResult result = useCase.execute("1.2.3.4", "anything");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
    }

    // ---- RBAC mode ----

    private User rbacUser(String username, String rawPassword) {
        return new User(username, PasswordHasher.hash(rawPassword), BuiltInRoles.OPERATOR_ID);
    }

    private void enableRbac() {
        setField("rbacSettings", rbacSettings(true, "rbac"));
    }

    @Test
    void executeRbac_correctCredentials_returnsSuccessWithUserId() {
        enableRbac();
        User user = rbacUser("alice", "pw123");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        LoginResult result = useCase.execute("1.2.3.4", "alice", "pw123");

        assertEquals(LoginResult.Status.SUCCESS, result.status());
        assertEquals(user.getId(), result.userId());
        verify(rateLimitPort).recordSuccess("login:1.2.3.4");
        verify(rateLimitPort).recordSuccess("login-user:alice");
    }

    @Test
    void executeRbac_correctCredentials_updatesLastLogin() {
        enableRbac();
        User user = rbacUser("alice", "pw123");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        useCase.execute("1.2.3.4", "alice", "pw123");

        verify(userRepository).save(argThat(saved -> saved.getLastLoginAt() != null));
    }

    @Test
    void executeRbac_wrongPassword_returnsInvalidAndRecordsBothKeys() {
        enableRbac();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(rbacUser("alice", "pw123")));

        LoginResult result = useCase.execute("1.2.3.4", "alice", "wrong");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
        verify(rateLimitPort).recordFailure("login:1.2.3.4");
        verify(rateLimitPort).recordFailure("login-user:alice");
        verify(userRepository, never()).save(any());
    }

    @Test
    void executeRbac_unknownUsername_returnsInvalidPassword() {
        enableRbac();
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        LoginResult result = useCase.execute("1.2.3.4", "ghost", "whatever");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
        verify(rateLimitPort).recordFailure("login:1.2.3.4");
    }

    @Test
    void executeRbac_disabledUser_returnsInvalidPassword() {
        enableRbac();
        User user = rbacUser("alice", "pw123");
        user.setEnabled(false);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        LoginResult result = useCase.execute("1.2.3.4", "alice", "pw123");

        assertEquals(LoginResult.Status.INVALID_PASSWORD, result.status());
        verify(userRepository, never()).save(any());
    }

    @Test
    void executeRbac_blankUsername_returnsInvalidPassword() {
        enableRbac();

        assertEquals(LoginResult.Status.INVALID_PASSWORD, useCase.execute("1.2.3.4", null, "pw").status());
        assertEquals(LoginResult.Status.INVALID_PASSWORD, useCase.execute("1.2.3.4", "  ", "pw").status());
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void executeRbac_userLevelRateLimit_returnsRateLimited() {
        enableRbac();
        when(rateLimitPort.checkRateLimit("login:1.2.3.4")).thenReturn(Optional.empty());
        when(rateLimitPort.checkRateLimit("login-user:alice")).thenReturn(Optional.of(60L));

        LoginResult result = useCase.execute("1.2.3.4", "Alice", "pw123");

        assertEquals(LoginResult.Status.RATE_LIMITED, result.status());
        assertEquals(60, result.retryAfterSeconds());
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void executeRbac_usernameLookupIsTrimmed() {
        enableRbac();
        User user = rbacUser("alice", "pw123");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        LoginResult result = useCase.execute("1.2.3.4", "  alice  ", "pw123");

        assertEquals(LoginResult.Status.SUCCESS, result.status());
    }
}
