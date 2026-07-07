package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.CreateRoleRequest;
import br.com.fzdevx.application.usecase.ManageRolesUseCase;
import br.com.fzdevx.domain.model.auth.BuiltInRoles;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleControllerTest {

    @Mock ManageRolesUseCase manageRolesUseCase;

    @InjectMocks
    RoleController controller;

    @Test
    void classRequiresUsersManagePermission() {
        RequiresPermission annotation = RoleController.class.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new Permission[]{Permission.USERS_MANAGE}, annotation.value());
    }

    @Test
    void list_delegates() {
        when(manageRolesUseCase.list()).thenReturn(List.of(BuiltInRoles.viewer()));
        assertEquals(1, controller.list().size());
    }

    @Test
    void permissionCatalog_returnsAllPermissionsWithCategories() {
        List<Map<String, String>> catalog = controller.permissionCatalog();

        assertEquals(Permission.values().length, catalog.size());
        assertTrue(catalog.stream().anyMatch(entry ->
                "SYSTEM_CONFIG".equals(entry.get("name")) && "ADMINISTRATION".equals(entry.get("category"))));
        assertTrue(catalog.stream().allMatch(entry ->
                entry.containsKey("name") && entry.containsKey("category")));
    }

    @Test
    void create_returns201() {
        CreateRoleRequest request = new CreateRoleRequest();
        when(manageRolesUseCase.create(request)).thenReturn(BuiltInRoles.viewer());

        Response response = controller.create(request);
        assertEquals(201, response.getStatus());
    }

    @Test
    void update_delegates() {
        CreateRoleRequest request = new CreateRoleRequest();
        Role role = BuiltInRoles.viewer();
        when(manageRolesUseCase.update("r1", request)).thenReturn(role);
        assertEquals(role, controller.update("r1", request));
    }

    @Test
    void delete_delegates() {
        assertEquals(Map.of("success", true), controller.delete("r1"));
        verify(manageRolesUseCase).delete("r1");
    }
}
