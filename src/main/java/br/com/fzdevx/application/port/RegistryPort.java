package br.com.fzdevx.application.port;

import com.github.dockerjava.api.model.AuthConfig;

import java.util.List;

// ⚠ SOLID — DIP: port interface abstracting registry operations for use cases
public interface RegistryPort {

    List<String> fetchTags(String repository) throws Exception;

    AuthConfig buildAuthConfig(String repository, String tag);

    String buildFullImageRef(String repository, String tag);
}
