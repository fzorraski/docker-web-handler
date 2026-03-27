package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerEventTest {

    @Test
    void info_setsTypeAndFields() {
        ContainerEvent event = ContainerEvent.info("Validating", "All good");

        assertEquals(ContainerEvent.EventType.INFO, event.getType());
        assertEquals("Validating", event.getStep());
        assertEquals("All good", event.getMessage());
        assertNull(event.getProgress());
        assertNull(event.getDetail());
    }

    @Test
    void progress_setsAllFields() {
        ContainerEvent event = ContainerEvent.progress("Pulling", "50%", 50);

        assertEquals(ContainerEvent.EventType.PROGRESS, event.getType());
        assertEquals("Pulling", event.getStep());
        assertEquals("50%", event.getMessage());
        assertEquals(50, event.getProgress());
        assertNull(event.getDetail());
    }

    @Test
    void success_withoutDetail_setsTypeAndNullDetail() {
        ContainerEvent event = ContainerEvent.success("Complete", "Done");

        assertEquals(ContainerEvent.EventType.SUCCESS, event.getType());
        assertEquals("Complete", event.getStep());
        assertEquals("Done", event.getMessage());
        assertNull(event.getDetail());
    }

    @Test
    void success_withDetail_setsDetail() {
        String snapshotId = "550e8400-e29b-41d4-a716-446655440000";
        ContainerEvent event = ContainerEvent.success("Complete", "Done", snapshotId);

        assertEquals(ContainerEvent.EventType.SUCCESS, event.getType());
        assertEquals("Complete", event.getStep());
        assertEquals("Done", event.getMessage());
        assertEquals(snapshotId, event.getDetail());
    }

    @Test
    void error_setsTypeAndNullDetail() {
        ContainerEvent event = ContainerEvent.error("Creating", "Failed");

        assertEquals(ContainerEvent.EventType.ERROR, event.getType());
        assertEquals("Creating", event.getStep());
        assertEquals("Failed", event.getMessage());
        assertNull(event.getDetail());
    }

    @Test
    void setters_work() {
        ContainerEvent event = new ContainerEvent();
        event.setType(ContainerEvent.EventType.INFO);
        event.setStep("step");
        event.setMessage("msg");
        event.setProgress(75);
        event.setDetail("detail-value");

        assertEquals(ContainerEvent.EventType.INFO, event.getType());
        assertEquals("step", event.getStep());
        assertEquals("msg", event.getMessage());
        assertEquals(75, event.getProgress());
        assertEquals("detail-value", event.getDetail());
    }
}
