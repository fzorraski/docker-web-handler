package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import com.github.dockerjava.api.model.Container;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/stats")
public class StatsController {

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    DockerImagePort dockerImagePort;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    RestoreDumpUseCase restoreDumpUseCase;

    private String startedAt;

    @PostConstruct
    void init() {
        startedAt = Instant.now().toString();
    }

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getSummary() {
        List<Container> containers = dockerContainerPort.listContainers(true);
        int containerCount = (int) containers.stream()
                .filter(c -> c.getImage() != null && !c.getImage().startsWith(Constants.DOCKER_WEB_HANDLER_IMAGE))
                .count();

        int imageCount = (int) dockerImagePort.listImages().stream()
                .filter(img -> img.getRepoTags() != null && img.getRepoTags().length > 0
                        && !img.getRepoTags()[0].startsWith(Constants.DOCKER_WEB_HANDLER_IMAGE))
                .count();

        int dumpCount = dumpStorageService.isEnabled() ? dumpStorageService.findAll().size() : 0;
        int snapshotCount = dumpStorageService.isEnabled() ? snapshotStorageService.findAll().size() : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("containers", containerCount);
        result.put("images", imageCount);
        result.put("dumps", dumpCount);
        result.put("snapshots", snapshotCount);
        result.put("restores", restoreDumpUseCase.getTotalRestores());
        result.put("startedAt", startedAt);
        return result;
    }
}
