package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ContainerSchedule;

import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {

    void save(ContainerSchedule schedule);

    void delete(String id);

    Optional<ContainerSchedule> findById(String id);

    List<ContainerSchedule> findByContainerId(String containerId);

    List<ContainerSchedule> findAll();
}
