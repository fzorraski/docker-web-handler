package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.ManageSettingsUseCase;
import br.com.fzdevx.domain.model.auth.Permission;
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
class SettingsControllerTest {

    @Mock ManageSettingsUseCase manageSettingsUseCase;

    @InjectMocks
    SettingsController controller;

    @Test
    void classRequiresSystemConfigPermission() {
        RequiresPermission annotation = SettingsController.class.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new Permission[]{Permission.SYSTEM_CONFIG}, annotation.value());
    }

    @Test
    void list_delegates() {
        when(manageSettingsUseCase.describe()).thenReturn(List.of(Map.of("key", "terminalEnabled")));
        assertEquals(1, controller.list().size());
    }

    @Test
    void update_delegates() {
        Map<String, Object> changes = Map.of("terminalEnabled", true);
        controller.update(changes);
        verify(manageSettingsUseCase).update(changes);
    }

    @Test
    void reset_delegates() {
        controller.reset("terminalEnabled");
        verify(manageSettingsUseCase).reset("terminalEnabled");
    }
}
