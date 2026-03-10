package br.com.fzdevx.controller;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.model.RunContainerRequest;
import br.com.fzdevx.service.RequestStash;
import br.com.fzdevx.usecase.RemoveContainerUseCase;
import br.com.fzdevx.usecase.RunContainerUseCase;
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

    @POST
    @Path("/run/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> prepareRun(RunContainerRequest request) {
        String ticket = requestStash.stash(request);
        return Map.of("ticket", ticket);
    }

    @GET
    @Path("/run/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRun(@PathParam("ticket") String ticket,
                          @Context SseEventSink sink,
                          @Context Sse sse) {
        RunContainerRequest request = requestStash.retrieve(ticket);
        if (request == null) {
            sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            sink.close();
            return;
        }

        try {
            runContainerUseCase.execute(request, event -> sendEvent(sink, sse, event));
        } finally {
            sink.close();
        }
    }

    @GET
    @Path("/remove/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("containerId") String containerId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        try {
            removeContainerUseCase.execute(containerId, event -> sendEvent(sink, sse, event));
        } finally {
            sink.close();
        }
    }

    private void sendEvent(SseEventSink sink, Sse sse, ContainerEvent event) {
        if (sink.isClosed()) return;
        try {
            sink.send(sse.newEventBuilder()
                    .data(ContainerEvent.class, event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build());
        } catch (Exception ignored) {
        }
    }
}
