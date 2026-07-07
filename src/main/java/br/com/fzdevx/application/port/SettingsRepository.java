package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.RuntimeSettings;

public interface SettingsRepository {

    /** Never null - returns an empty (no overrides) instance when nothing is stored. */
    RuntimeSettings get();

    void save(RuntimeSettings settings);
}
