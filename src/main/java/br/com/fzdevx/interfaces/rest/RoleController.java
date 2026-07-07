package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateRoleRequest;
import br.com.fzdevx.application.usecase.ManageRolesUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Path("/roles")
@RequiresPermission(Permission.USERS_MANAGE)
public class RoleController {

    @Inject
    ManageRolesUseCase manageRolesUseCase;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Role> list() {
        return manageRolesUseCase.list();
    }

    /** The fixed permission catalog, for the custom-role builder UI. */
    @GET
    @Path("/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> permissionCatalog() {
        return Arrays.stream(Permission.values())
                .map(p -> Map.of("name", p.name(), "category", p.getCategory()))
                .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateRoleRequest request) {
        Role created = manageRolesUseCase.create(request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Role update(@PathParam("id") String id, CreateRoleRequest request) {
        return manageRolesUseCase.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> delete(@PathParam("id") String id) {
        manageRolesUseCase.delete(id);
        return Map.of("success", true);
    }
}
