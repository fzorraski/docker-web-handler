package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.LogPreset;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.interfaces.rest.util.LogAnalysisBroadcaster;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
import br.com.fzdevx.interfaces.rest.util.UploadFormParser;
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

import java.util.List;
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
    LogPresetProvider logPresetProvider;

    @Inject
    @ConfigProperty(name = "log.analyzer.enabled", defaultValue = "false")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "log.analyzer.default-preset", defaultValue = "WILDFLY")
    String defaultPresetName;

    @Inject
    @ConfigProperty(name = "log.analyzer.slow-threshold-ms", defaultValue = "1000")
    int defaultSlowThresholdMs;

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
            var duplicates = analyzeLogFileUseCase.findDuplicateFilenames(
                    request.getFilenames(), broadcaster.getActiveFilenames());
            if (!duplicates.isEmpty()) {
                request.cleanupTempFiles();
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "File(s) already analyzed: " + String.join(", ", duplicates),
                                "duplicates", duplicates))
                        .build();
            }
            // Register filenames as in-progress immediately to close the TOCTOU window
            // between prepare and stream. broadcastCompleted in streamAnalysis handles cleanup.
            String joinedFilenames = String.join(", ", request.getFilenames());
            broadcaster.broadcastStarted("", joinedFilenames, request.getFilenames());
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
        List<String> individualFilenames = request.getFilenames();
        // broadcastStarted already called at prepare time — no duplicate call here

        boolean[] succeeded = {false};
        String[] analysisId = {null};
        try {
            analyzeLogFileUseCase.analyzeWithProgress(
                    request.getTempFiles(), request.getFilenames(),
                    request.getPreset(), request.getSlowThresholdMs(), request.getOptions(),
                    event -> {
                        synchronized (sink) {
                            SseHelper.sendEvent(sink, sse, event);
                        }
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
                broadcaster.broadcastCompleted("", filenames, analysisId[0], request.getFilenames());
            } else {
                // Cancelled or failed — still broadcast so all clients clear the "in progress" banner
                broadcaster.broadcastCompleted("", filenames, "", request.getFilenames());
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

    // ── Compose with SSE progress ──────────────────────────────────────────────

    @POST
    @Path("/compose/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response prepareCompose(Map<String, Object> body) {
        if (!enabled) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Log analyzer is disabled.")).build();
        }
        try {
            Object idsObj = body.get("ids");
            if (!(idsObj instanceof List<?> rawList)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "'ids' must be an array.")).build();
            }
            List<String> ids = rawList.stream().map(Object::toString).toList();
            if (ids.size() < 2) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "At least 2 analysis IDs are required.")).build();
            }
            String presetName = (String) body.get("preset");
            Object slowObj = body.get("slowThresholdMs");
            int slowThresholdMs = slowObj instanceof Number n ? n.intValue() : defaultSlowThresholdMs;
            var options = UploadFormParser.parseAnalysisOptionsFromMap(body.get("options"));

            broadcaster.broadcastStarted("", "Compose", List.of());
            String ticket = requestStash.stashCompose(
                    new RequestStash.ComposeRequest(ids, presetName, slowThresholdMs, options));
            return Response.ok(Map.of("ticket", ticket)).build();
        } catch (Exception e) {
            Log.errorf("Failed to prepare compose: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to prepare compose.")).build();
        }
    }

    @GET
    @Path("/compose/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamCompose(@PathParam("ticket") String ticket,
                              @Context SseEventSink sink, @Context Sse sse) {
        RequestStash.ComposeRequest request = requestStash.retrieveCompose(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        LogPreset preset = request.presetName() != null
                ? logPresetProvider.byName(request.presetName())
                : logPresetProvider.byName(defaultPresetName);
        int slowThreshold = request.slowThresholdMs() > 0 ? request.slowThresholdMs() : defaultSlowThresholdMs;

        boolean[] succeeded = {false};
        String[] analysisId = {null};
        try {
            analyzeLogFileUseCase.composeWithProgress(
                    request.ids(), preset, slowThreshold, request.options(),
                    event -> {
                        synchronized (sink) {
                            SseHelper.sendEvent(sink, sse, event);
                        }
                        if (event.getType() == ContainerEvent.EventType.SUCCESS) {
                            succeeded[0] = true;
                            analysisId[0] = event.getDetail();
                        }
                    }, ticket,
                    evictedId -> broadcaster.broadcastDeleted(evictedId));
        } finally {
            if (succeeded[0] && analysisId[0] != null) {
                broadcaster.broadcastCompleted("", "Compose", analysisId[0], List.of());
            } else {
                broadcaster.broadcastCompleted("", "Compose", "", List.of());
            }
            SseHelper.closeSink(sink);
        }
    }

    @POST
    @Path("/compose/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelCompose(@PathParam("ticket") String ticket) {
        boolean cancelled = analyzeLogFileUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }
}
