package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.application.dto.CreateSnapshotRequest;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.application.usecase.CreateSnapshotUseCase;
import br.com.fzdevx.interfaces.rest.util.SseHelper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;

@Path("/database/snapshots/sse")
@RequiresPermission(Permission.DATABASE_OPERATE)
public class SnapshotSseController {

    @Inject
    RequestStash requestStash;

    @Inject
    CreateSnapshotUseCase createSnapshotUseCase;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantEntitlements tenantEntitlements;

    @Inject
    br.com.fzdevx.application.port.TenantRepository tenantRepository;

    @Inject
    br.com.fzdevx.application.port.ManagedDatabaseRepository managedDatabaseRepository;

    @POST
    @Path("/create/prepare")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response prepareSnapshot(CreateSnapshotRequest request) {
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

        if (snapshotStorageService.isStorageFull()) {
            return jakarta.ws.rs.core.Response.status(507)
                    .entity(Map.of("error", "Snapshot storage quota exceeded. Free up space before creating a new snapshot."))
                    .build();
        }

        // tenant resolution happens here (request scope); the SSE stream runs off a ticket
        request.setTenantId(tenantVisibility.resolveCreationTenant(request.getTenantId()));
        if (request.getSharedWithTenants() != null) {
            for (String tenantId : request.getSharedWithTenants()) {
                if (tenantRepository.findById(tenantId).isEmpty()) {
                    return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "Unknown tenant: " + tenantId))
                            .build();
                }
            }
        }
        if (request.getRepository() != null) {
            tenantEntitlements.requireDatabaseAllowed(request.getRepository());
            if (request.getSourceDatabaseName() != null) {
                managedDatabaseRepository.find(request.getRepository(), request.getSourceDatabaseName())
                        .ifPresent(db -> tenantVisibility.requireVisible(db.getTenantId()));
            }
        }

        request.setPassword(null);
        String ticket = requestStash.stashSnapshot(request);
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    @GET
    @Path("/create/{ticket}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamSnapshot(@PathParam("ticket") String ticket,
                                @Context SseEventSink sink,
                                @Context Sse sse) {
        CreateSnapshotRequest request = requestStash.retrieveSnapshot(ticket);
        if (request == null) {
            SseHelper.sendEvent(sink, sse, ContainerEvent.error("Error", "Invalid or expired ticket."));
            SseHelper.closeSink(sink);
            return;
        }

        try {
            String snapshotId = createSnapshotUseCase.executeSave(request, event -> SseHelper.sendEvent(sink, sse, event));
            if (snapshotId != null) {
                SseHelper.sendEvent(sink, sse, ContainerEvent.success("Complete",
                        "Snapshot of '" + request.getSourceDatabaseName() + "' created successfully.",
                        snapshotId));
            }
        } finally {
            SseHelper.closeSink(sink);
        }
    }

}
