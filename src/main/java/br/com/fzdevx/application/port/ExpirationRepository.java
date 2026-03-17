package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ContainerExpiration;

import java.util.List;
import java.util.Optional;

public interface ExpirationRepository {

    void save(ContainerExpiration expiration);

    void delete(String shortId);

    Optional<ContainerExpiration> findByContainerId(String shortId);

    List<ContainerExpiration> findByDatabaseName(String databaseName);

    List<ContainerExpiration> findAll();
}
