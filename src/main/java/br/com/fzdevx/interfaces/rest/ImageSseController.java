package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.PruneImagesRequest;
import br.com.fzdevx.application.usecase.PruneImagesUseCase;
import br.com.fzdevx.application.usecase.RemoveImageUseCase;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;

@Path("/images/sse")
public class ImageSseController {

    @Inject
    RemoveImageUseCase removeImageUseCase;

    @Inject
    PruneImagesUseCase pruneImagesUseCase;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    RequestStash requestStash;

    @GET
    @Path("/remove/{imageId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("imageId") String imageId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        try {
            removeImageUseCase.execute(imageId, event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @POST
    @Path("/prune/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response preparePrune(PruneImagesRequest request) {
        if (!passwordValidationService.validateOperationsPassword(request.getPassword())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid password"))
                    .build();
        }

        request.setPassword(null);
        String ticket = requestStash.stashPrune(request);
        return Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/prune/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamPrune(@PathParam("ticket") String ticket,
                            @Context SseEventSink sink,
                            @Context Sse sse) {
        PruneImagesRequest request = requestStash.retrievePrune(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        try {
            pruneImagesUseCase.execute(request.getMinDays(), event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }
}
