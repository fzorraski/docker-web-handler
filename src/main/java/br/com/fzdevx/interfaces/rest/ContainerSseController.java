package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.application.usecase.RemoveContainerUseCase;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
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
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        try {
            runContainerUseCase.execute(request, event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @GET
    @Path("/remove/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("containerId") String containerId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        try {
            removeContainerUseCase.execute(containerId, event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }
}
