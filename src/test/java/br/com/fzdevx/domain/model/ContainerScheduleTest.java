package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ContainerScheduleTest {

    @Test
    void constructor_setsAllFields() {
        ContainerSchedule s = new ContainerSchedule("nightly", ScheduleAction.STOP, ScheduleType.RECURRING);

        assertNotNull(s.getId());
        assertEquals("nightly", s.getName());
        assertEquals(ScheduleAction.STOP, s.getAction());
        assertEquals(ScheduleType.RECURRING, s.getScheduleType());
        assertTrue(s.isEnabled());
        assertNotNull(s.getCreatedAt());
    }

    @Test
    void constructor_nullName_throws() {
        assertThrows(NullPointerException.class,
                () -> new ContainerSchedule(null, ScheduleAction.STOP, ScheduleType.ONE_TIME));
    }

    @Test
    void constructor_nullAction_throws() {
        assertThrows(NullPointerException.class,
                () -> new ContainerSchedule("test", null, ScheduleType.ONE_TIME));
    }

    @Test
    void constructor_nullType_throws() {
        assertThrows(NullPointerException.class,
                () -> new ContainerSchedule("test", ScheduleAction.STOP, null));
    }

    @Test
    void equals_sameId() {
        ContainerSchedule a = new ContainerSchedule();
        a.setId("abc");
        ContainerSchedule b = new ContainerSchedule();
        b.setId("abc");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentId() {
        ContainerSchedule a = new ContainerSchedule();
        a.setId("abc");
        ContainerSchedule b = new ContainerSchedule();
        b.setId("def");
        assertNotEquals(a, b);
    }

    @Test
    void settersWork() {
        ContainerSchedule s = new ContainerSchedule();
        s.setCronExpression("0 3 * * *");
        s.setScheduledAt(Instant.now());
        s.setContainerId("abc123def4");
        s.setContainerName("my-container");
        s.setNextExecutionAt(Instant.now());
        s.setLastExecutedAt(Instant.now());
        s.setLastExecutionStatus("SUCCESS");
        s.setLastExecutionMessage("Done");

        assertEquals("0 3 * * *", s.getCronExpression());
        assertNotNull(s.getScheduledAt());
        assertEquals("abc123def4", s.getContainerId());
        assertEquals("my-container", s.getContainerName());
        assertNotNull(s.getNextExecutionAt());
        assertNotNull(s.getLastExecutedAt());
        assertEquals("SUCCESS", s.getLastExecutionStatus());
        assertEquals("Done", s.getLastExecutionMessage());
    }

    @Test
    void scheduleAction_values() {
        assertEquals(4, ScheduleAction.values().length);
        assertNotNull(ScheduleAction.valueOf("START"));
        assertNotNull(ScheduleAction.valueOf("STOP"));
        assertNotNull(ScheduleAction.valueOf("CREATE"));
        assertNotNull(ScheduleAction.valueOf("REMOVE"));
    }

    @Test
    void scheduleType_values() {
        assertEquals(2, ScheduleType.values().length);
        assertNotNull(ScheduleType.valueOf("ONE_TIME"));
        assertNotNull(ScheduleType.valueOf("RECURRING"));
    }
}
