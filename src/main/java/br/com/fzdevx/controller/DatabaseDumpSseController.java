package br.com.fzdevx.controller;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.model.RestoreDumpRequest;
import br.com.fzdevx.service.DumpStorageService;
import br.com.fzdevx.service.RequestStash;
import br.com.fzdevx.usecase.RestoreDumpUseCase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;

@Path("/database/dumps/sse")
public class DatabaseDumpSseController {

    @Inject
    RequestStash requestStash;

    @Inject
    RestoreDumpUseCase restoreDumpUseCase;

    @Inject
    DumpStorageService dumpStorageService;

    @POST
    @Path("/restore/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareRestore(RestoreDumpRequest request) {
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

        // Clear password before stashing
        request.setPassword(null);
        String ticket = requestStash.stashRestore(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/restore/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRestore(@PathParam("ticket") String ticket,
                              @Context SseEventSink sink,
                              @Context Sse sse) {
        RestoreDumpRequest request = requestStash.retrieveRestore(ticket);
        if (request == null) {
            sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            closeSink(sink);
            return;
        }

        try {
            boolean success = restoreDumpUseCase.execute(request, event -> sendEvent(sink, sse, event));
            if (success) {
                sendEvent(sink, sse, ContainerEvent.success("Complete",
                        "Dump restored successfully into '" + request.getTargetDatabase() + "'."));
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
