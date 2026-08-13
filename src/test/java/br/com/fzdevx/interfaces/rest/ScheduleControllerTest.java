package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateScheduleRequest;
import br.com.fzdevx.application.dto.UpdateScheduleRequest;
import br.com.fzdevx.application.usecase.ManageScheduleUseCase;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleControllerTest {

    private static final String VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_PASSWORD = "secret";

    @Mock ManageScheduleUseCase manageScheduleUseCase;
    @Mock ContainerSchedulingService schedulingService;
    @Mock PasswordValidationService passwordValidationService;

    @InjectMocks
    ScheduleController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        controller.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
    }

    private ContainerSchedule makeSchedule() {
        return new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_delegatesToService() {
        when(schedulingService.isEnabled()).thenReturn(true);
        assertTrue(controller.isEnabled());
    }

    @Test
    void isEnabled_returnsFalse() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertFalse(controller.isEnabled());
    }

    // ---- listAll ----

    @Test
    void listAll_enabled_returnsList() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(manageScheduleUseCase.findAll()).thenReturn(List.of(makeSchedule()));
        assertEquals(1, controller.listAll().size());
    }

    @Test
    void listAll_disabled_returnsEmpty() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertTrue(controller.listAll().isEmpty());
    }

    // ---- getById ----

    @Test
    void getById_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.getById(VALID_UUID).getStatus());
    }

    @Test
    void getById_invalidUuid_returnsBadRequest() {
        when(schedulingService.isEnabled()).thenReturn(true);
        assertEquals(400, controller.getById("bad!").getStatus());
    }

    @Test
    void getById_notFound_returns404() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(manageScheduleUseCase.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertEquals(404, controller.getById(VALID_UUID).getStatus());
    }

    @Test
    void getById_found_returns200() {
        when(schedulingService.isEnabled()).thenReturn(true);
        ContainerSchedule s = makeSchedule();
        when(manageScheduleUseCase.findById(VALID_UUID)).thenReturn(Optional.of(s));
        Response response = controller.getById(VALID_UUID);
        assertEquals(200, response.getStatus());
        assertSame(s, response.getEntity());
    }

    // ---- getByContainer ----

    @Test
    void getByContainer_disabled_returnsEmpty() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertTrue(controller.getByContainer("abc123def4").isEmpty());
    }

    @Test
    void getByContainer_enabled_delegatesToUseCase() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(manageScheduleUseCase.findByContainerId("abc123def4")).thenReturn(List.of(makeSchedule()));
        assertEquals(1, controller.getByContainer("abc123def4").size());
    }

    // ---- create ----

    @Test
    void create_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.create(new CreateScheduleRequest()).getStatus());
    }

    @Test
    void create_invalidPassword_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(true);
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setOperationsPassword("wrong");
        when(passwordValidationService.validateSchedulingPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.create(req).getStatus());
    }

    @Test
    void create_valid_returns201() {
        when(schedulingService.isEnabled()).thenReturn(true);
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setOperationsPassword(VALID_PASSWORD);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        ContainerSchedule s = makeSchedule();
        when(manageScheduleUseCase.createAndSchedule(req)).thenReturn(s);

        Response response = controller.create(req);
        assertEquals(201, response.getStatus());
        assertSame(s, response.getEntity());
    }

    // ---- update ----

    @Test
    void update_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.update(VALID_UUID, VALID_PASSWORD, new UpdateScheduleRequest()).getStatus());
    }

    @Test
    void update_invalidPassword_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.update(VALID_UUID, "wrong", new UpdateScheduleRequest()).getStatus());
    }

    @Test
    void update_invalidUuid_returnsBadRequest() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        assertEquals(400, controller.update("bad!", VALID_PASSWORD, new UpdateScheduleRequest()).getStatus());
    }

    @Test
    void update_valid_returns200() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        UpdateScheduleRequest req = new UpdateScheduleRequest();
        ContainerSchedule s = makeSchedule();
        when(manageScheduleUseCase.updateAndReschedule(VALID_UUID, req)).thenReturn(s);

        Response response = controller.update(VALID_UUID, VALID_PASSWORD, req);
        assertEquals(200, response.getStatus());
    }

    // ---- toggle ----

    @Test
    void toggle_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.toggle(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    @Test
    void toggle_invalidPassword_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.toggle(VALID_UUID, "wrong").getStatus());
    }

    @Test
    void toggle_invalidUuid_returnsBadRequest() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        assertEquals(400, controller.toggle("bad!", VALID_PASSWORD).getStatus());
    }

    @Test
    void toggle_valid_returns200() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        when(manageScheduleUseCase.toggleAndReschedule(VALID_UUID)).thenReturn(makeSchedule());
        assertEquals(200, controller.toggle(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    // ---- delete ----

    @Test
    void delete_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.delete(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    @Test
    void delete_invalidPassword_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.delete(VALID_UUID, "wrong").getStatus());
    }

    @Test
    void delete_invalidUuid_returnsBadRequest() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        assertEquals(400, controller.delete("bad!", VALID_PASSWORD).getStatus());
    }

    @Test
    void delete_valid_returns200() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        Response response = controller.delete(VALID_UUID, VALID_PASSWORD);
        assertEquals(200, response.getStatus());
        verify(manageScheduleUseCase).deleteAndCancel(VALID_UUID);
    }

    // ---- executeNow ----

    @Test
    void executeNow_disabled_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.executeNow(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    @Test
    void executeNow_invalidPassword_returnsForbidden() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.executeNow(VALID_UUID, "wrong").getStatus());
    }

    @Test
    void executeNow_invalidUuid_returnsBadRequest() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        assertEquals(400, controller.executeNow("bad!", VALID_PASSWORD).getStatus());
    }

    @Test
    void executeNow_valid_returns202() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        Response response = controller.executeNow(VALID_UUID, VALID_PASSWORD);
        assertEquals(202, response.getStatus());
        verify(manageScheduleUseCase).executeNow(VALID_UUID);
    }

    // ---- updateSharing ----

    @Test
    void updateSharing_shareRecipient_cannotReshare() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        ContainerSchedule schedule = makeSchedule();
        schedule.setId(VALID_UUID);
        schedule.setTenantId("owner-tenant");
        schedule.setSharedWithTenants(java.util.List.of("recipient-tenant"));
        when(manageScheduleUseCase.findById(VALID_UUID)).thenReturn(Optional.of(schedule));

        var recipient = new br.com.fzdevx.infrastructure.config.CurrentUser();
        recipient.set("u1", "bob",
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.SCHEDULES_MANAGE),
                java.util.Set.of("recipient-tenant"));
        controller.currentUser = recipient;
        controller.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(recipient, null);

        // a share grants edit/disable/execute-now, but never re-sharing onward
        Response response = controller.updateSharing(VALID_UUID, VALID_PASSWORD,
                Map.of("sharedWithTenants", java.util.List.of("third-tenant")));

        assertEquals(403, response.getStatus());
        verify(manageScheduleUseCase, never()).updateSharing(any(), any(), any(), anyBoolean());
    }

    @Test
    void updateSharing_ownerTenantMember_delegates() {
        when(schedulingService.isEnabled()).thenReturn(true);
        when(passwordValidationService.validateSchedulingPassword(VALID_PASSWORD)).thenReturn(true);
        ContainerSchedule schedule = makeSchedule();
        schedule.setId(VALID_UUID);
        schedule.setTenantId("owner-tenant");
        when(manageScheduleUseCase.findById(VALID_UUID)).thenReturn(Optional.of(schedule));
        when(manageScheduleUseCase.updateSharing(eq(VALID_UUID), any(), any(), anyBoolean()))
                .thenReturn(schedule);

        var owner = new br.com.fzdevx.infrastructure.config.CurrentUser();
        owner.set("u1", "alice",
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.SCHEDULES_MANAGE),
                java.util.Set.of("owner-tenant"));
        controller.currentUser = owner;
        controller.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(owner, null);

        Response response = controller.updateSharing(VALID_UUID, VALID_PASSWORD,
                Map.of("sharedWithTenants", java.util.List.of("other-tenant")));

        assertEquals(200, response.getStatus());
        verify(manageScheduleUseCase).updateSharing(eq(VALID_UUID),
                eq(java.util.List.of("other-tenant")), isNull(), eq(false));
    }
}
