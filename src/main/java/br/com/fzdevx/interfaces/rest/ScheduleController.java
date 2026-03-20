package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateScheduleRequest;
import br.com.fzdevx.application.dto.UpdateScheduleRequest;
import br.com.fzdevx.application.usecase.ManageScheduleUseCase;
import br.com.fzdevx.domain.model.ContainerSchedule;
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
public class ScheduleController {

    @Inject
    ManageScheduleUseCase manageScheduleUseCase;

    @Inject
    ContainerSchedulingService schedulingService;

    @Inject
    PasswordValidationService passwordValidationService;

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
        return manageScheduleUseCase.findAll();
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

        Optional<ContainerSchedule> schedule = manageScheduleUseCase.findById(id);
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
        return manageScheduleUseCase.findByContainerId(containerId);
    }

    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateScheduleRequest request) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateOperationsPassword(request.getOperationsPassword())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        ContainerSchedule schedule = manageScheduleUseCase.create(request);
        schedulingService.scheduleNext(schedule);
        return Response.status(Response.Status.CREATED).entity(schedule).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") String id, UpdateScheduleRequest request) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        ContainerSchedule schedule = manageScheduleUseCase.update(id, request);
        schedulingService.cancel(id);
        if (schedule.isEnabled()) {
            schedulingService.scheduleNext(schedule);
        }
        return Response.ok(schedule).build();
    }

    @POST
    @Path("/{id}/toggle")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggle(@PathParam("id") String id) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        ContainerSchedule schedule = manageScheduleUseCase.toggleEnabled(id);
        if (schedule.isEnabled()) {
            schedulingService.scheduleNext(schedule);
        } else {
            schedulingService.cancel(id);
        }
        return Response.ok(schedule).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id,
                           @HeaderParam("X-Schedule-Password") String password) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        schedulingService.cancel(id);
        manageScheduleUseCase.delete(id);
        return Response.ok(Map.of("success", true)).build();
    }

    @POST
    @Path("/{id}/execute-now")
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeNow(@PathParam("id") String id) {
        if (!schedulingService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Scheduling is disabled.")).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }

        var schedule = manageScheduleUseCase.findById(id);
        if (schedule.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Schedule not found.")).build();
        }

        if (!schedule.get().isEnabled()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot execute a disabled schedule.")).build();
        }

        schedulingService.executeNow(id);
        return Response.status(Response.Status.ACCEPTED)
                .entity(Map.of("message", "Execution triggered.")).build();
    }
}
