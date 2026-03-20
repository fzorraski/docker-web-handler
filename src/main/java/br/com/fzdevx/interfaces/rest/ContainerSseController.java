package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.application.usecase.RemoveContainerUseCase;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.application.usecase.RunMigrationUseCase;
import br.com.fzdevx.application.usecase.StreamContainerLogsUseCase;
import br.com.fzdevx.application.usecase.StreamContainerStatsUseCase;
import br.com.fzdevx.domain.model.ContainerStats;
import br.com.fzdevx.interfaces.rest.util.SseHelper; // ✦ CLEAN — extracted duplicated SSE logic into shared helper
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;

@Path("/containers/sse")
public class ContainerSseController {

    @Inject
    RequestStash requestStash;

    @Inject
    RunContainerUseCase runContainerUseCase;

    @Inject
    RemoveContainerUseCase removeContainerUseCase;

    @Inject
    RunMigrationUseCase runMigrationUseCase;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    StreamContainerLogsUseCase streamContainerLogsUseCase;

    @Inject
    StreamContainerStatsUseCase streamContainerStatsUseCase;

    @POST
    @Path("/run/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareRun(RunContainerRequest request) {
        if (request.getOperationsPassword() != null && !request.getOperationsPassword().isBlank()) {
            if (!dumpStorageService.validateOperationsPassword(request.getOperationsPassword())) {
                return jakarta.ws.rs.core.Response.status(403)
                        .entity(Map.of("error", "Invalid operations password.")).build();
            }
            request.setOperationsPasswordValidated(true);
            request.setOperationsPassword(null);
        }
        String ticket = requestStash.stash(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/run/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRun(@PathParam("ticket") String ticket,
                          @Context SseEventSink sink,
                          @Context Sse sse) {
        RunContainerRequest request = requestStash.retrieve(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        try {
            runContainerUseCase.execute(request, event -> SseHelper.sendEvent(sink, sse, event), ticket);
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @POST
    @Path("/run/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelRun(@PathParam("ticket") String ticket) {
        boolean cancelled = runContainerUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }

    @POST
    @Path("/migration/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareMigration(RunMigrationRequest request) {
        if (!dumpStorageService.validateOperationsPassword(request.getPassword())) {
            return jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }
        request.setPassword(null);
        String ticket = requestStash.stashMigration(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/migration/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamMigration(@PathParam("ticket") String ticket,
                                @Context SseEventSink sink,
                                @Context Sse sse) {
        RunMigrationRequest request = requestStash.retrieveMigration(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            runMigrationUseCase.execute(request, event -> SseHelper.sendEvent(sink, sse, event), ticket);
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @POST
    @Path("/migration/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelMigration(@PathParam("ticket") String ticket) {
        boolean cancelled = runMigrationUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }

    @GET
    @Path("/remove/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("containerId") String containerId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid container ID."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            removeContainerUseCase.execute(containerId, event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @GET
    @Path("/logs/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamLogs(@PathParam("containerId") String containerId,
                           @Context SseEventSink sink,
                           @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid container ID."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            streamContainerLogsUseCase.execute(containerId,
                    event -> SseHelper.sendEvent(sink, sse, event),
                    () -> !sink.isClosed());
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @GET
    @Path("/stats/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamStats(@PathParam("containerId") String containerId,
                            @Context SseEventSink sink,
                            @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.closeSink(sink);
            return;
        }
        try {
            streamContainerStatsUseCase.execute(containerId,
                    stats -> {
                        if (!sink.isClosed()) {
                            sink.send(sse.newEventBuilder()
                                    .data(ContainerStats.class, stats)
                                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                                    .build());
                        }
                    },
                    () -> !sink.isClosed());
        } finally {
            SseHelper.closeSink(sink);
        }
    }
}
