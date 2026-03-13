package br.com.fzdevx.repository;

import br.com.fzdevx.model.DatabaseDump;

import java.util.List;
import java.util.Optional;

public interface DumpRepository {

    void save(DatabaseDump dump);

    void delete(String id);

    Optional<DatabaseDump> findById(String id);

    Optional<DatabaseDump> findByMd5Hash(String md5Hash);

    Optional<DatabaseDump> findByOriginalFilename(String originalFilename);

    List<DatabaseDump> findAll();
}
