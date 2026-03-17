package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.RemoveImageUseCase;
import br.com.fzdevx.interfaces.rest.util.SseHelper; // ✦ CLEAN — extracted duplicated SSE logic into shared helper
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
            removeImageUseCase.execute(imageId, event -> SseHelper.sendEvent(sink, sse, event));
        } finally {
            SseHelper.closeSink(sink);
        }
    }
}
