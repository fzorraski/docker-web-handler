package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.domain.model.ContainerEvent;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;


public final class SseHelper {

    private SseHelper() {}

    public static void sendEvent(SseEventSink sink, Sse sse, ContainerEvent event) {
        if (sink.isClosed()) return;
        try {
            sink.send(sse.newEventBuilder()
                    .data(ContainerEvent.class, event)
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .build());
        } catch (IllegalStateException ignored) {
            // Response already written — client disconnected
        }
    }

    public static void closeSink(SseEventSink sink) {
        if (sink.isClosed()) return;
        try {
            sink.close();
        } catch (IllegalStateException ignored) {
            // Response already written — client disconnected
        }
    }
}
