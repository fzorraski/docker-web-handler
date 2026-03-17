package br.com.fzdevx.controller;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.model.CreateSnapshotRequest;
import br.com.fzdevx.service.DumpStorageService;
import br.com.fzdevx.service.RequestStash;
import br.com.fzdevx.usecase.CreateSnapshotUseCase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;

@Path("/database/snapshots/sse")
public class SnapshotSseController {

    @Inject
    RequestStash requestStash;

    @Inject
    CreateSnapshotUseCase createSnapshotUseCase;

    @Inject
    DumpStorageService dumpStorageService;

    @POST
    @Path("/create/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareSnapshot(CreateSnapshotRequest request) {
        if (!dumpStorageService.isEnabled()) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled."))
                    .build();
        }

        if (!dumpStorageService.validateOperationsPassword(request.getPassword())) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password."))
                    .build();
        }

        request.setPassword(null);
        String ticket = requestStash.stashSnapshot(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/create/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamSnapshot(@PathParam("ticket") String ticket,
                                @Context SseEventSink sink,
                                @Context Sse sse) {
        CreateSnapshotRequest request = requestStash.retrieveSnapshot(ticket);
        if (request == null) {
            sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            closeSink(sink);
            return;
        }

        try {
            boolean success = createSnapshotUseCase.executeSave(request, event -> sendEvent(sink, sse, event));
            if (success) {
                sendEvent(sink, sse, ContainerEvent.success("Complete",
                        "Snapshot of '" + request.getSourceDatabaseName() + "' created successfully."));
            }
        } finally {
            closeSink(sink);
        }
    }

    private void sendEvent(SseEventSink sink, Sse sse, ContainerEvent event) {
        if (sink.isClosed()) return;
        try {
            sink.send(sse.newEventBuilder()
                    .data(ContainerEvent.class, event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build());
        } catch (IllegalStateException ignored) {
        }
    }

    private void closeSink(SseEventSink sink) {
        if (sink.isClosed()) return;
        try {
            sink.close();
        } catch (IllegalStateException ignored) {
        }
    }
}
