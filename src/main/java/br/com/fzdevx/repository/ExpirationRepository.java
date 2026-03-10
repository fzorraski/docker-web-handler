package br.com.fzdevx.repository;

import br.com.fzdevx.model.ContainerExpiration;

import java.util.List;
import java.util.Optional;

public interface ExpirationRepository {

    void save(ContainerExpiration expiration);

    void delete(String shortId);

    Optional<ContainerExpiration> findByContainerId(String shortId);

    List<ContainerExpiration> findAll();
}
