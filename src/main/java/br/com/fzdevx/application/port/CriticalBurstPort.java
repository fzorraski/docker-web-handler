package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.CriticalIssueSummary;

import java.util.List;

public interface CriticalBurstPort {

    List<CriticalIssueSummary> computeBursts(List<CriticalIssueSummary> summaries);

    List<CriticalIssueSummary> computeBursts(List<CriticalIssueSummary> summaries, int threshold, int windowMinutes);
}
