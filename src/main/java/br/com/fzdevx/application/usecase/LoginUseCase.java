package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class LoginUseCase {

    @Inject
    RateLimitPort rateLimitPort;

    @Inject
    @ConfigProperty(name = "app.auth.password")
    Optional<String> authPassword;

    public LoginResult execute(String clientIp, String password) {
        String key = "login:" + clientIp;

        Optional<Long> blocked = rateLimitPort.checkRateLimit(key);
        if (blocked.isPresent()) {
            return LoginResult.rateLimited(blocked.get());
        }

        if (!PasswordValidationService.constantTimeEquals(authPassword, password)) {
            rateLimitPort.recordFailure(key);
            return LoginResult.invalidPassword();
        }

        rateLimitPort.recordSuccess(key);
        return LoginResult.success();
    }
}
