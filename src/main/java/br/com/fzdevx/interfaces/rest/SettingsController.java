package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.ManageSettingsUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/settings")
@RequiresPermission(Permission.SYSTEM_CONFIG)
public class SettingsController {

    @Inject
    ManageSettingsUseCase manageSettingsUseCase;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list() {
        return manageSettingsUseCase.describe();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> update(Map<String, Object> changes) {
        return manageSettingsUseCase.update(changes);
    }

    @DELETE
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> reset(@PathParam("key") String key) {
        return manageSettingsUseCase.reset(key);
    }
}
