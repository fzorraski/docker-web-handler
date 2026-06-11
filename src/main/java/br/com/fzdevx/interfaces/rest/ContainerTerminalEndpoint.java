package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.DockerTerminalPort;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.docker.TerminalInitCommandResolver;
import br.com.fzdevx.infrastructure.docker.TerminalSessionManager;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@ServerEndpoint(value = "/api/containers/terminal/{ticket}", configurator = AuthWebSocketConfigurator.class)
public class ContainerTerminalEndpoint {

    @Inject
    DockerTerminalPort dockerTerminalPort;

    @Inject
    RequestStash requestStash;

    @Inject
    TerminalSessionManager sessionManager;

    @Inject
    TerminalInitCommandResolver initCommandResolver;

    @Inject
    @ConfigProperty(name = "container.terminal.enabled", defaultValue = "false")
    boolean terminalEnabled;

    @Inject
    @ConfigProperty(name = "container.terminal.default-shell", defaultValue = "/bin/bash")
    String defaultShell;

    @Inject
    @ConfigProperty(name = "container.terminal.init-command.delay-ms", defaultValue = "300")
    long initCommandDelayMs;

    @OnOpen
    public void onOpen(Session session, @PathParam("ticket") String ticket) {
        Boolean authenticated = (Boolean) session.getUserProperties().get(AuthWebSocketConfigurator.AUTH_RESULT_KEY);
        if (authenticated != null && !authenticated) {
            sendAndClose(session, errorMsg("Authentication required."));
            return;
        }

        if (!terminalEnabled) {
            sendAndClose(session, errorMsg("Terminal feature is disabled."));
            return;
        }

        String containerId = requestStash.retrieveTerminal(ticket);
        if (containerId == null) {
            sendAndClose(session, errorMsg("Invalid or expired ticket."));
            return;
        }

        if (InputValidator.validateContainerId(containerId).isPresent()) {
            sendAndClose(session, errorMsg("Invalid container ID."));
            return;
        }

        DockerTerminalPort.ContainerRuntimeInfo containerInfo = dockerTerminalPort.inspectContainer(containerId);
        if (!containerInfo.running()) {
            sendAndClose(session, errorMsg("Container is not running."));
            return;
        }

        session.setMaxBinaryMessageBufferSize(65536);
        session.setMaxTextMessageBufferSize(65536);

        DockerTerminalPort.ExecSession execSession = null;
        try {
            String execId = dockerTerminalPort.createExecSession(containerId, defaultShell);
            execSession = dockerTerminalPort.startExecSession(execId);

            Optional<TerminalSessionManager.TerminalSession> registered =
                    sessionManager.tryRegisterSession(session.getId(), session, execSession, containerId, execId);

            if (registered.isEmpty()) {
                execSession.close();
                sendAndClose(session, errorMsg("Maximum terminal sessions reached."));
                return;
            }

            TerminalSessionManager.TerminalSession ts = registered.get();
            sendMessage(session, "{\"type\":\"connected\"}");

            execSession.onOutput(data -> {
                if (session.isOpen()) {
                    ts.touch();
                    String encoded = Base64.getEncoder().encodeToString(data);
                    sendMessage(session, "{\"type\":\"output\",\"data\":\"" + encoded + "\"}");
                }
            });

            final DockerTerminalPort.ExecSession finalExec = execSession;

            final List<String> initCommands = initCommandResolver.resolve(containerInfo.image());

            Thread.ofVirtual().name("terminal-exit-watcher-" + session.getId()).start(() -> {
                try {
                    // Send init commands from this long-lived thread. Writing from a
                    // short-lived thread that then dies would break the stdin pipe
                    // ("Write end dead"), permanently blocking further user input.
                    sendInitCommands(finalExec, initCommands);
                    while (finalExec.isRunning() && session.isOpen()) {
                        Thread.sleep(1000);
                    }
                    if (session.isOpen()) {
                        sendMessage(session, "{\"type\":\"exit\",\"code\":0}");
                        closeSession(session);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Log.infof("Terminal session started: session=%s, container=%s", session.getId(), containerId);

        } catch (Exception e) {
            Log.errorf("Failed to create terminal session: %s", e.getMessage());
            if (execSession != null) {
                try { execSession.close(); } catch (Exception ignored) {}
            }
            sendAndClose(session, errorMsg("Failed to start terminal."));
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        TerminalSessionManager.TerminalSession ts = sessionManager.getSession(session.getId());
        if (ts == null) return;

        JsonObject json;
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            json = reader.readObject();
        } catch (Exception e) {
            return;
        }

        if (json == null) return;

        String type = json.getString("type", "");
        switch (type) {
            case "input" -> handleInput(json, ts);
            case "resize" -> handleResize(json, ts);
            case "ping" -> sendMessage(session, "{\"type\":\"pong\"}");
            default -> { }
        }
    }

    @OnClose
    public void onClose(Session session) {
        sessionManager.removeSession(session.getId());
        Log.infof("Terminal WebSocket closed: session=%s", session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        sessionManager.removeSession(session.getId());
        Log.warnf("Terminal WebSocket error: session=%s, error=%s", session.getId(), error.getMessage());
    }

    private void sendInitCommands(DockerTerminalPort.ExecSession execSession, List<String> commands)
            throws InterruptedException {
        if (commands.isEmpty()) return;
        if (initCommandDelayMs > 0) {
            Thread.sleep(initCommandDelayMs);
        }
        try {
            OutputStream stdin = execSession.getStdin();
            for (String cmd : commands) {
                stdin.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            Log.warnf("Failed to send terminal init command: %s", e.getMessage());
        }
    }

    private void handleInput(JsonObject json, TerminalSessionManager.TerminalSession ts) {
        String data = json.getString("data", "");
        if (data.isEmpty()) return;
        ts.touch();
        try {
            OutputStream stdin = ts.getExecSession().getStdin();
            stdin.write(data.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            Log.warnf("Failed to write to terminal stdin: %s", e.getMessage());
        }
    }

    private void handleResize(JsonObject json, TerminalSessionManager.TerminalSession ts) {
        int cols = json.getInt("cols", 80);
        int rows = json.getInt("rows", 24);
        ts.touch();
        dockerTerminalPort.resizeExec(ts.getExecId(), cols, rows);
    }

    private void sendMessage(Session session, String message) {
        if (!session.isOpen()) return;
        session.getAsyncRemote().sendText(message, result -> {
            if (!result.isOK()) {
                Log.warnf("Failed to send WebSocket message: %s", result.getException().getMessage());
            }
        });
    }

    private void sendAndClose(Session session, String message) {
        sendMessage(session, message);
        closeSession(session);
    }

    private void closeSession(Session session) {
        try {
            if (session.isOpen()) session.close();
        } catch (IOException e) {
            Log.warnf("Failed to close WebSocket session: %s", e.getMessage());
        }
    }

    private String errorMsg(String message) {
        return "{\"type\":\"error\",\"message\":" + jsonString(message) + "}";
    }

    private String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }
}
