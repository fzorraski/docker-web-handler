package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.domain.model.DockerImage;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.persistence.ImageUsageTracker;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListImagesUseCaseTest {

    private static final String IMAGE_ID = "sha256:abcdef1234567890abcdef1234567890";
    private static final long CREATED_EPOCH = 1700000000L;

    @Mock DockerImagePort dockerImagePort;
    @Mock DockerContainerPort dockerContainerPort;
    @Mock ImageUsageTracker imageUsageTracker;

    @InjectMocks
    ListImagesUseCase useCase;

    private Image mockImage(String id, String repoTag, String parentId) {
        Image img = mock(Image.class);
        when(img.getId()).thenReturn(id);
        when(img.getRepoTags()).thenReturn(new String[]{repoTag});
        when(img.getCreated()).thenReturn(CREATED_EPOCH);
        when(img.getSize()).thenReturn(100L * 1024 * 1024);
        when(img.getParentId()).thenReturn(parentId != null ? parentId : "");
        return img;
    }

    private Container mockContainer(String imageId) {
        Container c = mock(Container.class);
        when(c.getImageId()).thenReturn(imageId);
        return c;
    }

    // ---- empty ----

    @Test
    void execute_noImages_returnsEmptyList() {
        when(dockerImagePort.listImages()).thenReturn(Collections.emptyList());
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertTrue(result.isEmpty());
    }

    // ---- basic mapping ----

    @Test
    void execute_mapsImageFields() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(1, result.size());
        DockerImage di = result.getFirst();
        assertEquals("postgres", di.getRepository());
        assertEquals("16", di.getTag());
        assertFalse(di.isInUse());
        assertEquals(0, di.getContainerCount());
    }

    @Test
    void execute_truncatesLongImageId() {
        String longId = "sha256:" + "a".repeat(64);
        Image img = mockImage(longId, "redis:7", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(20, result.getFirst().getImageId().length());
    }

    @Test
    void execute_imageWithNoTag_setsHyphen() {
        Image img = mockImage(IMAGE_ID, "myrepo", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals("-", result.getFirst().getTag());
    }

    // ---- filtering ----

    @Test
    void execute_filtersSelfImage() {
        Image self = mockImage("sha256:self1234567890", Constants.DOCKER_WEB_HANDLER_IMAGE + ":latest", null);
        Image other = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(self, other));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(1, result.size());
        assertEquals("postgres", result.getFirst().getRepository());
    }

    @Test
    void execute_filtersImagesWithoutRepoTags() {
        Image noTags = mock(Image.class);
        when(noTags.getId()).thenReturn("sha256:notags1234567890");
        when(noTags.getRepoTags()).thenReturn(null);
        Image other = mockImage(IMAGE_ID, "redis:7", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(noTags, other));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(1, result.size());
    }

    @Test
    void execute_filtersImagesWithEmptyRepoTags() {
        Image emptyTags = mock(Image.class);
        when(emptyTags.getId()).thenReturn("sha256:empty1234567890");
        when(emptyTags.getRepoTags()).thenReturn(new String[]{});
        Image other = mockImage(IMAGE_ID, "redis:7", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(emptyTags, other));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(1, result.size());
    }

    // ---- in-use tracking ----

    @Test
    void execute_marksImageAsInUse() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        Container container = mockContainer(IMAGE_ID);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(List.of(container));

        List<DockerImage> result = useCase.execute();

        assertTrue(result.getFirst().isInUse());
        assertEquals(1, result.getFirst().getContainerCount());
    }

    @Test
    void execute_countsMultipleContainers() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        Container c1 = mockContainer(IMAGE_ID);
        Container c2 = mockContainer(IMAGE_ID);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(List.of(c1, c2));

        List<DockerImage> result = useCase.execute();

        assertEquals(2, result.getFirst().getContainerCount());
    }

    @Test
    void execute_callsMarkInUse() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        Container container = mockContainer(IMAGE_ID);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(List.of(container));

        useCase.execute();

        verify(imageUsageTracker).markInUse(argThat(set -> set.contains(IMAGE_ID)));
    }

    @Test
    void execute_callsCleanup() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        useCase.execute();

        verify(imageUsageTracker).cleanup(argThat(set -> set.contains(IMAGE_ID)));
    }

    // ---- last used ----

    @Test
    void execute_setsLastUsedAt() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        Instant lastUsed = Instant.parse("2025-06-15T10:30:00Z");
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of(IMAGE_ID, lastUsed));

        List<DockerImage> result = useCase.execute();

        assertNotNull(result.getFirst().getLastUsedAt());
    }

    @Test
    void execute_lastUsedNull_whenNeverTracked() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());
        when(imageUsageTracker.getAllLastUsed()).thenReturn(java.util.Map.of());

        List<DockerImage> result = useCase.execute();

        assertNull(result.getFirst().getLastUsedAt());
    }

    // ---- parent/child relationships ----

    @Test
    void execute_setsParentId() {
        String parentId = "sha256:parent12345678901234";
        Image img = mockImage(IMAGE_ID, "postgres:16", parentId);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(parentId.substring(0, 20), result.getFirst().getParentId());
    }

    @Test
    void execute_noParent_setsEmptyString() {
        Image img = mockImage(IMAGE_ID, "postgres:16", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals("", result.getFirst().getParentId());
    }

    @Test
    void execute_buildsChildIds() {
        String parentId = IMAGE_ID;
        String childId = "sha256:child12345678901234";
        Image parent = mockImage(parentId, "postgres:16", null);
        Image child = mockImage(childId, "postgres:15", parentId);
        when(dockerImagePort.listImages()).thenReturn(List.of(parent, child));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        DockerImage parentResult = result.stream()
                .filter(i -> i.getRepository().equals("postgres") && i.getTag().equals("16"))
                .findFirst().orElseThrow();
        assertFalse(parentResult.getChildIds().isEmpty());
    }

    // ---- multiple images ----

    @Test
    void execute_multipleImages_returnsAll() {
        Image img1 = mockImage(IMAGE_ID, "postgres:16", null);
        Image img2 = mockImage("sha256:bbbccc1234567890bbbccc", "redis:7", null);
        when(dockerImagePort.listImages()).thenReturn(List.of(img1, img2));
        when(dockerContainerPort.listContainers(true)).thenReturn(Collections.emptyList());

        List<DockerImage> result = useCase.execute();

        assertEquals(2, result.size());
    }
}
