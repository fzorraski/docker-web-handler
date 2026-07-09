package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    void save(User user);

    /**
     * Atomically mutates the stored user - concurrent edits of other fields
     * are not clobbered by a stale full-object save. Returns false when the
     * user no longer exists.
     */
    boolean update(String id, java.util.function.Consumer<User> mutator);

    void delete(String id);

    Optional<User> findById(String id);

    Optional<User> findByUsername(String username);

    List<User> findAll();

    long count();
}
