package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateScheduleRequest;
import br.com.fzdevx.application.dto.UpdateScheduleRequest;
import br.com.fzdevx.application.usecase.ManageScheduleUseCase;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/schedules")
@RequiresPermission(Permission.SCHEDULES_VIEW)
public class ScheduleController {

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    /** Creator visibility is its own permission (AUDIT_VIEW); strip it for callers without it. */
    private <T> java.util.List<T> withCreatorVisibility(java.util.List<T> items, java.util.function.BiConsumer<T, String> setter) {
        if (!currentUser.hasPermission(br.com.fzdevx.domain.model.auth.Permission.AUDIT_VIEW)) {
            items.forEach(item -> setter.accept(item, null));
        }
        return items;
    }

    /** Tenant-hidden schedules 404 like nonexistent ones; null means visible. */
    private Response guardVisible(String id) {
        Optional<ContainerSchedule> schedule = manageScheduleUseCase.findById(id);
        if (schedule.isPresent() && !tenantVisibility.canSee(schedule.get().getTenantId())) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Schedule not found.")).build();
        }
        return null;
    }

    @Inject
    ManageScheduleUseCase manageScheduleUseCase;

    @Inject
    ContainerSchedulingService schedulingService;

    @Inject
    PasswordValidationService passwordValidationService;

    /** Feature-flag probe: called by the navbar for every user, hence no permission required. */
    @RequiresPermission({})
    @GET
    @Path("/enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isEnabled() {
        return schedulingService.isEnabled();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ContainerSchedule> listAll() {
        if (!schedulingService.isEnabled()) {
            return Collections.emptyList();
        }
        List<ContainerSchedule> visible = tenantVisibility.visible(
                manageScheduleUseCase.findAll(), ContainerSchedule::getTenantId);
        return withCreatorVisibility(new java.util.ArrayList<>(visible), ContainerSchedule::setCreatedBy);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getById(@PathParam("id") String id) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        Optional<ContainerSchedule> schedule = manageScheduleUseCase.findById(id)
                .filter(s -> tenantVisibility.canSee(s.getTenantId()));
        if (schedule.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Schedule not found.")).build();
        }
        return Response.ok(schedule.get()).build();
    }

    @GET
    @Path("/container/{containerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ContainerSchedule> getByContainer(@PathParam("containerId") String containerId) {
        if (!schedulingService.isEnabled()) {
            return Collections.emptyList();
        }
        return tenantVisibility.visible(manageScheduleUseCase.findByContainerId(containerId),
                ContainerSchedule::getTenantId);
    }

    @RequiresPermission(Permission.SCHEDULES_MANAGE)
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateScheduleRequest request) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateSchedulingPassword(request.getOperationsPassword())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid scheduling password.")).build();
        }

        ContainerSchedule schedule = manageScheduleUseCase.createAndSchedule(request);
        return Response.status(Response.Status.CREATED).entity(schedule).build();
    }

    @RequiresPermission(Permission.SCHEDULES_MANAGE)
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") String id,
                           @HeaderParam("X-Schedule-Password") String password,
                           UpdateScheduleRequest request) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateSchedulingPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid scheduling password.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        Response hidden = guardVisible(id);
        if (hidden != null) return hidden;

        ContainerSchedule schedule = manageScheduleUseCase.updateAndReschedule(id, request);
        return Response.ok(schedule).build();
    }

    @RequiresPermission(Permission.SCHEDULES_MANAGE)
    @POST
    @Path("/{id}/toggle")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggle(@PathParam("id") String id,
                           @HeaderParam("X-Schedule-Password") String password) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateSchedulingPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid scheduling password.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        Response hidden = guardVisible(id);
        if (hidden != null) return hidden;

        ContainerSchedule schedule = manageScheduleUseCase.toggleAndReschedule(id);
        return Response.ok(schedule).build();
    }

    @RequiresPermission(Permission.SCHEDULES_MANAGE)
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id,
                           @HeaderParam("X-Schedule-Password") String password) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateSchedulingPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid scheduling password.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        Response hidden = guardVisible(id);
        if (hidden != null) return hidden;

        manageScheduleUseCase.deleteAndCancel(id);
        return Response.ok(Map.of("success", true)).build();
    }

    @RequiresPermission(Permission.SCHEDULES_MANAGE)
    @POST
    @Path("/{id}/execute-now")
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeNow(@PathParam("id") String id,
                               @HeaderParam("X-Schedule-Password") String password) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateSchedulingPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid scheduling password.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        Response hidden = guardVisible(id);
        if (hidden != null) return hidden;

        manageScheduleUseCase.executeNow(id);
        return Response.status(Response.Status.ACCEPTED)
                .entity(Map.of("message", "Execution triggered.")).build();
    }
}
