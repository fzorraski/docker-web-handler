package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    void save(User user);

    void delete(String id);

    Optional<User> findById(String id);

    Optional<User> findByUsername(String username);

    List<User> findAll();

    long count();
}
