package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.shared.PasswordHasher;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.config.RbacSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class LoginUseCase {

    /**
     * Verified against when the username is unknown so lookup failures take
     * the same time as a real hash check (prevents username enumeration).
     */
    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-timing-equalizer");

    @Inject
    RateLimitPort rateLimitPort;

    @Inject
    RbacSettings rbacSettings;

    @Inject
    UserRepository userRepository;

    @Inject
    AuditLogger auditLogger;

    @Inject
    @ConfigProperty(name = "app.auth.password")
    Optional<String> authPassword;

    public LoginResult execute(String clientIp, String password) {
        return execute(clientIp, null, password);
    }

    public LoginResult execute(String clientIp, String username, String password) {
        if (rbacSettings.isRbacEnabled()) {
            return executeRbac(clientIp, username, password);
        }
        return executeLegacy(clientIp, password);
    }

    private LoginResult executeLegacy(String clientIp, String password) {
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

    private LoginResult executeRbac(String clientIp, String username, String password) {
        String ipKey = "login:" + clientIp;
        String userKey = username == null ? null : "login-user:" + username.trim().toLowerCase();

        Optional<Long> blocked = rateLimitPort.checkRateLimit(ipKey);
        if (blocked.isEmpty() && userKey != null) {
            blocked = rateLimitPort.checkRateLimit(userKey);
        }
        if (blocked.isPresent()) {
            return LoginResult.rateLimited(blocked.get());
        }

        Optional<User> user = username == null || username.isBlank()
                ? Optional.empty()
                : userRepository.findByUsername(username.trim());

        String hash = user.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = PasswordHasher.verify(password, hash);

        if (user.isEmpty() || !passwordMatches || !user.get().isEnabled()) {
            rateLimitPort.recordFailure(ipKey);
            if (userKey != null) {
                rateLimitPort.recordFailure(userKey);
            }
            auditLogger.logAs(username == null ? "unknown" : username.trim(),
                    "LOGIN_FAILED", "session", "ip=" + clientIp);
            return LoginResult.invalidPassword();
        }

        rateLimitPort.recordSuccess(ipKey);
        rateLimitPort.recordSuccess(userKey);

        User found = user.get();
        found.setLastLoginAt(Instant.now());
        userRepository.save(found);
        auditLogger.logAs(found.getUsername(), "LOGIN", "session", "ip=" + clientIp);
        return LoginResult.success(found.getId());
    }
}
