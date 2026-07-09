package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.persistence.ImageUsageTracker;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PruneImagesUseCaseTest {

    private static final String IMAGE_ID = "sha256:abc123def456789012";

    @Mock DockerImagePort dockerImagePort;
    @Mock DockerContainerPort dockerContainerPort;
    @Mock ImageUsageTracker imageUsageTracker;
    @Mock ResourceCounterService resourceCounterService;

    @InjectMocks
    PruneImagesUseCase useCase;

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

    private Image mockImage(String id, String repoTag, long createdEpoch) {
        Image img = mock(Image.class);
        when(img.getId()).thenReturn(id);
        when(img.getRepoTags()).thenReturn(repoTag != null ? new String[]{repoTag} : null);
        when(img.getCreated()).thenReturn(createdEpoch);
        when(img.getSize()).thenReturn(50L * 1024 * 1024);
        return img;
    }

    private Container mockContainer(String imageId) {
        Container c = mock(Container.class);
        when(c.getImageId()).thenReturn(imageId);
        return c;
    }

    // ---- no images to prune ----

    @Test
    void execute_noImages_sendsSuccess() {
        when(dockerImagePort.listImages()).thenReturn(Collections.emptyList());
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        useCase.execute(30, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertTrue(lastEvent().getMessage().contains("No unused images"));
    }

    @Test
    void execute_allImagesInUse_sendsNoImagesFound() {
        Image img = mockImage(IMAGE_ID, "postgres:16", Instant.now().minus(90, ChronoUnit.DAYS).getEpochSecond());
        Container c = mockContainer(IMAGE_ID);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(List.of(c));

        useCase.execute(30, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertTrue(lastEvent().getMessage().contains("No unused images"));
    }

    // ---- skips self image ----

    @Test
    void execute_skipsSelfImage() {
        Image self = mockImage("sha256:self123456789012", Constants.DOCKER_WEB_HANDLER_IMAGE + ":latest",
                Instant.now().minus(90, ChronoUnit.DAYS).getEpochSecond());
        when(dockerImagePort.listImages()).thenReturn(List.of(self));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        useCase.execute(30, events::add);

        verify(dockerImagePort, never()).removeImage(any());
    }

    // ---- prune by age (creation date fallback) ----

    @Test
    void execute_oldUnusedImage_removesIt() {
        long oldEpoch = Instant.now().minus(60, ChronoUnit.DAYS).getEpochSecond();
        Image img = mockImage(IMAGE_ID, "postgres:16", oldEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        useCase.execute(30, events::add);

        verify(dockerImagePort).removeImage(IMAGE_ID);
        assertTrue(hasEvent(EventType.SUCCESS));
        assertTrue(lastEvent().getMessage().contains("Removed 1"));
    }

    @Test
    void execute_recentUnusedImage_doesNotRemove() {
        long recentEpoch = Instant.now().minus(5, ChronoUnit.DAYS).getEpochSecond();
        Image img = mockImage(IMAGE_ID, "postgres:16", recentEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        useCase.execute(30, events::add);

        verify(dockerImagePort, never()).removeImage(any());
    }

    // ---- prune by last-used tracker ----

    @Test
    void execute_imageLastUsedBeforeCutoff_removesIt() {
        Image img = mockImage(IMAGE_ID, "postgres:16", Instant.now().getEpochSecond());
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        Instant oldUsage = Instant.now().minus(60, ChronoUnit.DAYS);
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of(IMAGE_ID, oldUsage));

        useCase.execute(30, events::add);

        verify(dockerImagePort).removeImage(IMAGE_ID);
    }

    @Test
    void execute_imageLastUsedAfterCutoff_doesNotRemove() {
        Image img = mockImage(IMAGE_ID, "postgres:16", Instant.now().getEpochSecond());
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        Instant recentUsage = Instant.now().minus(5, ChronoUnit.DAYS);
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of(IMAGE_ID, recentUsage));

        useCase.execute(30, events::add);

        verify(dockerImagePort, never()).removeImage(any());
    }

    // ---- prune all (minDays <= 0) ----

    @Test
    void execute_minDaysZero_prunesAllUnused() {
        long recentEpoch = Instant.now().getEpochSecond();
        Image img = mockImage(IMAGE_ID, "postgres:16", recentEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        useCase.execute(0, events::add);

        verify(dockerImagePort).removeImage(IMAGE_ID);
    }

    // ---- remove failure ----

    @Test
    void execute_removePartialFailure_reportsSkipped() {
        String id1 = IMAGE_ID;
        String id2 = "sha256:def456789012345678";
        long oldEpoch = Instant.now().minus(60, ChronoUnit.DAYS).getEpochSecond();
        Image img1 = mockImage(id1, "postgres:16", oldEpoch);
        Image img2 = mockImage(id2, "redis:7", oldEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img1, img2));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        doNothing().when(dockerImagePort).removeImage(id1);
        doThrow(new RuntimeException("in use")).when(dockerImagePort).removeImage(id2);

        useCase.execute(30, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertTrue(lastEvent().getMessage().contains("Removed 1"));
        assertTrue(lastEvent().getMessage().contains("Skipped 1"));
    }

    // ---- counter ----

    @Test
    void execute_incrementsCounterPerRemoval() {
        long oldEpoch = Instant.now().minus(60, ChronoUnit.DAYS).getEpochSecond();
        Image img = mockImage(IMAGE_ID, "postgres:16", oldEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        useCase.execute(30, events::add);

        verify(resourceCounterService).increment(ResourceCounterService.IMAGES_DELETED);
    }

    // ---- general exception ----

    @Test
    void execute_dockerListFails_sendsError() {
        when(dockerImagePort.listImages()).thenThrow(new RuntimeException("Docker down"));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        useCase.execute(30, events::add);

        assertTrue(hasEvent(EventType.ERROR));
        assertTrue(lastEvent().getMessage().contains("Prune failed"));
    }

    // ---- progress events ----

    @Test
    void execute_sendsProgressEvents() {
        long oldEpoch = Instant.now().minus(60, ChronoUnit.DAYS).getEpochSecond();
        Image img = mockImage(IMAGE_ID, "postgres:16", oldEpoch);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        useCase.execute(30, events::add);

        assertTrue(events.stream().anyMatch(e -> e.getType() == EventType.PROGRESS));
    }
}
