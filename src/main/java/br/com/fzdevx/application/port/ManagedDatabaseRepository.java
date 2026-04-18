package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ManagedDatabase;

import java.util.List;
import java.util.Optional;


public interface ManagedDatabaseRepository {

    void save(ManagedDatabase db);

    void delete(String repository, String name);

    Optional<ManagedDatabase> find(String repository, String name);

    List<ManagedDatabase> findByRepository(String repository);

    List<ManagedDatabase> findAll();
}
