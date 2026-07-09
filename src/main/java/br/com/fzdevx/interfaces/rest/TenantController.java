package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateTenantRequest;
import br.com.fzdevx.application.dto.TenantSummary;
import br.com.fzdevx.application.usecase.ManageTenantsUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Tenant;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/tenants")
@RequiresPermission(Permission.USERS_MANAGE)
public class TenantController {

    @Inject
    ManageTenantsUseCase manageTenantsUseCase;

    /** Names only, for creation/sharing selectors - open to any authenticated user. */
    @GET
    @RequiresPermission({})
    @Produces(MediaType.APPLICATION_JSON)
    public List<TenantSummary> list() {
        return manageTenantsUseCase.list().stream()
                .map(TenantSummary::of)
                .toList();
    }

    @GET
    @Path("/manage")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> manage() {
        // @RequiresPermission is any-of; the USERS_MANAGE + cross-tenant AND lives here
        manageTenantsUseCase.guardGlobalAdmin();
        Map<String, Long> memberCounts = manageTenantsUseCase.memberCounts();
        return manageTenantsUseCase.list().stream()
                .map(tenant -> withMemberCount(tenant, memberCounts.getOrDefault(tenant.getId(), 0L)))
                .toList();
    }

    /** Global repository/database option lists for the tenant entitlement editor. */
    @GET
    @Path("/entitlement-options")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<String>> entitlementOptions() {
        return manageTenantsUseCase.entitlementOptions();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateTenantRequest request) {
        Tenant created = manageTenantsUseCase.create(request);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Tenant update(@PathParam("id") String id, CreateTenantRequest request) {
        return manageTenantsUseCase.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> delete(@PathParam("id") String id) {
        manageTenantsUseCase.delete(id);
        return Map.of("success", true);
    }

    private Map<String, Object> withMemberCount(Tenant tenant, long memberCount) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", tenant.getId());
        entry.put("name", tenant.getName());
        entry.put("description", tenant.getDescription());
        entry.put("enabledRepositories", tenant.getEnabledRepositories());
        entry.put("enabledDatabases", tenant.getEnabledDatabases());
        entry.put("createdAt", tenant.getCreatedAt());
        entry.put("memberCount", memberCount);
        return entry;
    }
}
