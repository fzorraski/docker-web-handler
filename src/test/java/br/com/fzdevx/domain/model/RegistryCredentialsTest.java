package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegistryCredentialsTest {

    @Test
    void threeArgConstructor_setsEmptyRegistryPath() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass");

        assertEquals("", creds.registryPath());
    }

    @Test
    void fourArgConstructor_setsRegistryPath() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass", "group/project/app");

        assertEquals("group/project/app", creds.registryPath());
    }

    @Test
    void resolvePathOrDefault_returnsRegistryPath_whenConfigured() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass", "sparkag/mywms/mywms-spk");

        assertEquals("sparkag/mywms/mywms-spk", creds.resolvePathOrDefault("mywms-spk"));
    }

    @Test
    void resolvePathOrDefault_returnsRepository_whenPathIsEmpty() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass", "");

        assertEquals("mywms-spk", creds.resolvePathOrDefault("mywms-spk"));
    }

    @Test
    void resolvePathOrDefault_returnsRepository_whenPathIsBlank() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass", "   ");

        assertEquals("mywms-spk", creds.resolvePathOrDefault("mywms-spk"));
    }

    @Test
    void resolvePathOrDefault_returnsRepository_whenPathIsNull() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass", null);

        assertEquals("mywms-spk", creds.resolvePathOrDefault("mywms-spk"));
    }

    @Test
    void resolvePathOrDefault_returnsRepository_whenThreeArgConstructorUsed() {
        var creds = new RegistryCredentials("https://reg.io", "user", "pass");

        assertEquals("myapp", creds.resolvePathOrDefault("myapp"));
    }

    @Test
    void isDockerHub_trueForEmptyUrl() {
        var creds = new RegistryCredentials("", "user", "pass", "some/path");

        assertTrue(creds.isDockerHub());
    }

    @Test
    void isDockerHub_falseForNonEmptyUrl() {
        var creds = new RegistryCredentials("https://registry.gitlab.com", "user", "pass", "some/path");

        assertFalse(creds.isDockerHub());
    }

    @Test
    void host_stripsProtocolAndTrailingSlash() {
        var creds = new RegistryCredentials("https://registry.gitlab.com/", "user", "pass", "group/app");

        assertEquals("registry.gitlab.com", creds.host());
    }
}
