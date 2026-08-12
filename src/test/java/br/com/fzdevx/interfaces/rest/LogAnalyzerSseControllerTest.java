package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.LogPreset;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.interfaces.rest.util.LogAnalysisBroadcaster;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogAnalyzerSseControllerTest {

    @Mock AnalyzeLogFileUseCase analyzeLogFileUseCase;
    @Mock RequestStash requestStash;
    @Mock LogAnalysisBroadcaster broadcaster;
    @Mock LogAnalyzerController logAnalyzerController;
    @Mock SseEventSink sink;
    @Mock Sse sse;
    @Mock OutboundSseEvent.Builder eventBuilder;
    @Mock OutboundSseEvent outboundEvent;
    @Mock br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;
    @Mock br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    @InjectMocks
    LogAnalyzerSseController controller;

    private AnalyzeLogFileRequest request;

    @BeforeEach
    void setUp() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(true);

        // Prepare a mock request
        request = mock(AnalyzeLogFileRequest.class);
        when(request.getFilenames()).thenReturn(List.of("server.log"));
        when(request.getTempFiles()).thenReturn(List.of(Path.of("/tmp/server.log")));
        when(request.getPreset()).thenReturn(LogPreset.byName("WILDFLY"));
        when(request.getSlowThresholdMs()).thenReturn(1000);
        when(request.getOptions()).thenReturn(AnalysisOptions.all());
        when(request.getLabel()).thenReturn(null);

        // Mock SSE plumbing
        when(sink.isClosed()).thenReturn(false);
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.data(eq(ContainerEvent.class), any())).thenReturn(eventBuilder);
        when(eventBuilder.mediaType(any())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(outboundEvent);
    }

    // ---- streamAnalysis: invalid ticket ----

    @Test
    void streamAnalysis_invalidTicket_sendsErrorAndCloses() {
        when(requestStash.retrieveLogAnalysis("bad-ticket")).thenReturn(null);

        controller.streamAnalysis("bad-ticket", sink, sse);

        // Should send error event and close, no broadcasts
        verify(broadcaster, never()).broadcastStarted(any(), any(), anyList());
        verify(broadcaster, never()).broadcastCompleted(any(), any(), any(), anyList());
    }

    // ---- streamAnalysis: successful analysis ----

    @Test
    void streamAnalysis_success_broadcastsStartedAndCompleted() {
        when(requestStash.retrieveLogAnalysis("ticket-ok")).thenReturn(request);

        // Simulate analyzeWithProgress sending a SUCCESS event
        doAnswer(inv -> {
            Consumer<ContainerEvent> eventSink = inv.getArgument(5);
            eventSink.accept(ContainerEvent.success("Complete", "Done", "analysis-123"));
            return null;
        }).when(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-ok"), any(), any());

        when(analyzeLogFileUseCase.get("analysis-123")).thenReturn(
                new LogAnalysis(List.of(), 0, null, null, List.of(), List.of(),
                        List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(), List.of()));

        controller.streamAnalysis("ticket-ok", sink, sse);

        // broadcastStarted is called at prepare time, not stream time
        verify(broadcaster).broadcastCompleted("", "server.log", "analysis-123", List.of("server.log"));
        verify(request).cleanupTempFiles();
    }

    // ---- streamAnalysis: cancelled analysis ----

    @Test
    void streamAnalysis_cancelled_broadcastsCompletedWithEmptyId() {
        when(requestStash.retrieveLogAnalysis("ticket-cancel")).thenReturn(request);

        // analyzeWithProgress catches CancellationException internally and sends ERROR event
        doAnswer(inv -> {
            Consumer<ContainerEvent> eventSink = inv.getArgument(5);
            eventSink.accept(ContainerEvent.error("Cancelled", "Analysis cancelled."));
            return null;
        }).when(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-cancel"), any(), any());

        controller.streamAnalysis("ticket-cancel", sink, sse);

        // broadcastStarted is called at prepare time, not stream time
        // Key assertion: broadcastCompleted IS called even on cancellation
        verify(broadcaster).broadcastCompleted("", "server.log", "", List.of("server.log"));
        verify(request).cleanupTempFiles();
    }

    // ---- streamAnalysis: failed analysis ----

    @Test
    void streamAnalysis_failure_broadcastsCompletedWithEmptyId() {
        when(requestStash.retrieveLogAnalysis("ticket-fail")).thenReturn(request);

        // analyzeWithProgress catches exceptions internally and sends ERROR event
        doAnswer(inv -> {
            Consumer<ContainerEvent> eventSink = inv.getArgument(5);
            eventSink.accept(ContainerEvent.error("Error", "Analysis failed: Parse error"));
            return null;
        }).when(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-fail"), any(), any());

        controller.streamAnalysis("ticket-fail", sink, sse);

        // broadcastStarted is called at prepare time, not stream time
        // Key assertion: broadcastCompleted IS called even on failure
        verify(broadcaster).broadcastCompleted("", "server.log", "", List.of("server.log"));
        verify(request).cleanupTempFiles();
    }

    // ---- streamAnalysis: cancelled via event sink (not exception) ----

    @Test
    void streamAnalysis_cancelledViaErrorEvent_broadcastsCompletedWithEmptyId() {
        when(requestStash.retrieveLogAnalysis("ticket-cancel2")).thenReturn(request);

        // Simulate analyzeWithProgress sending an ERROR event (cancelled) but not throwing
        doAnswer(inv -> {
            Consumer<ContainerEvent> eventSink = inv.getArgument(5);
            eventSink.accept(ContainerEvent.error("Cancelled", "Analysis cancelled."));
            return null;
        }).when(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-cancel2"), any(), any());

        controller.streamAnalysis("ticket-cancel2", sink, sse);

        // broadcastStarted is called at prepare time, not stream time
        // succeeded[0] is false (no SUCCESS event), so the else branch fires
        verify(broadcaster).broadcastCompleted("", "server.log", "", List.of("server.log"));
        verify(request).cleanupTempFiles();
    }

    // ---- streamAnalysis: attribution handed to the use case ----

    @Test
    void streamAnalysis_passesTrimmedLabelAndUploaderAsAttribution() {
        when(request.getLabel()).thenReturn("  my-label  ");
        when(requestStash.retrieveLogAnalysis("ticket-label")).thenReturn(request);
        when(actorResolver.usernameOrSystem()).thenReturn("alice");

        doAnswer(inv -> {
            Consumer<ContainerEvent> eventSink = inv.getArgument(5);
            eventSink.accept(ContainerEvent.success("Complete", "Done", "analysis-456"));
            return null;
        }).when(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-label"), any(), any());

        controller.streamAnalysis("ticket-label", sink, sse);

        // stamping happens inside the use case, before the analysis is published and the
        // SUCCESS event fires — the controller only supplies the values
        var captor = ArgumentCaptor.forClass(AnalyzeLogFileUseCase.Attribution.class);
        verify(analyzeLogFileUseCase).analyzeWithProgress(
                anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-label"), any(),
                captor.capture());
        assertEquals("my-label", captor.getValue().label());
        assertEquals("alice", captor.getValue().uploadedBy());
        verify(broadcaster).broadcastCompleted("", "server.log", "analysis-456", List.of("server.log"));
    }

    // ---- streamAnalysis: temp files always cleaned up ----

    @Test
    void streamAnalysis_alwaysCleansUpTempFiles_evenOnException() {
        when(requestStash.retrieveLogAnalysis("ticket-cleanup")).thenReturn(request);

        doThrow(new OutOfMemoryError("heap"))
                .when(analyzeLogFileUseCase).analyzeWithProgress(
                        anyList(), anyList(), any(), anyInt(), any(), any(), eq("ticket-cleanup"), any(), any());

        assertThrows(OutOfMemoryError.class, () ->
                controller.streamAnalysis("ticket-cleanup", sink, sse));

        // Even on OOM, temp files must be cleaned
        verify(request).cleanupTempFiles();
    }

    // ---- cancelAnalysis ----

    @Test
    void cancelAnalysis_delegatesToUseCase() {
        when(analyzeLogFileUseCase.cancel("ticket-x")).thenReturn(true);

        var result = controller.cancelAnalysis("ticket-x");

        assertEquals(true, result.get("cancelled"));
    }

    @Test
    void cancelAnalysis_unknownTicket_returnsFalse() {
        when(analyzeLogFileUseCase.cancel("unknown")).thenReturn(false);

        var result = controller.cancelAnalysis("unknown");

        assertEquals(false, result.get("cancelled"));
    }
}
