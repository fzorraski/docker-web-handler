package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateUserRequest;
import br.com.fzdevx.application.dto.UpdateUserRequest;
import br.com.fzdevx.application.dto.UserResponse;
import br.com.fzdevx.application.usecase.ManageUsersUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock ManageUsersUseCase manageUsersUseCase;

    @InjectMocks
    UserController controller;

    private UserResponse sample() {
        return new UserResponse("u1", "alice", java.util.List.of("role-1"), java.util.List.of("OPERATOR"), java.util.List.of(), java.util.List.of(), true, Instant.now(), null);
    }

    @Test
    void classRequiresUsersManagePermission() {
        RequiresPermission annotation = UserController.class.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new Permission[]{Permission.USERS_MANAGE}, annotation.value());
    }

    @Test
    void list_delegatesToUseCase() {
        when(manageUsersUseCase.list()).thenReturn(List.of(sample()));
        assertEquals(1, controller.list().size());
    }

    @Test
    void create_returns201WithBody() {
        CreateUserRequest request = new CreateUserRequest();
        when(manageUsersUseCase.create(request)).thenReturn(sample());

        Response response = controller.create(request);

        assertEquals(201, response.getStatus());
        assertEquals("alice", ((UserResponse) response.getEntity()).username());
    }

    @Test
    void update_delegates() {
        UpdateUserRequest request = new UpdateUserRequest();
        when(manageUsersUseCase.update("u1", request)).thenReturn(sample());
        assertEquals("alice", controller.update("u1", request).username());
    }

    @Test
    void resetPassword_passesPasswordFromBody() {
        assertEquals(Map.of("success", true),
                controller.resetPassword("u1", Map.of("password", "new-secret")));
        verify(manageUsersUseCase).resetPassword("u1", "new-secret");
    }

    @Test
    void resetPassword_nullBody_passesNull() {
        controller.resetPassword("u1", null);
        verify(manageUsersUseCase).resetPassword("u1", null);
    }

    @Test
    void delete_delegates() {
        assertEquals(Map.of("success", true), controller.delete("u1"));
        verify(manageUsersUseCase).delete("u1");
    }
}
