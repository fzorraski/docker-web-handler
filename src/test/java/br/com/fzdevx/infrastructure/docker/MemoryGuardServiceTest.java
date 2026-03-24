package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.model.HostMemoryStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class MemoryGuardServiceTest {

    private MemoryGuardService createService(boolean enabled, long thresholdMb, boolean supported) throws Exception {
        MemoryGuardService service = new MemoryGuardService();
        setField(service, "enabled", enabled);
        setField(service, "thresholdMb", thresholdMb);
        setField(service, "supported", supported);
        return service;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_returnsConfiguredValue() throws Exception {
        assertTrue(createService(true, 2048, true).isEnabled());
        assertFalse(createService(false, 2048, true).isEnabled());
    }

    // ---- checkMemoryFor — disabled/unsupported ----

    @Test
    void checkMemoryFor_disabled_returnsNull() throws Exception {
        assertNull(createService(false, 2048, true).checkMemoryFor(5000L));
    }

    @Test
    void checkMemoryFor_unsupported_returnsNull() throws Exception {
        assertNull(createService(true, 2048, false).checkMemoryFor(5000L));
    }

    @Test
    void checkMemoryFor_disabledAndUnsupported_returnsNull() throws Exception {
        assertNull(createService(false, 2048, false).checkMemoryFor(5000L));
    }

    @Test
    void checkMemoryFor_nullRequest_disabled_returnsNull() throws Exception {
        assertNull(createService(false, 2048, true).checkMemoryFor(null));
    }

    @Test
    void checkMemoryFor_zeroRequest_disabled_returnsNull() throws Exception {
        assertNull(createService(false, 2048, true).checkMemoryFor(0L));
    }

    // ---- getStatus — unsupported ----

    @Test
    void getStatus_unsupported_returnsUnsupportedStatus() throws Exception {
        HostMemoryStatus status = createService(true, 2048, false).getStatus();
        assertFalse(status.supported());
        assertTrue(status.enabled());
        assertTrue(status.available());
        assertEquals(0, status.totalMb());
        assertEquals(0, status.usedMb());
        assertEquals(0, status.availableMb());
        assertEquals(2048, status.thresholdMb());
    }

    @Test
    void getStatus_disabled_returnsAvailableTrue() throws Exception {
        HostMemoryStatus status = createService(false, 2048, false).getStatus();
        assertFalse(status.supported());
        assertFalse(status.enabled());
        assertTrue(status.available());
    }
}
