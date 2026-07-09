package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.DockerTerminalPort;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerFileUploadControllerTest {

    private static final String VALID_CONTAINER_ID = "abcdef1234567890";
    private static final String VALID_PASSWORD = "secret";
    private static final String VALID_REMOTE_PATH = "/tmp";

    @Mock DockerTerminalPort dockerTerminalPort;
    @Mock PasswordValidationService passwordValidationService;
    @Mock br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    @InjectMocks
    ContainerFileUploadController controller;

    @BeforeEach
    void setUp() {
        controller.containerTenantGuard = br.com.fzdevx.infrastructure.docker.TestContainerTenantGuard.passthrough();
        when(runtimeSettings.isTerminalUploadEnabled()).thenReturn(true);
        when(runtimeSettings.getTerminalUploadMaxSizeMb()).thenReturn(100);
    }

    // ---- Feature disabled ----

    @Test
    void uploadFile_disabled_returnsForbidden() {
        when(runtimeSettings.isTerminalUploadEnabled()).thenReturn(false);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, mock(MultipartFormDataInput.class));

        assertEquals(403, response.getStatus());
    }

    // ---- Invalid container ID ----

    @Test
    void uploadFile_invalidContainerId_returnsBadRequest() {
        Response response = controller.uploadFile("not-hex!", mock(MultipartFormDataInput.class));

        assertEquals(400, response.getStatus());
    }

    // ---- Invalid password ----

    @Test
    void uploadFile_invalidPassword_returnsForbidden() throws Exception {
        MultipartFormDataInput input = mockForm("wrong", VALID_REMOTE_PATH, "test.txt", new byte[]{1, 2, 3});
        when(passwordValidationService.validateTerminalPassword("wrong")).thenReturn(false);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(403, response.getStatus());
    }

    // ---- Container not running ----

    @Test
    void uploadFile_containerNotRunning_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "test.txt", new byte[]{1, 2, 3});
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(false);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "not running");
    }

    // ---- Invalid remote path ----

    @Test
    void uploadFile_invalidRemotePath_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, "../etc", "test.txt", new byte[]{1});
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
    }

    // ---- No file provided ----

    @Test
    void uploadFile_noFile_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockFormNoFile(VALID_PASSWORD, VALID_REMOTE_PATH);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "No file");
    }

    // ---- Missing filename in Content-Disposition ----

    @Test
    void uploadFile_blankFilename_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "", new byte[]{1});
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
    }

    // ---- Invalid filename ----

    @Test
    void uploadFile_filenameWithSlash_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "path/file.txt", new byte[]{1});
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
    }

    // ---- File too large ----

    @Test
    void uploadFile_exceedsMaxSize_returnsBadRequest() throws Exception {
        when(runtimeSettings.getTerminalUploadMaxSizeMb()).thenReturn(1); // 1 MB limit
        byte[] largeFile = new byte[1024 * 1024 + 1]; // 1 MB + 1 byte
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "big.bin", largeFile);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "maximum size");
        verify(dockerTerminalPort, never()).copyFileToContainer(any(), any(), any());
    }

    // ---- Successful upload ----

    @Test
    void uploadFile_valid_returns200AndCopies() throws Exception {
        byte[] content = "hello world".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "test.txt", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        verify(dockerTerminalPort).copyFileToContainer(eq(VALID_CONTAINER_ID), any(Path.class), eq(VALID_REMOTE_PATH));

        @SuppressWarnings("unchecked")
        Map<String, String> entity = (Map<String, String>) response.getEntity();
        assertEquals("test.txt", entity.get("filename"));
        assertEquals(VALID_REMOTE_PATH, entity.get("remotePath"));
    }

    // ---- copyFileToContainer failure ----

    @Test
    void uploadFile_copyThrows_returns500() throws Exception {
        byte[] content = "data".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "test.txt", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);
        doThrow(new RuntimeException("Docker error"))
                .when(dockerTerminalPort).copyFileToContainer(any(), any(), any());

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(500, response.getStatus());
    }

    // ---- Valid upload with trailing slash in path ----

    @Test
    void uploadFile_trailingSlashPath_returns200() throws Exception {
        byte[] content = "data".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, "/tmp/", "script.sh", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        verify(dockerTerminalPort).copyFileToContainer(eq(VALID_CONTAINER_ID), any(Path.class), eq("/tmp/"));
    }

    // ---- Original filename preservation ----

    @Test
    void uploadFile_valid_copiesFileWithOriginalFilename() throws Exception {
        byte[] content = "payload".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "report.csv", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(dockerTerminalPort).copyFileToContainer(eq(VALID_CONTAINER_ID), pathCaptor.capture(), eq(VALID_REMOTE_PATH));
        assertEquals("report.csv", pathCaptor.getValue().getFileName().toString());
    }

    @Test
    void uploadFile_valid_preservesFilenameWithSpaces() throws Exception {
        byte[] content = "data".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "my file.txt", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(dockerTerminalPort).copyFileToContainer(eq(VALID_CONTAINER_ID), pathCaptor.capture(), eq(VALID_REMOTE_PATH));
        assertEquals("my file.txt", pathCaptor.getValue().getFileName().toString());
    }

    @Test
    void uploadFile_valid_preservesFilenameWithDot() throws Exception {
        byte[] content = "war-content".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "app.v2.1.war", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        verify(dockerTerminalPort).copyFileToContainer(eq(VALID_CONTAINER_ID), pathCaptor.capture(), eq(VALID_REMOTE_PATH));
        assertEquals("app.v2.1.war", pathCaptor.getValue().getFileName().toString());
    }

    // ---- Temp file cleanup ----

    @Test
    void uploadFile_valid_cleansUpTempFiles() throws Exception {
        byte[] content = "data".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "test.txt", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);

        controller.uploadFile(VALID_CONTAINER_ID, input);

        verify(dockerTerminalPort).copyFileToContainer(any(), pathCaptor.capture(), any());
        Path tempFile = pathCaptor.getValue();
        Path tempDir = tempFile.getParent();

        assertFalse(Files.exists(tempFile), "Temp file should be deleted after upload");
        assertFalse(Files.exists(tempDir), "Temp directory should be deleted after upload");
    }

    @Test
    void uploadFile_copyThrows_cleansUpTempFiles() throws Exception {
        byte[] content = "data".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "test.txt", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        doThrow(new RuntimeException("Docker error"))
                .when(dockerTerminalPort).copyFileToContainer(any(), pathCaptor.capture(), any());

        controller.uploadFile(VALID_CONTAINER_ID, input);

        Path tempFile = pathCaptor.getValue();
        Path tempDir = tempFile.getParent();

        assertFalse(Files.exists(tempFile), "Temp file should be deleted after failed upload");
        assertFalse(Files.exists(tempDir), "Temp directory should be deleted after failed upload");
    }

    // ---- Temp file content ----

    @Test
    void uploadFile_valid_tempFileContainsUploadedContent() throws Exception {
        byte[] content = "expected-content-12345".getBytes();
        MultipartFormDataInput input = mockForm(VALID_PASSWORD, VALID_REMOTE_PATH, "data.bin", content);
        when(passwordValidationService.validateTerminalPassword(VALID_PASSWORD)).thenReturn(true);
        when(dockerTerminalPort.isContainerRunning(VALID_CONTAINER_ID)).thenReturn(true);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        doAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            byte[] written = Files.readAllBytes(file);
            assertArrayEquals(content, written, "File content should match uploaded content");
            return null;
        }).when(dockerTerminalPort).copyFileToContainer(any(), pathCaptor.capture(), any());

        Response response = controller.uploadFile(VALID_CONTAINER_ID, input);

        assertEquals(200, response.getStatus());
        verify(dockerTerminalPort).copyFileToContainer(any(), any(Path.class), any());
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    private void assertErrorContains(Response response, String substring) {
        Map<String, String> entity = (Map<String, String>) response.getEntity();
        assertTrue(entity.get("error").toLowerCase().contains(substring.toLowerCase()),
                "Expected error to contain '" + substring + "' but got: " + entity.get("error"));
    }

    private MultipartFormDataInput mockForm(String password, String remotePath, String filename, byte[] fileContent) throws Exception {
        MultipartFormDataInput input = mock(MultipartFormDataInput.class);

        InputPart passwordPart = mockStringPart(password);
        InputPart remotePathPart = mockStringPart(remotePath);
        InputPart filePart = mockFilePart(filename, fileContent);

        when(input.getFormDataMap()).thenReturn(Map.of(
                "password", List.of(passwordPart),
                "remotePath", List.of(remotePathPart),
                "file", List.of(filePart)
        ));
        return input;
    }

    private MultipartFormDataInput mockFormNoFile(String password, String remotePath) throws Exception {
        MultipartFormDataInput input = mock(MultipartFormDataInput.class);

        InputPart passwordPart = mockStringPart(password);
        InputPart remotePathPart = mockStringPart(remotePath);

        when(input.getFormDataMap()).thenReturn(Map.of(
                "password", List.of(passwordPart),
                "remotePath", List.of(remotePathPart)
        ));
        return input;
    }

    private InputPart mockStringPart(String value) throws Exception {
        InputPart part = mock(InputPart.class);
        when(part.getBodyAsString()).thenReturn(value);
        return part;
    }

    private InputPart mockFilePart(String filename, byte[] content) throws Exception {
        InputPart part = mock(InputPart.class);
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Content-Disposition", "form-data; name=\"file\"; filename=\"" + filename + "\"");
        when(part.getHeaders()).thenReturn(headers);
        when(part.getBody(eq(java.io.InputStream.class), any())).thenReturn(new ByteArrayInputStream(content));
        return part;
    }
}
