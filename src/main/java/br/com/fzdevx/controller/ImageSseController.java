package br.com.fzdevx.controller;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.usecase.RemoveImageUseCase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

@Path("/images/sse")
public class ImageSseController {

    @Inject
    RemoveImageUseCase removeImageUseCase;

    @GET
    @Path("/remove/{imageId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("imageId") String imageId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        try {
            removeImageUseCase.execute(imageId, event -> sendEvent(sink, sse, event));
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
