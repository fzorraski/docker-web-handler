package br.com.fzdevx.application.port;

import com.github.dockerjava.api.model.AuthConfig;

import java.util.List;


public interface RegistryPort {

    List<String> fetchTags(String repository) throws Exception;

    AuthConfig buildAuthConfig(String repository, String tag);

    String buildFullImageRef(String repository, String tag);
}
