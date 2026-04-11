package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.model.anomaly.Signal;
import br.com.fzdevx.domain.model.anomaly.SignalType;

import java.util.List;
import java.util.Map;

public interface SignalExtractionPort {

    List<Signal> extract(List<LogLine> lines, SignalType type,
                         List<ApiCallPair> apiCalls, List<JobExecution> jobExecutions,
                         List<OrphanRequest> orphanRequests);

    Map<SignalType, List<Signal>> extractAll(List<LogLine> lines,
                                             List<ApiCallPair> apiCalls,
                                             List<JobExecution> jobExecutions,
                                             List<OrphanRequest> orphanRequests);

    List<SignalType> detectAvailableTypes(List<LogLine> lines,
                                          List<ApiCallPair> apiCalls,
                                          List<JobExecution> jobExecutions,
                                          List<OrphanRequest> orphanRequests);
}
