package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.application.dto.RemoveContainerRequest;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.application.dto.UpgradeContainerRequest;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.application.usecase.RemoveContainerUseCase;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.application.usecase.RunMigrationUseCase;
import br.com.fzdevx.application.usecase.UpgradeContainerUseCase;
import br.com.fzdevx.application.usecase.StreamContainerLogsUseCase;
import br.com.fzdevx.application.usecase.StreamContainerStatsUseCase;
import br.com.fzdevx.domain.model.ContainerStats;
import br.com.fzdevx.application.dto.WebhookPayload;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/containers/sse")
@RequiresPermission(Permission.CONTAINERS_VIEW)
public class ContainerSseController {

    @Inject
    RequestStash requestStash;

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    RunContainerUseCase runContainerUseCase;

    @Inject
    RemoveContainerUseCase removeContainerUseCase;

    @Inject
    RunMigrationUseCase runMigrationUseCase;

    @Inject
    UpgradeContainerUseCase upgradeContainerUseCase;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    StreamContainerLogsUseCase streamContainerLogsUseCase;

    @Inject
    StreamContainerStatsUseCase streamContainerStatsUseCase;

    @Inject
    WebhookService webhookService;

    @Inject
    ContainerListBroadcaster broadcaster;

    @GET
    @Path("/updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribeToUpdates(@Context SseEventSink sink, @Context Sse sse) {
        broadcaster.register(sink, sse);
    }

    @POST
    @Path("/lock")
    @Consumes(MediaType.APPLICATION_JSON)
    public void lockContainers(List<String> containerIds) {
        List<String> valid = sanitizeIds(containerIds);
        if (!valid.isEmpty()) {
            broadcaster.broadcastLocking(valid);
        }
    }

    @POST
    @Path("/unlock")
    @Consumes(MediaType.APPLICATION_JSON)
    public void unlockContainers(List<String> containerIds) {
        List<String> valid = sanitizeIds(containerIds);
        if (!valid.isEmpty()) {
            broadcaster.broadcastUnlocking(valid);
        }
    }

    private List<String> sanitizeIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream()
                .filter(id -> id != null && InputValidator.validateContainerId(id).isEmpty())
                .toList();
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/run/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareRun(RunContainerRequest request) {
        if (request.getOperationsPassword() != null && !request.getOperationsPassword().isBlank()) {
            if (!dumpStorageService.validateOperationsPassword(request.getOperationsPassword())) {
                return jakarta.ws.rs.core.Response.status(403)
                        .entity(Map.of("error", "Invalid operations password.")).build();
            }
            request.setOperationsPasswordValidated(true);
            request.setOperationsPassword(null);
        }
        String ticket = requestStash.stash(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
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

        String[] lastError = {null};
        boolean[] succeeded = {false};
        List<String> ports = new ArrayList<>();
        try {
            runContainerUseCase.execute(request, event -> {
                SseHelper.sendEvent(sink, sse, event);
                if (event.getType() == ContainerEvent.EventType.ERROR) lastError[0] = event.getMessage();
                if (event.getType() == ContainerEvent.EventType.SUCCESS) succeeded[0] = true;
                if (event.getMessage() != null && event.getMessage().startsWith("Port mapped:")) {
                    // Extract host port (left side of "hostPort → containerPort")
                    String mapping = event.getMessage().replace("Port mapped: ", "").trim();
                    String hostPort = mapping.split("\\s*\u2192\\s*")[0].trim();
                    ports.add(hostPort);
                }
            }, ticket);
        } finally {
            if (request.isWebhookNotify()) {
                webhookService.fireAsync(webhookService.buildContainerPayload(
                        succeeded[0] ? WebhookPayload.Status.SUCCESS : WebhookPayload.Status.FAILURE,
                        request.getRepository(), request.getTag(), request.getContainerName(),
                        String.join(", ", ports), lastError[0]));
            }
            if (succeeded[0]) broadcaster.notifyChange();
            SseHelper.closeSink(sink);
        }
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/run/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelRun(@PathParam("ticket") String ticket) {
        boolean cancelled = runContainerUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/migration/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareMigration(RunMigrationRequest request) {
        if (!dumpStorageService.validateOperationsPassword(request.getPassword())) {
            return jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }
        request.setPassword(null);
        String ticket = requestStash.stashMigration(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @GET
    @Path("/migration/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamMigration(@PathParam("ticket") String ticket,
                                @Context SseEventSink sink,
                                @Context Sse sse) {
        RunMigrationRequest request = requestStash.retrieveMigration(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            runMigrationUseCase.execute(request, event -> SseHelper.sendEvent(sink, sse, event), ticket);
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/migration/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelMigration(@PathParam("ticket") String ticket) {
        boolean cancelled = runMigrationUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/upgrade/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareUpgrade(UpgradeContainerRequest request) {
        if (!dumpStorageService.validateOperationsPassword(request.getPassword())) {
            return jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }
        request.setPassword(null);
        String ticket = requestStash.stashUpgrade(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @GET
    @Path("/upgrade/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamUpgrade(@PathParam("ticket") String ticket,
                              @Context SseEventSink sink,
                              @Context Sse sse) {
        UpgradeContainerRequest request = requestStash.retrieveUpgrade(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            upgradeContainerUseCase.execute(request, event -> SseHelper.sendEvent(sink, sse, event), ticket);
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/upgrade/cancel/{ticket}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> cancelUpgrade(@PathParam("ticket") String ticket) {
        boolean cancelled = upgradeContainerUseCase.cancel(ticket);
        return Map.of("cancelled", cancelled);
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Path("/remove/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareRemove(RemoveContainerRequest request) {
        if (InputValidator.validateContainerId(request.getContainerId()).isPresent()) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity(Map.of("error", "Invalid container ID.")).build();
        }
        if (request.isDeleteDatabase()) {
            // dropping the database alongside the container needs the dedicated delete permission
            if (!currentUser.hasPermission(Permission.DATABASE_DELETE)) {
                return jakarta.ws.rs.core.Response.status(403)
                        .entity(Map.of("code", "FORBIDDEN",
                                "message", "You do not have permission to perform this action.")).build();
            }
            if (request.getRepository() == null || InputValidator.validateRepository(request.getRepository()).isPresent()) {
                return jakarta.ws.rs.core.Response.status(400)
                        .entity(Map.of("error", "Invalid repository.")).build();
            }
            if (request.getDatabaseName() == null || InputValidator.validateDatabaseName(request.getDatabaseName()).isPresent()) {
                return jakarta.ws.rs.core.Response.status(400)
                        .entity(Map.of("error", "Invalid database name.")).build();
            }
            if (!dumpStorageService.validateOperationsPassword(request.getOperationsPassword())) {
                return jakarta.ws.rs.core.Response.status(403)
                        .entity(Map.of("error", "Invalid operations password.")).build();
            }
        }
        request.setOperationsPassword(null);
        String ticket = requestStash.stashRemove(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @GET
    @Path("/remove/ticket/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemoveWithTicket(@PathParam("ticket") String ticket,
                                       @Context SseEventSink sink,
                                       @Context Sse sse) {
        RemoveContainerRequest request = requestStash.retrieveRemove(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }
        boolean[] succeeded = {false};
        try {
            removeContainerUseCase.execute(
                    request.getContainerId(),
                    request.isDeleteDatabase(),
                    request.getRepository(),
                    request.getDatabaseName(),
                    event -> {
                        SseHelper.sendEvent(sink, sse, event);
                        if (event.getType() == ContainerEvent.EventType.SUCCESS) succeeded[0] = true;
                    });
        } finally {
            if (succeeded[0]) broadcaster.notifyChange();
            SseHelper.closeSink(sink);
        }
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @GET
    @Path("/remove/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamRemove(@PathParam("containerId") String containerId,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid container ID."));
            SseHelper.closeSink(sink);
            return;
        }
        boolean[] succeeded = {false};
        try {
            removeContainerUseCase.execute(containerId, event -> {
                SseHelper.sendEvent(sink, sse, event);
                if (event.getType() == ContainerEvent.EventType.SUCCESS) succeeded[0] = true;
            });
        } finally {
            if (succeeded[0]) broadcaster.notifyChange();
            SseHelper.closeSink(sink);
        }
    }

    @RequiresPermission(Permission.LOGS_VIEW)
    @GET
    @Path("/logs/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamLogs(@PathParam("containerId") String containerId,
                           @Context SseEventSink sink,
                           @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid container ID."));
            SseHelper.closeSink(sink);
            return;
        }
        try {
            streamContainerLogsUseCase.execute(containerId,
                    event -> SseHelper.sendEvent(sink, sse, event),
                    () -> !sink.isClosed());
        } finally {
            SseHelper.closeSink(sink);
        }
    }

    @GET
    @Path("/stats/{containerId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamStats(@PathParam("containerId") String containerId,
                            @Context SseEventSink sink,
                            @Context Sse sse) {
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            SseHelper.closeSink(sink);
            return;
        }
        try {
            streamContainerStatsUseCase.execute(containerId,
                    stats -> {
                        if (!sink.isClosed()) {
                            sink.send(sse.newEventBuilder()
                                    .data(ContainerStats.class, stats)
                                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                                    .build());
                        }
                    },
                    () -> !sink.isClosed());
        } finally {
            SseHelper.closeSink(sink);
        }
    }
}
