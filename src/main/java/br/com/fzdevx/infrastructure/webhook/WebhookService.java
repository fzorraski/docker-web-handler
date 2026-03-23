package br.com.fzdevx.infrastructure.webhook;

import br.com.fzdevx.application.dto.WebhookPayload;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class WebhookService {

    @Inject
    @ConfigProperty(name = "webhook.enabled", defaultValue = "false")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "webhook.url", defaultValue = "")
    Optional<String> webhookUrl;

    @Inject
    @ConfigProperty(name = "webhook.secret", defaultValue = "")
    Optional<String> webhookSecret;

    @Inject
    @ConfigProperty(name = "webhook.timeout-ms", defaultValue = "5000")
    int timeoutMs;

    @Inject
    @ConfigProperty(name = "webhook.allow-http", defaultValue = "false")
    boolean allowHttp;

    @Inject
    @ConfigProperty(name = "webhook.template.container.success",
            defaultValue = "Container created: {repository}:{tag} as \"{containerName}\" on port {port}")
    String containerSuccessTemplate;

    @Inject
    @ConfigProperty(name = "webhook.template.container.failure",
            defaultValue = "Container creation failed: {repository}:{tag} - {errorMessage}")
    String containerFailureTemplate;

    @Inject
    @ConfigProperty(name = "webhook.template.restore.success",
            defaultValue = "Restore completed: {dumpFilename} into {targetDatabase} on {repository}")
    String restoreSuccessTemplate;

    @Inject
    @ConfigProperty(name = "webhook.template.restore.failure",
            defaultValue = "Restore failed: {dumpFilename} into {targetDatabase} - {errorMessage}")
    String restoreFailureTemplate;

    private final Jsonb jsonb = JsonbBuilder.create();
    private volatile HttpClient httpClient;

    public boolean isEnabled() {
        return enabled && webhookUrl.isPresent() && !webhookUrl.get().isBlank();
    }

    public void fireAsync(WebhookPayload payload) {
        if (!isEnabled()) return;

        String url = webhookUrl.get().trim()
                .replaceAll("^['\"\u2018\u2019\u201C\u201D]+|['\"\u2018\u2019\u201C\u201D]+$", "");

        // Render message from template (success vs failure)
        boolean isSuccess = payload.getStatus() == WebhookPayload.Status.SUCCESS;
        String template;
        if (payload.getOperation() == WebhookPayload.Operation.CONTAINER_CREATION) {
            template = isSuccess ? containerSuccessTemplate : containerFailureTemplate;
        } else {
            template = isSuccess ? restoreSuccessTemplate : restoreFailureTemplate;
        }
        String message = renderTemplate(template, payload);
        if (message.isBlank()) {
            Log.debugf("Webhook skipped: rendered message is empty for %s/%s.", payload.getOperation(), payload.getStatus());
            return;
        }
        payload.setMessage(message);
        payload.setTimestamp(Instant.now().toString());

        Thread.ofVirtual().start(() -> {
            try {
                send(url, payload);
            } catch (Exception e) {
                Log.warnf("Webhook delivery failed: %s", e.getMessage());
            }
        });
    }

    private void send(String url, WebhookPayload payload) throws Exception {
        URI uri = URI.create(url);

        // Validate URL scheme
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !(allowHttp && scheme.equals("http")))) {
            Log.warnf("Webhook URL rejected: scheme '%s' not allowed.", scheme);
            return;
        }

        // SSRF prevention: resolve hostname and block private/internal IPs
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            Log.warn("Webhook URL rejected: no host.");
            return;
        }
        InetAddress address = InetAddress.getByName(host);
        if (isBlockedAddress(address)) {
            Log.warnf("Webhook URL rejected: resolved IP %s is a blocked address.", address.getHostAddress());
            return;
        }

        // Build payload with "text" at root for Slack/Discord/Teams compatibility
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("text", payload.getMessage());
        bodyMap.put("operation", payload.getOperation());
        bodyMap.put("status", payload.getStatus());
        bodyMap.put("timestamp", payload.getTimestamp());
        if (payload.getErrorMessage() != null && !payload.getErrorMessage().isBlank()) {
            bodyMap.put("errorMessage", payload.getErrorMessage());
        }
        if (payload.getDetails() != null) {
            bodyMap.put("details", payload.getDetails());
        }
        String body = jsonb.toJson(bodyMap);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DockerWebHandler-Webhook/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        // HMAC signature if secret is configured
        if (webhookSecret.isPresent() && !webhookSecret.get().isBlank()) {
            String signature = computeHmacSha256(webhookSecret.get(), body);
            requestBuilder.header("X-Webhook-Signature", signature);
        }

        HttpResponse<String> response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        Log.infof("Webhook delivered to %s — status: %d", url, response.statusCode());
    }

    private HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return httpClient;
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }

    private String computeHmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            Log.warnf("HMAC computation failed: %s", e.getMessage());
            return "";
        }
    }

    private String renderTemplate(String template, WebhookPayload payload) {
        Map<String, String> details = payload.getDetails() != null ? payload.getDetails() : Map.of();
        String result = template;
        result = result.replace("{operation}", payload.getOperation() != null ? payload.getOperation().name() : "");
        result = result.replace("{status}", payload.getStatus() != null ? payload.getStatus().name() : "");
        result = result.replace("{timestamp}", Instant.now().toString());
        result = result.replace("{errorMessage}", sanitizeErrorMessage(payload.getErrorMessage()));
        for (Map.Entry<String, String> entry : details.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        result = result.replaceAll("\\{[a-zA-Z]+}", "");
        return result.trim();
    }

    private String sanitizeErrorMessage(String msg) {
        if (msg == null || msg.isBlank()) return "";
        // Truncate and strip file paths
        String sanitized = msg.length() > 500 ? msg.substring(0, 500) : msg;
        sanitized = sanitized.replaceAll("[/\\\\][\\w./\\\\-]+", "[path]");
        return sanitized;
    }

    public WebhookPayload buildContainerPayload(WebhookPayload.Status status, String repository,
                                                  String tag, String containerName,
                                                  String ports, String errorMessage) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("repository", repository != null ? repository : "");
        details.put("tag", tag != null ? tag : "");
        details.put("containerName", containerName != null ? containerName : "");
        details.put("port", ports != null && !ports.isBlank() ? ports.split(",")[0].trim() : "");
        details.put("ports", ports != null ? ports : "");
        return new WebhookPayload(
                WebhookPayload.Operation.CONTAINER_CREATION, status,
                null, null, errorMessage, details);
    }

    public WebhookPayload buildRestorePayload(WebhookPayload.Status status, String repository,
                                                String targetDatabase, String dumpFilename, String errorMessage) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("repository", repository != null ? repository : "");
        details.put("targetDatabase", targetDatabase != null ? targetDatabase : "");
        details.put("dumpFilename", dumpFilename != null ? dumpFilename : "");
        return new WebhookPayload(
                WebhookPayload.Operation.DATABASE_RESTORE, status,
                null, null, errorMessage, details);
    }
}
