package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.interfaces.rest.dto.*;

public final class PayloadTruncationMapper {

    private PayloadTruncationMapper() {
    }

    public static ApiCallPairResponse toResponse(ApiCallPair pair, int threshold) {
        String reqPayload = pair.requestPayload();
        String resPayload = pair.responsePayload();
        int reqSize = reqPayload != null ? reqPayload.length() : 0;
        int resSize = resPayload != null ? resPayload.length() : 0;
        boolean reqTruncated = reqSize > threshold;
        boolean resTruncated = resSize > threshold;

        return new ApiCallPairResponse(
                pair.endpoint(),
                pair.correlationId(),
                pair.thread(),
                pair.requestTimestamp(),
                pair.responseTimestamp(),
                pair.durationMs(),
                reqTruncated ? reqPayload.substring(0, threshold) : reqPayload,
                resTruncated ? resPayload.substring(0, threshold) : resPayload,
                pair.requestLineNumber(),
                pair.responseLineNumber(),
                pair.sourceFile(),
                pair.slow(),
                reqTruncated,
                resTruncated,
                reqSize,
                resSize
        );
    }

    public static LogLineResponse toResponse(LogLine line, int threshold) {
        String message = line.message();
        int size = message != null ? message.length() : 0;
        boolean truncated = size > threshold;

        return new LogLineResponse(
                line.lineNumber(),
                line.timestamp(),
                line.level(),
                line.logger(),
                line.thread(),
                truncated ? message.substring(0, threshold) : message,
                line.sourceFile(),
                truncated,
                size
        );
    }

    public static OrphanRequestResponse toResponse(OrphanRequest req, int threshold) {
        String payload = req.payload();
        int size = payload != null ? payload.length() : 0;
        boolean truncated = size > threshold;

        return new OrphanRequestResponse(
                req.endpoint(),
                req.thread(),
                req.timestamp(),
                truncated ? payload.substring(0, threshold) : payload,
                req.lineNumber(),
                req.sourceFile(),
                truncated,
                size
        );
    }

    public static CustomFieldMatchResponse toResponse(CustomFieldMatch match, int threshold) {
        String msg = match.fullMessage();
        int size = msg != null ? msg.length() : 0;
        boolean truncated = size > threshold;

        return new CustomFieldMatchResponse(
                match.lineNumber(),
                match.timestamp(),
                match.thread(),
                match.sourceFile(),
                truncated ? msg.substring(0, threshold) : msg,
                match.groups(),
                truncated,
                size
        );
    }

    public static RepeatedFailureResponse toResponse(RepeatedFailure failure, int threshold) {
        var details = failure.details().stream()
                .map(d -> toFailureDetailResponse(d, threshold))
                .toList();
        return new RepeatedFailureResponse(
                failure.entityId(),
                failure.reason(),
                failure.occurrences(),
                failure.firstSeen(),
                failure.lastSeen(),
                details
        );
    }

    private static FailureDetailResponse toFailureDetailResponse(RepeatedFailure.FailureDetail d, int threshold) {
        String msg = d.message();
        int size = msg != null ? msg.length() : 0;
        boolean truncated = size > threshold;

        return new FailureDetailResponse(
                d.timestamp(),
                d.lineNumber(),
                truncated ? msg.substring(0, threshold) : msg,
                d.sourceFile(),
                truncated,
                size
        );
    }

    public static NpeOccurrenceResponse toResponse(NpeOccurrence occ, int threshold) {
        String msg = occ.message();
        int size = msg != null ? msg.length() : 0;
        boolean truncated = size > threshold;

        return new NpeOccurrenceResponse(
                occ.originClass(),
                occ.method(),
                occ.sourceFile(),
                occ.sourceLine(),
                truncated ? msg.substring(0, threshold) : msg,
                occ.timestamp(),
                occ.logLineNumber(),
                occ.logSourceFile(),
                occ.stackTrace(),
                truncated,
                size
        );
    }

    public static NpeLocationSummaryResponse toResponse(NpeLocationSummary summary, int threshold) {
        var occurrences = summary.occurrences().stream()
                .map(o -> toResponse(o, threshold))
                .toList();
        return new NpeLocationSummaryResponse(
                summary.origin(),
                summary.originClass(),
                summary.method(),
                summary.sourceFile(),
                summary.sourceLine(),
                summary.count(),
                summary.firstSeen(),
                summary.lastSeen(),
                occurrences
        );
    }

    public static ExceptionOccurrenceResponse toResponse(ExceptionOccurrence occ, int threshold) {
        String msg = occ.message();
        int size = msg != null ? msg.length() : 0;
        boolean truncated = size > threshold;

        return new ExceptionOccurrenceResponse(
                occ.exceptionType(),
                occ.originClass(),
                occ.method(),
                occ.sourceFile(),
                occ.sourceLine(),
                truncated ? msg.substring(0, threshold) : msg,
                occ.timestamp(),
                occ.logLineNumber(),
                occ.logSourceFile(),
                occ.stackTrace(),
                truncated,
                size
        );
    }

    public static ExceptionLocationSummaryResponse toResponse(ExceptionLocationSummary summary, int threshold) {
        var occurrences = summary.occurrences().stream()
                .map(o -> toResponse(o, threshold))
                .toList();
        return new ExceptionLocationSummaryResponse(
                summary.exceptionType(),
                summary.origin(),
                summary.originClass(),
                summary.method(),
                summary.sourceFile(),
                summary.sourceLine(),
                summary.count(),
                summary.firstSeen(),
                summary.lastSeen(),
                occurrences
        );
    }

    public static CriticalIssueResponse toResponse(CriticalIssue issue, int threshold) {
        String msg = issue.message();
        int size = msg != null ? msg.length() : 0;
        boolean truncated = size > threshold;

        return new CriticalIssueResponse(
                issue.category(),
                issue.severity(),
                issue.pattern(),
                issue.lineNumber(),
                issue.timestamp(),
                truncated ? msg.substring(0, threshold) : msg,
                issue.sourceFile(),
                truncated,
                size
        );
    }

    public static CriticalBurstResponse toResponse(CriticalBurst burst, int threshold) {
        var issues = burst.issues().stream()
                .map(i -> toResponse(i, threshold))
                .toList();
        return new CriticalBurstResponse(
                burst.category(),
                burst.severity(),
                burst.burstStart(),
                burst.burstEnd(),
                burst.issueCount(),
                issues
        );
    }

    public static CriticalIssueSummaryResponse toResponse(CriticalIssueSummary summary, int threshold) {
        var issues = summary.issues().stream()
                .map(i -> toResponse(i, threshold))
                .toList();
        var bursts = summary.bursts().stream()
                .map(b -> toResponse(b, threshold))
                .toList();
        return new CriticalIssueSummaryResponse(
                summary.category(),
                summary.severity(),
                summary.count(),
                summary.firstSeen(),
                summary.lastSeen(),
                issues,
                bursts
        );
    }
}
