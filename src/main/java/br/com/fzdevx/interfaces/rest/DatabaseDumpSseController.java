package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.application.dto.WebhookPayload;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
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

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    WebhookService webhookService;

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
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        String[] lastError = {null};
        try {
            boolean success = restoreDumpUseCase.execute(request, event -> {
                SseHelper.sendEvent(sink, sse, event);
                if (event.getType() == ContainerEvent.EventType.ERROR) lastError[0] = event.getMessage();
            });
            if (success) {
                SseHelper.sendEvent(sink, sse, ContainerEvent.success("Complete",
                        "Dump restored successfully into '" + request.getTargetDatabase() + "'."));
            }
            if (request.isWebhookNotify()) {
                String filename = resolveDumpFilename(request);
                webhookService.fireAsync(webhookService.buildRestorePayload(
                        success ? WebhookPayload.Status.SUCCESS : WebhookPayload.Status.FAILURE,
                        request.getRepository(), request.getTargetDatabase(), filename, lastError[0]));
            }
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    private String resolveDumpFilename(RestoreDumpRequest request) {
        if (request.getDumpId() != null) {
            return dumpStorageService.findById(request.getDumpId())
                    .map(d -> d.getOriginalFilename())
                    .orElse(request.getDumpId());
        }
        if (request.getSnapshotId() != null) {
            return snapshotStorageService.findById(request.getSnapshotId())
                    .map(s -> s.getLabel() != null ? s.getLabel() : s.getStoredFilename())
                    .orElse(request.getSnapshotId());
        }
        return "";
    }
}
