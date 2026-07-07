package br.com.fzdevx.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class RbacSettings {

    public static final String MODE_PASSWORD = "password";
    public static final String MODE_RBAC = "rbac";

    @ConfigProperty(name = "app.auth.enabled", defaultValue = "false")
    boolean authEnabled;

    @ConfigProperty(name = "app.auth.mode", defaultValue = MODE_PASSWORD)
    String authMode;

    public boolean isRbacEnabled() {
        return authEnabled && MODE_RBAC.equalsIgnoreCase(authMode);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
