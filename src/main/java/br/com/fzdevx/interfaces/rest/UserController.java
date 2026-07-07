package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateUserRequest;
import br.com.fzdevx.application.dto.UpdateUserRequest;
import br.com.fzdevx.application.dto.UserResponse;
import br.com.fzdevx.application.usecase.ManageUsersUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
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

import java.util.List;
import java.util.Map;

@Path("/users")
@RequiresPermission(Permission.USERS_MANAGE)
public class UserController {

    @Inject
    ManageUsersUseCase manageUsersUseCase;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<UserResponse> list() {
        return manageUsersUseCase.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateUserRequest request) {
        UserResponse created = manageUsersUseCase.create(request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public UserResponse update(@PathParam("id") String id, UpdateUserRequest request) {
        return manageUsersUseCase.update(id, request);
    }

    @POST
    @Path("/{id}/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> resetPassword(@PathParam("id") String id, Map<String, String> body) {
        manageUsersUseCase.resetPassword(id, body != null ? body.get("password") : null);
        return Map.of("success", true);
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> delete(@PathParam("id") String id) {
        manageUsersUseCase.delete(id);
        return Map.of("success", true);
    }
}
