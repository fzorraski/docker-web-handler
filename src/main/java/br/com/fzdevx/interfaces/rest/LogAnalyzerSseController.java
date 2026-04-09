package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.interfaces.rest.util.LogAnalysisBroadcaster;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.util.Map;

@Path("/logs/analyzer/sse")
public class LogAnalyzerSseController {

    @Inject
    LogAnalyzerController logAnalyzerController;

    @Inject
    AnalyzeLogFileUseCase analyzeLogFileUseCase;

    @Inject
    RequestStash requestStash;

    @Inject
    LogAnalysisBroadcaster broadcaster;

    @Inject
    @ConfigProperty(name = "log.analyzer.enabled", defaultValue = "false")
    boolean enabled;

    @GET
    @Path("/updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribeToUpdates(@Context SseEventSink sink, @Context Sse sse) {
        broadcaster.register(sink, sse);
    }

    @POST
    @Path("/viewing")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> setViewing(Map<String, String> body) {
        String clientToken = body.getOrDefault("clientToken", "");
        String analysisId = body.get("analysisId");
        if (!clientToken.isBlank()) {
            broadcaster.setViewing(clientToken, analysisId);
        }
        return Map.of("ok", true);
    }

    @POST
    @Path("/upload/prepare")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response prepareUpload(MultipartFormDataInput input) {
        if (!enabled) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Log analyzer is disabled.")).build();
        }
        try {
            AnalyzeLogFileRequest request = logAnalyzerController.parseUploadForm(input);
            String ticket = requestStash.stashLogAnalysis(request);
            return Response.ok(Map.of("ticket", ticket)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            Log.errorf("Failed to prepare log analysis: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to prepare analysis.")).build();
        }
    }

    @GET
    @Path("/upload/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamAnalysis(@PathParam("ticket") String ticket,
                               @Context SseEventSink sink,
                               @Context Sse sse) {
        AnalyzeLogFileRequest request = requestStash.retrieveLogAnalysis(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        String filenames = String.join(", ", request.getFilenames());
        broadcaster.broadcastStarted("", filenames);

        boolean[] succeeded = {false};
        String[] analysisId = {null};
        try {
            analyzeLogFileUseCase.analyzeWithProgress(
                    request.getTempFiles(), request.getFilenames(),
                    request.getPreset(), request.getSlowThresholdMs(), request.getOptions(),
                    event -> {
                        SseHelper.sendEvent(sink, sse, event);
                        if (event.getType() == ContainerEvent.EventType.SUCCESS) {
                            succeeded[0] = true;
                            analysisId[0] = event.getDetail();
                        }
                    }, ticket,
                    evictedId -> broadcaster.broadcastDeleted(evictedId));
        } finally {
            if (succeeded[0] && analysisId[0] != null) {
                if (request.getLabel() != null && !request.getLabel().isBlank()) {
                    String lbl = request.getLabel().trim();
                    var analysis = analyzeLogFileUseCase.get(analysisId[0]);
                    if (analysis != null) analysis.setLabel(lbl.length() > 50 ? lbl.substring(0, 50) : lbl);
                }
                broadcaster.broadcastCompleted("", filenames, analysisId[0]);
            }
            request.cleanupTempFiles();
            SseHelper.closeSink(sink);
        }
    }

    @POST
    @Path("/upload/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelAnalysis(@PathParam("ticket") String ticket) {
        boolean cancelled = analyzeLogFileUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }
}
