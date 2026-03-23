package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CiCreateEnvironmentRequest;
import br.com.fzdevx.application.dto.CiDestroyResponse;
import br.com.fzdevx.application.dto.CiEnvironmentResponse;
import br.com.fzdevx.application.dto.CiHealthResponse;
import br.com.fzdevx.application.usecase.CiEnvironmentUseCase;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@Path("/ci")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CiController {

    @Inject
    CiEnvironmentUseCase ciEnvironmentUseCase;

    @Inject
    @ConfigProperty(name = "ci.api.enabled", defaultValue = "false")
    boolean ciEnabled;

    @Inject
    @ConfigProperty(name = "ci.api.key")
    Optional<String> ciApiKey;

    void onStartup(@Observes StartupEvent event) {
        if (ciEnabled && (ciApiKey.isEmpty() || ciApiKey.get().isBlank())) {
            Log.warn("ci.api.enabled is true but ci.api.key is blank — all CI API requests will be rejected.");
        }
    }

    @POST
    @Path("/environments")
    public CiEnvironmentResponse createEnvironment(CiCreateEnvironmentRequest request) {
        return ciEnvironmentUseCase.createEnvironment(request);
    }

    @DELETE
    @Path("/environments/{containerId}")
    public CiDestroyResponse destroyEnvironment(@PathParam("containerId") String containerId,
                                                 @QueryParam("dropDatabase") @DefaultValue("false") boolean dropDatabase) {
        return ciEnvironmentUseCase.destroyEnvironment(containerId, dropDatabase);
    }

    @GET
    @Path("/environments/{containerId}/health")
    public CiHealthResponse healthCheck(@PathParam("containerId") String containerId) {
        return ciEnvironmentUseCase.healthCheck(containerId);
    }

    @GET
    @Path("/environments")
    public List<CiEnvironmentResponse> listEnvironments(@QueryParam("pipelineId") String pipelineId) {
        return ciEnvironmentUseCase.listEnvironments(pipelineId);
    }
}
