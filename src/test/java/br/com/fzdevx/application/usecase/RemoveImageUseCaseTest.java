package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RemoveImageUseCaseTest {

    private static final String VALID_IMAGE_ID = "sha256:abcdef1234";
    private static final String SHORT_IMAGE_ID = "abcdef1";

    @Mock DockerImagePort dockerImagePort;
    @Mock ResourceCounterService resourceCounterService;

    @InjectMocks
    RemoveImageUseCase useCase;

    private List<ContainerEvent> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
    }

    private ContainerEvent lastEvent() {
        assertFalse(events.isEmpty(), "No events captured");
        return events.getLast();
    }

    private boolean hasEvent(EventType type) {
        return events.stream().anyMatch(e -> e.getType() == type);
    }

    // ---- validation ----

    @Test
    void execute_nullImageId_sendsError() {
        useCase.execute(null, events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        assertEquals("Removing", lastEvent().getStep());
        verifyNoInteractions(dockerImagePort);
    }

    @Test
    void execute_blankImageId_sendsError() {
        useCase.execute("   ", events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerImagePort);
    }

    @Test
    void execute_invalidImageId_sendsError() {
        useCase.execute("NOT_VALID!", events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerImagePort);
    }

    // ---- happy path ----

    @Test
    void execute_validSha256Id_sendsSuccess() {
        useCase.execute(VALID_IMAGE_ID, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertFalse(hasEvent(EventType.ERROR));
        assertEquals("Complete", lastEvent().getStep());
        assertTrue(lastEvent().getMessage().contains("removed successfully"));
    }

    @Test
    void execute_validShortId_sendsSuccess() {
        useCase.execute(SHORT_IMAGE_ID, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    @Test
    void execute_happyPath_removesImage() {
        useCase.execute(VALID_IMAGE_ID, events::add);

        verify(dockerImagePort).removeImage(VALID_IMAGE_ID);
    }

    @Test
    void execute_happyPath_incrementsCounter() {
        useCase.execute(VALID_IMAGE_ID, events::add);

        verify(resourceCounterService).increment(ResourceCounterService.IMAGES_DELETED);
    }

    @Test
    void execute_happyPath_sendsInfoBeforeSuccess() {
        useCase.execute(VALID_IMAGE_ID, events::add);

        assertEquals(2, events.size());
        assertEquals(EventType.INFO, events.get(0).getType());
        assertTrue(events.get(0).getMessage().contains("Removing image"));
        assertEquals(EventType.SUCCESS, events.get(1).getType());
    }

    // ---- image in use ----

    @Test
    void execute_imageInUse_sendsSpecificError() {
        doThrow(new RuntimeException("image is being used by container xyz"))
                .when(dockerImagePort).removeImage(VALID_IMAGE_ID);

        useCase.execute(VALID_IMAGE_ID, events::add);

        assertTrue(hasEvent(EventType.ERROR));
        assertFalse(hasEvent(EventType.SUCCESS));
        assertTrue(lastEvent().getMessage().contains("currently in use"));
    }

    // ---- image has children ----

    @Test
    void execute_imageHasChildren_sendsSpecificError() {
        doThrow(new RuntimeException("image has dependent child images"))
                .when(dockerImagePort).removeImage(VALID_IMAGE_ID);

        useCase.execute(VALID_IMAGE_ID, events::add);

        assertTrue(hasEvent(EventType.ERROR));
        assertTrue(lastEvent().getMessage().contains("dependent child images"));
    }

    // ---- generic failure ----

    @Test
    void execute_genericFailure_sendsErrorWithMessage() {
        doThrow(new RuntimeException("Docker daemon error"))
                .when(dockerImagePort).removeImage(VALID_IMAGE_ID);

        useCase.execute(VALID_IMAGE_ID, events::add);

        assertTrue(hasEvent(EventType.ERROR));
        assertTrue(lastEvent().getMessage().contains("Docker daemon error"));
    }

    @Test
    void execute_removeFailure_doesNotIncrementCounter() {
        doThrow(new RuntimeException("fail")).when(dockerImagePort).removeImage(VALID_IMAGE_ID);

        useCase.execute(VALID_IMAGE_ID, events::add);

        verifyNoInteractions(resourceCounterService);
    }
}
