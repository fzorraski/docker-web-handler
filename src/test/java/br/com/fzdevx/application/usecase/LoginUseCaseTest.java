package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.port.RateLimitPort;
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

    @InjectMocks
    LoginUseCase useCase;

    @BeforeEach
    void setUp() {
        setField("authPassword", Optional.of("secret"));
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
}
